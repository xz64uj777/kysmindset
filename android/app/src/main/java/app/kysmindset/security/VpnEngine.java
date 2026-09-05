package app.kysmindset.security;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TUN reader + blocking sockets. Each TCP/UDP session is its own thread so we
 * never register NIO channels from the wrong thread (that broke the first pass).
 */
final class VpnEngine {
    private static final byte[] FIN = new byte[0];
    private final VpnService vpn;
    private final ConnLog log;
    private final FileInputStream in;
    private final FileOutputStream out;
    private volatile boolean running;
    private final Map<String, TcpSess> tcp = new ConcurrentHashMap<>();
    private final Map<String, UdpSess> udp = new ConcurrentHashMap<>();
    private final AtomicInteger ipId = new AtomicInteger(1);
    private final AtomicInteger seqGen = new AtomicInteger((int) (System.nanoTime() & 0x3fffffff) + 10000);
    private Thread reader;

    VpnEngine(VpnService vpn, ParcelFileDescriptor tun, ConnLog log) {
        this.vpn = vpn;
        this.log = log;
        this.in = new FileInputStream(tun.getFileDescriptor());
        this.out = new FileOutputStream(tun.getFileDescriptor());
    }

    void start() {
        running = true;
        reader = new Thread(this::readLoop, "kys-tun");
        reader.setDaemon(true);
        reader.start();
    }

    void stop() {
        running = false;
        closeAll();
        try {
            in.close();
        } catch (Exception ignored) {
        }
        try {
            out.close();
        } catch (Exception ignored) {
        }
    }

    private void readLoop() {
        byte[] buf = new byte[32767];
        while (running) {
            try {
                int n = in.read(buf);
                if (n <= 0) {
                    if (!running) break;
                    continue;
                }
                handle(buf, n);
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    private void handle(byte[] pkt, int n) {
        if (n < 20) return;
        int ver = (pkt[0] >> 4) & 0xf;
        if (ver == 4) {
            int ihl = (pkt[0] & 0xf) * 4;
            if (ihl < 20 || n < ihl + 4) return;
            int proto = pkt[9] & 0xff;
            byte[] src = slice(pkt, 12, 4);
            byte[] dst = slice(pkt, 16, 4);
            if (proto == 17) handleUdp(pkt, n, false, ihl, src, dst);
            else if (proto == 6) handleTcp(pkt, n, false, ihl, src, dst);
        } else if (ver == 6) {
            if (n < 48) return;
            int proto = pkt[6] & 0xff;
            byte[] src = slice(pkt, 8, 16);
            byte[] dst = slice(pkt, 24, 16);
            if (proto == 17) handleUdp(pkt, n, true, 40, src, dst);
            else if (proto == 6) handleTcp(pkt, n, true, 40, src, dst);
        }
    }

    private void handleUdp(byte[] pkt, int n, boolean v6, int off, byte[] src, byte[] dst) {
        if (n < off + 8) return;
        int srcPort = u16(pkt, off);
        int dstPort = u16(pkt, off + 2);
        int payloadOff = off + 8;
        int plen = n - payloadOff;
        InetAddress sAddr = addr(src);
        InetAddress dAddr = addr(dst);
        if (sAddr == null || dAddr == null) return;
        boolean ok = log.onPacket(17, sAddr, srcPort, dAddr, dstPort, Math.max(0, plen), true);
        if (!ok) return;
        if (dstPort == 53 && plen > 12) noteDnsQuery(pkt, payloadOff, plen);
        String key = (v6 ? "6" : "4") + "u" + srcPort + ":" + dAddr.getHostAddress() + ":" + dstPort;
        UdpSess s = udp.get(key);
        try {
            if (s == null) {
                if (udp.size() > 64) evict(udp);
                DatagramSocket sock = new DatagramSocket(null);
                vpn.protect(sock);
                sock.bind(new InetSocketAddress(0));
                sock.connect(dAddr, dstPort);
                sock.setSoTimeout(200);
                s = new UdpSess();
                s.sock = sock;
                s.v6 = v6;
                s.src = src;
                s.dst = dst;
                s.srcPort = srcPort;
                s.dstPort = dstPort;
                s.alive = true;
                udp.put(key, s);
                final UdpSess sess = s;
                Thread t = new Thread(() -> recvUdp(key, sess), "kys-u-" + srcPort);
                t.setDaemon(true);
                s.th = t;
                t.start();
            }
            if (plen > 0) {
                s.sock.send(new DatagramPacket(pkt, payloadOff, plen));
            }
        } catch (Exception e) {
            closeUdp(key);
        }
    }

    private void recvUdp(String key, UdpSess s) {
        byte[] buf = new byte[8192];
        while (running && s.alive) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.sock.receive(p);
                int n = p.getLength();
                if (n <= 0) continue;
                log.addBytes(n, false);
                if (s.dstPort == 53) parseDnsAnswers(buf, n);
                injectUdp(s, buf, n);
            } catch (SocketTimeoutException ignored) {
            } catch (Exception e) {
                break;
            }
        }
        closeUdp(key);
    }

    private void handleTcp(byte[] pkt, int n, boolean v6, int off, byte[] src, byte[] dst) {
        if (n < off + 20) return;
        int srcPort = u16(pkt, off);
        int dstPort = u16(pkt, off + 2);
        long seq = u32(pkt, off + 4);
        int doff = ((pkt[off + 12] >> 4) & 0xf) * 4;
        int flags = pkt[off + 13] & 0xff;
        int payloadOff = off + doff;
        int plen = Math.max(0, n - payloadOff);
        boolean syn = (flags & 0x02) != 0;
        boolean fin = (flags & 0x01) != 0;
        boolean rst = (flags & 0x04) != 0;
        InetAddress sAddr = addr(src);
        InetAddress dAddr = addr(dst);
        if (sAddr == null || dAddr == null) return;
        boolean ok = log.onPacket(6, sAddr, srcPort, dAddr, dstPort, plen, true);
        String key = (v6 ? "6" : "4") + "t" + srcPort + ":" + dAddr.getHostAddress() + ":" + dstPort;
        if (!ok) {
            if (syn) injectRst(v6, src, dst, srcPort, dstPort, seq);
            closeTcp(key);
            return;
        }
        if (rst) {
            closeTcp(key);
            return;
        }
        TcpSess s = tcp.get(key);
        if (s == null) {
            if (!syn) return;
            if (tcp.size() > 96) evict(tcp);
            s = new TcpSess();
            s.v6 = v6;
            s.src = src;
            s.dst = dst;
            s.srcPort = srcPort;
            s.dstPort = dstPort;
            s.theirSeq = seq + 1;
            s.mySeq = seqGen.addAndGet(4096);
            s.q = new LinkedBlockingQueue<>(256);
            s.alive = true;
            tcp.put(key, s);
            final TcpSess sess = s;
            Thread t = new Thread(() -> runTcp(key, sess, dAddr, dstPort), "kys-t-" + srcPort);
            t.setDaemon(true);
            s.th = t;
            t.start();
            return;
        }
        if (fin) {
            s.theirSeq = seq + 1 + plen;
            s.q.offer(FIN);
            return;
        }
        if (plen > 0) {
            s.theirSeq = seq + plen;
            byte[] copy = Arrays.copyOfRange(pkt, payloadOff, payloadOff + plen);
            if (!s.q.offer(copy)) {
                /* slow app; drop this chunk */
            }
        }
    }

    private void runTcp(String key, TcpSess s, InetAddress dest, int port) {
        Socket sock = new Socket();
        s.sock = sock;
        try {
            vpn.protect(sock);
            sock.setTcpNoDelay(true);
            sock.setKeepAlive(true);
            sock.connect(new InetSocketAddress(dest, port), 8000);
            sock.setSoTimeout(50);
            injectTcp(s, 0x12, synAckOpts(), 0); // SYN+ACK + MSS
            s.mySeq++;
            InputStream is = sock.getInputStream();
            OutputStream os = sock.getOutputStream();
            byte[] buf = new byte[8192];
            while (running && s.alive && !sock.isClosed()) {
                byte[] outb;
                while ((outb = s.q.poll()) != null) {
                    if (outb == FIN) {
                        injectTcp(s, 0x11, null, 0);
                        s.alive = false;
                        break;
                    }
                    os.write(outb);
                    os.flush();
                    injectTcp(s, 0x10, null, 0); // ACK
                }
                if (!s.alive) break;
                try {
                    int n = is.read(buf);
                    if (n < 0) {
                        injectTcp(s, 0x11, null, 0);
                        break;
                    }
                    if (n == 0) continue;
                    log.addBytes(n, false);
                    byte[] payload = Arrays.copyOf(buf, n);
                    injectTcp(s, 0x18, payload, n); // PSH+ACK
                    s.mySeq += n;
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (Exception e) {
            injectRst(s.v6, s.src, s.dst, s.srcPort, s.dstPort, s.theirSeq - 1);
        } finally {
            closeTcp(key);
        }
    }

    private static byte[] synAckOpts() {
        return new byte[] {2, 4, 0x05, 0x50, 1, 1, 1, 1}; // MSS 1360 + NOP pad
    }

    private void injectUdp(UdpSess s, byte[] payload, int len) {
        writeTun(buildUdp(s.v6, s.dst, s.src, s.dstPort, s.srcPort, payload, 0, len));
    }

    private void injectTcp(TcpSess s, int flags, byte[] payload, int len) {
        writeTun(
            buildTcp(s.v6, s.dst, s.src, s.dstPort, s.srcPort, s.mySeq, s.theirSeq, flags, payload, len));
    }

    private void injectRst(boolean v6, byte[] src, byte[] dst, int srcPort, int dstPort, long seq) {
        writeTun(buildTcp(v6, dst, src, dstPort, srcPort, 0, seq + 1, 0x14, null, 0));
    }

    private synchronized void writeTun(byte[] pkt) {
        if (pkt == null) return;
        try {
            out.write(pkt);
        } catch (Exception ignored) {
        }
    }

    private byte[] buildUdp(
        boolean v6, byte[] src, byte[] dst, int srcPort, int dstPort, byte[] payload, int off, int len) {
        if (v6) {
            int total = 40 + 8 + len;
            byte[] p = new byte[total];
            p[0] = 0x60;
            put16(p, 4, 8 + len);
            p[6] = 17;
            p[7] = 64;
            System.arraycopy(src, 0, p, 8, 16);
            System.arraycopy(dst, 0, p, 24, 16);
            put16(p, 40, srcPort);
            put16(p, 42, dstPort);
            put16(p, 44, 8 + len);
            if (len > 0) System.arraycopy(payload, off, p, 48, len);
            put16(p, 46, udpSum6(p, src, dst, 8 + len));
            return p;
        }
        int total = 20 + 8 + len;
        byte[] p = new byte[total];
        p[0] = 0x45;
        put16(p, 2, total);
        put16(p, 4, ipId.getAndIncrement() & 0xffff);
        p[8] = 64;
        p[9] = 17;
        System.arraycopy(src, 0, p, 12, 4);
        System.arraycopy(dst, 0, p, 16, 4);
        put16(p, 10, ipSum(p, 0, 20));
        put16(p, 20, srcPort);
        put16(p, 22, dstPort);
        put16(p, 24, 8 + len);
        if (len > 0) System.arraycopy(payload, off, p, 28, len);
        return p;
    }

    private byte[] buildTcp(
        boolean v6,
        byte[] src,
        byte[] dst,
        int srcPort,
        int dstPort,
        long seq,
        long ack,
        int flags,
        byte[] payload,
        int len) {
        boolean syn = (flags & 0x02) != 0;
        byte[] opt = syn ? synAckOpts() : null;
        int optLen = opt == null ? 0 : opt.length;
        int l = payload == null ? 0 : len;
        int tcpLen = 20 + optLen + l;
        if (v6) {
            byte[] p = new byte[40 + tcpLen];
            p[0] = 0x60;
            put16(p, 4, tcpLen);
            p[6] = 6;
            p[7] = 64;
            System.arraycopy(src, 0, p, 8, 16);
            System.arraycopy(dst, 0, p, 24, 16);
            fillTcp(p, 40, srcPort, dstPort, seq, ack, flags, opt);
            if (l > 0) System.arraycopy(payload, 0, p, 40 + 20 + optLen, l);
            put16(p, 56, tcpSum(p, 40, tcpLen, src, dst, 6));
            return p;
        }
        byte[] p = new byte[20 + tcpLen];
        p[0] = 0x45;
        put16(p, 2, p.length);
        put16(p, 4, ipId.getAndIncrement() & 0xffff);
        p[8] = 64;
        p[9] = 6;
        System.arraycopy(src, 0, p, 12, 4);
        System.arraycopy(dst, 0, p, 16, 4);
        put16(p, 10, ipSum(p, 0, 20));
        fillTcp(p, 20, srcPort, dstPort, seq, ack, flags, opt);
        if (l > 0) System.arraycopy(payload, 0, p, 40 + optLen, l);
        put16(p, 36, tcpSum(p, 20, tcpLen, src, dst, 6));
        return p;
    }

    private static void fillTcp(
        byte[] p, int off, int srcPort, int dstPort, long seq, long ack, int flags, byte[] opt) {
        int optLen = opt == null ? 0 : opt.length;
        put16(p, off, srcPort);
        put16(p, off + 2, dstPort);
        put32(p, off + 4, seq);
        put32(p, off + 8, ack);
        int words = (20 + optLen) / 4;
        p[off + 12] = (byte) (words << 4);
        p[off + 13] = (byte) flags;
        put16(p, off + 14, 65535);
        if (optLen > 0) System.arraycopy(opt, 0, p, off + 20, optLen);
    }

    private static int tcpSum(byte[] pkt, int tcpOff, int tcpLen, byte[] src, byte[] dst, int proto) {
        int sum = 0;
        for (int i = 0; i < src.length; i += 2) sum += ((src[i] & 0xff) << 8) | (src[i + 1] & 0xff);
        for (int i = 0; i < dst.length; i += 2) sum += ((dst[i] & 0xff) << 8) | (dst[i + 1] & 0xff);
        sum += proto;
        sum += tcpLen;
        for (int i = 0; i < tcpLen - 1; i += 2) sum += u16(pkt, tcpOff + i);
        if ((tcpLen & 1) != 0) sum += (pkt[tcpOff + tcpLen - 1] & 0xff) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        return ~sum & 0xffff;
    }

    private static int udpSum6(byte[] pkt, byte[] src, byte[] dst, int udpLen) {
        return tcpSum(pkt, 40, udpLen, src, dst, 17);
    }

    private static int ipSum(byte[] b, int off, int len) {
        int sum = 0;
        for (int i = 0; i < len - 1; i += 2) sum += u16(b, off + i);
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        return ~sum & 0xffff;
    }

    private void noteDnsQuery(byte[] pkt, int off, int len) {
        try {
            if (len < 12) return;
            String q = readName(pkt, off, off + 12, off + len);
            if (q != null) pendingQuery = q;
        } catch (Exception ignored) {
        }
    }

    private volatile String pendingQuery;

    private void parseDnsAnswers(byte[] d, int len) {
        if (len < 12) return;
        int qd = u16(d, 4);
        int an = u16(d, 6);
        int pos = 12;
        try {
            String q = pendingQuery;
            for (int i = 0; i < qd; i++) {
                int start = pos;
                pos = skipName(d, pos, len);
                if (q == null) q = readName(d, 0, start, len);
                pos += 4;
            }
            for (int i = 0; i < an && pos + 10 <= len; i++) {
                pos = skipName(d, pos, len);
                int type = u16(d, pos);
                pos += 8;
                int rdlen = u16(d, pos);
                pos += 2;
                if (pos + rdlen > len) break;
                if (type == 1 && rdlen == 4 && q != null) {
                    String ip =
                        (d[pos] & 0xff)
                            + "."
                            + (d[pos + 1] & 0xff)
                            + "."
                            + (d[pos + 2] & 0xff)
                            + "."
                            + (d[pos + 3] & 0xff);
                    log.rememberDns(ip, q);
                } else if (type == 28 && rdlen == 16 && q != null) {
                    InetAddress a = InetAddress.getByAddress(slice(d, pos, 16));
                    log.rememberDns(a.getHostAddress(), q);
                }
                pos += rdlen;
            }
        } catch (Exception ignored) {
        }
    }

    private static String readName(byte[] d, int base, int pos, int end) {
        StringBuilder b = new StringBuilder();
        int guard = 0;
        while (pos < end && guard++ < 40) {
            int l = d[pos] & 0xff;
            if (l == 0) break;
            if ((l & 0xc0) == 0xc0) {
                if (pos + 1 >= end) break;
                pos = ((l & 0x3f) << 8) | (d[pos + 1] & 0xff);
                continue;
            }
            pos++;
            if (pos + l > end) break;
            if (b.length() > 0) b.append('.');
            b.append(new String(d, pos, l));
            pos += l;
        }
        return b.length() == 0 ? null : b.toString();
    }

    private static int skipName(byte[] d, int pos, int end) {
        int guard = 0;
        while (pos < end && guard++ < 40) {
            int l = d[pos] & 0xff;
            if (l == 0) return pos + 1;
            if ((l & 0xc0) == 0xc0) return pos + 2;
            pos += 1 + l;
        }
        return pos;
    }

    private void evict(Map<String, ?> m) {
        String first = null;
        for (String k : m.keySet()) {
            first = k;
            break;
        }
        if (first == null) return;
        if (m == udp) closeUdp(first);
        else closeTcp(first);
    }

    private void closeUdp(String key) {
        UdpSess s = udp.remove(key);
        if (s == null) return;
        s.alive = false;
        try {
            s.sock.close();
        } catch (Exception ignored) {
        }
    }

    private void closeTcp(String key) {
        TcpSess s = tcp.remove(key);
        if (s == null) return;
        s.alive = false;
        try {
            if (s.sock != null) s.sock.close();
        } catch (Exception ignored) {
        }
    }

    private void closeAll() {
        for (String k : new java.util.ArrayList<>(udp.keySet())) closeUdp(k);
        for (String k : new java.util.ArrayList<>(tcp.keySet())) closeTcp(k);
    }

    private static InetAddress addr(byte[] a) {
        try {
            return InetAddress.getByAddress(a);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] slice(byte[] a, int off, int len) {
        byte[] o = new byte[len];
        System.arraycopy(a, off, o, 0, len);
        return o;
    }

    private static int u16(byte[] b, int i) {
        return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
    }

    private static long u32(byte[] b, int i) {
        return ((long) (b[i] & 0xff) << 24)
            | ((long) (b[i + 1] & 0xff) << 16)
            | ((b[i + 2] & 0xff) << 8)
            | (b[i + 3] & 0xff);
    }

    private static void put16(byte[] b, int i, int v) {
        b[i] = (byte) (v >> 8);
        b[i + 1] = (byte) v;
    }

    private static void put32(byte[] b, int i, long v) {
        b[i] = (byte) (v >> 24);
        b[i + 1] = (byte) (v >> 16);
        b[i + 2] = (byte) (v >> 8);
        b[i + 3] = (byte) v;
    }

    static final class UdpSess {
        DatagramSocket sock;
        Thread th;
        boolean v6;
        byte[] src;
        byte[] dst;
        int srcPort;
        int dstPort;
        volatile boolean alive;
    }

    static final class TcpSess {
        Socket sock;
        Thread th;
        LinkedBlockingQueue<byte[]> q;
        boolean v6;
        byte[] src;
        byte[] dst;
        int srcPort;
        int dstPort;
        int mySeq;
        volatile long theirSeq;
        volatile boolean alive;
    }
}
