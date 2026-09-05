package app.kysmindset.security;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reads the TUN, logs every connection, and forwards allowed packets so the
 * phone still has internet. Blocked apps are dropped.
 */
final class VpnEngine {
    private final VpnService vpn;
    private final ParcelFileDescriptor tun;
    private final ConnLog log;
    private final FileInputStream in;
    private final FileOutputStream out;
    private volatile boolean running;
    private Selector selector;
    private final Map<String, TcpPipe> tcp = new ConcurrentHashMap<>();
    private final Map<String, UdpPipe> udp = new ConcurrentHashMap<>();
    private final AtomicInteger mySeqBase = new AtomicInteger((int) (System.nanoTime() & 0x7fffffff));
    private Thread reader;
    private Thread pump;

    VpnEngine(VpnService vpn, ParcelFileDescriptor tun, ConnLog log) {
        this.vpn = vpn;
        this.tun = tun;
        this.log = log;
        this.in = new FileInputStream(tun.getFileDescriptor());
        this.out = new FileOutputStream(tun.getFileDescriptor());
    }

    void start() {
        running = true;
        try {
            selector = Selector.open();
        } catch (Exception e) {
            return;
        }
        reader = new Thread(this::readLoop, "kys-tun");
        pump = new Thread(this::pumpLoop, "kys-sock");
        reader.start();
        pump.start();
    }

    void stop() {
        running = false;
        try {
            if (selector != null) selector.wakeup();
        } catch (Exception ignored) {
        }
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

    private void pumpLoop() {
        ByteBuffer read = ByteBuffer.allocate(32767);
        while (running) {
            try {
                selector.select(500);
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey k = it.next();
                    it.remove();
                    if (!k.isValid()) continue;
                    Object att = k.attachment();
                    if (att instanceof UdpPipe) {
                        if (k.isReadable()) drainUdp((UdpPipe) att, read);
                    } else if (att instanceof TcpPipe) {
                        TcpPipe p = (TcpPipe) att;
                        if (k.isConnectable()) finishTcp(p);
                        if (k.isValid() && k.isReadable()) drainTcp(p, read);
                    }
                }
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    private void handle(byte[] pkt, int n) {
        if (n < 20) return;
        int ver = (pkt[0] >> 4) & 0xf;
        if (ver == 4) handle4(pkt, n);
        else if (ver == 6) handle6(pkt, n);
    }

    private void handle4(byte[] pkt, int n) {
        int ihl = (pkt[0] & 0xf) * 4;
        if (ihl < 20 || n < ihl + 4) return;
        int proto = pkt[9] & 0xff;
        byte[] src = slice(pkt, 12, 4);
        byte[] dst = slice(pkt, 16, 4);
        if (proto == 17) handleUdp(pkt, n, false, ihl, src, dst, 17);
        else if (proto == 6) handleTcp(pkt, n, false, ihl, src, dst);
    }

    private void handle6(byte[] pkt, int n) {
        if (n < 48) return;
        int proto = pkt[6] & 0xff;
        byte[] src = slice(pkt, 8, 16);
        byte[] dst = slice(pkt, 24, 16);
        int off = 40;
        if (proto == 17) handleUdp(pkt, n, true, off, src, dst, 17);
        else if (proto == 6) handleTcp(pkt, n, true, off, src, dst);
    }

    private void handleUdp(byte[] pkt, int n, boolean v6, int off, byte[] src, byte[] dst, int proto) {
        if (n < off + 8) return;
        int srcPort = u16(pkt, off);
        int dstPort = u16(pkt, off + 2);
        int payload = off + 8;
        int plen = n - payload;
        InetAddress sAddr = addr(src);
        InetAddress dAddr = addr(dst);
        if (sAddr == null || dAddr == null) return;
        boolean ok = log.onPacket(17, sAddr, srcPort, dAddr, dstPort, Math.max(0, plen), true);
        if (!ok) return;
        String key = (v6 ? "6" : "4") + ":" + srcPort + ":" + dAddr.getHostAddress() + ":" + dstPort;
        UdpPipe p = udp.get(key);
        try {
            if (p == null) {
                if (udp.size() > 128) evictUdp();
                DatagramChannel ch = DatagramChannel.open();
                ch.configureBlocking(false);
                vpn.protect(ch.socket());
                ch.connect(new InetSocketAddress(dAddr, dstPort));
                p = new UdpPipe();
                p.ch = ch;
                p.v6 = v6;
                p.src = src;
                p.dst = dst;
                p.srcPort = srcPort;
                p.dstPort = dstPort;
                udp.put(key, p);
                ch.register(selector, SelectionKey.OP_READ, p);
                selector.wakeup();
            }
            if (plen > 0) p.ch.write(ByteBuffer.wrap(pkt, payload, plen));
        } catch (Exception e) {
            closeUdp(key);
        }
    }

    private void handleTcp(byte[] pkt, int n, boolean v6, int off, byte[] src, byte[] dst) {
        if (n < off + 20) return;
        int srcPort = u16(pkt, off);
        int dstPort = u16(pkt, off + 2);
        long seq = u32(pkt, off + 4);
        int doff = ((pkt[off + 12] >> 4) & 0xf) * 4;
        int flags = pkt[off + 13] & 0xff;
        int payload = off + doff;
        int plen = Math.max(0, n - payload);
        boolean syn = (flags & 0x02) != 0;
        boolean ack = (flags & 0x10) != 0;
        boolean fin = (flags & 0x01) != 0;
        boolean rst = (flags & 0x04) != 0;
        InetAddress sAddr = addr(src);
        InetAddress dAddr = addr(dst);
        if (sAddr == null || dAddr == null) return;
        boolean ok = log.onPacket(6, sAddr, srcPort, dAddr, dstPort, plen, true);
        String key = (v6 ? "6" : "4") + ":" + srcPort + ":" + dAddr.getHostAddress() + ":" + dstPort;
        if (!ok) {
            if (syn) injectRst(v6, src, dst, srcPort, dstPort, seq);
            closeTcp(key);
            return;
        }
        TcpPipe p = tcp.get(key);
        try {
            if (rst) {
                closeTcp(key);
                return;
            }
            if (p == null) {
                if (!syn) return;
                if (tcp.size() > 256) evictTcp();
                SocketChannel ch = SocketChannel.open();
                ch.configureBlocking(false);
                vpn.protect(ch.socket());
                p = new TcpPipe();
                p.ch = ch;
                p.v6 = v6;
                p.src = src;
                p.dst = dst;
                p.srcPort = srcPort;
                p.dstPort = dstPort;
                p.mySeq = mySeqBase.addAndGet(2048);
                p.theirSeq = seq + 1;
                p.status = TcpPipe.CONNECTING;
                tcp.put(key, p);
                ch.register(selector, SelectionKey.OP_CONNECT, p);
                ch.connect(new InetSocketAddress(dAddr, dstPort));
                selector.wakeup();
                return;
            }
            if (p.status == TcpPipe.CONNECTING) return;
            if (fin) {
                p.theirSeq = seq + 1 + plen;
                injectTcp(p, 0x11, null, 0); // FIN+ACK
                closeTcp(key);
                return;
            }
            if (plen > 0 && p.ch.isConnected()) {
                p.theirSeq = seq + plen;
                p.ch.write(ByteBuffer.wrap(pkt, payload, plen));
                injectTcp(p, 0x10, null, 0); // ACK
            }
        } catch (Exception e) {
            closeTcp(key);
        }
    }

    private void finishTcp(TcpPipe p) {
        try {
            if (p.ch.finishConnect()) {
                p.status = TcpPipe.ESTABLISHED;
                p.ch.register(selector, SelectionKey.OP_READ, p);
                injectTcp(p, 0x12, null, 0); // SYN+ACK
                p.mySeq++;
            } else {
                injectRst(p.v6, p.src, p.dst, p.srcPort, p.dstPort, p.theirSeq - 1);
                closeTcp(keyOf(p));
            }
        } catch (Exception e) {
            injectRst(p.v6, p.src, p.dst, p.srcPort, p.dstPort, p.theirSeq - 1);
            closeTcp(keyOf(p));
        }
    }

    private void drainUdp(UdpPipe p, ByteBuffer read) {
        try {
            read.clear();
            int n = p.ch.read(read);
            if (n <= 0) return;
            log.addBytes(n, false);
            if (p.dstPort == 53) {
                byte[] raw = new byte[n];
                read.flip();
                read.get(raw);
                parseDnsAnswers(raw, 0, n);
                injectUdp(p, raw, 0, n);
            } else {
                injectUdp(p, read.array(), read.arrayOffset(), n);
            }
        } catch (Exception e) {
            closeUdp(keyOf(p));
        }
    }

    private void drainTcp(TcpPipe p, ByteBuffer read) {
        try {
            read.clear();
            int n = p.ch.read(read);
            if (n < 0) {
                injectTcp(p, 0x11, null, 0);
                closeTcp(keyOf(p));
                return;
            }
            if (n == 0) return;
            log.addBytes(n, false);
            byte[] payload = new byte[n];
            read.flip();
            read.get(payload);
            injectTcp(p, 0x18, payload, n); // PSH+ACK
            p.mySeq += n;
        } catch (Exception e) {
            closeTcp(keyOf(p));
        }
    }

    private void injectUdp(UdpPipe p, byte[] payload, int off, int len) {
        byte[] pkt = buildUdp(p.v6, p.dst, p.src, p.dstPort, p.srcPort, payload, off, len);
        writeTun(pkt);
    }

    private void injectTcp(TcpPipe p, int flags, byte[] payload, int len) {
        byte[] pkt =
            buildTcp(p.v6, p.dst, p.src, p.dstPort, p.srcPort, p.mySeq, p.theirSeq, flags, payload, len);
        writeTun(pkt);
    }

    private void injectRst(boolean v6, byte[] src, byte[] dst, int srcPort, int dstPort, long seq) {
        byte[] pkt = buildTcp(v6, dst, src, dstPort, srcPort, 0, seq + 1, 0x14, null, 0);
        writeTun(pkt);
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
            return p;
        }
        int total = 20 + 8 + len;
        byte[] p = new byte[total];
        p[0] = 0x45;
        put16(p, 2, total);
        p[8] = 64;
        p[9] = 17;
        System.arraycopy(src, 0, p, 12, 4);
        System.arraycopy(dst, 0, p, 16, 4);
        put16(p, 10, checksum(p, 0, 20));
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
        int l = payload == null ? 0 : len;
        if (v6) {
            int total = 40 + 20 + l;
            byte[] p = new byte[total];
            p[0] = 0x60;
            put16(p, 4, 20 + l);
            p[6] = 6;
            p[7] = 64;
            System.arraycopy(src, 0, p, 8, 16);
            System.arraycopy(dst, 0, p, 24, 16);
            fillTcp(p, 40, srcPort, dstPort, seq, ack, flags, l);
            if (l > 0) System.arraycopy(payload, 0, p, 60, l);
            put16(p, 56, tcpSum6(p, src, dst, 20 + l));
            return p;
        }
        int total = 20 + 20 + l;
        byte[] p = new byte[total];
        p[0] = 0x45;
        put16(p, 2, total);
        p[8] = 64;
        p[9] = 6;
        System.arraycopy(src, 0, p, 12, 4);
        System.arraycopy(dst, 0, p, 16, 4);
        put16(p, 10, checksum(p, 0, 20));
        fillTcp(p, 20, srcPort, dstPort, seq, ack, flags, l);
        if (l > 0) System.arraycopy(payload, 0, p, 40, l);
        put16(p, 36, tcpSum4(p, 20 + l));
        return p;
    }

    private static void fillTcp(
        byte[] p, int off, int srcPort, int dstPort, long seq, long ack, int flags, int plen) {
        put16(p, off, srcPort);
        put16(p, off + 2, dstPort);
        put32(p, off + 4, seq);
        put32(p, off + 6 + 2, ack);
        p[off + 12] = (byte) 0x50;
        p[off + 13] = (byte) flags;
        put16(p, off + 14, 65535);
    }

    private static int tcpSum4(byte[] ip, int tcpLen) {
        int sum = 0;
        for (int i = 12; i < 20; i += 2) sum += u16(ip, i);
        sum += 6;
        sum += tcpLen;
        for (int i = 20; i < 20 + tcpLen - 1; i += 2) sum += u16(ip, i);
        if ((tcpLen & 1) != 0) sum += (ip[20 + tcpLen - 1] & 0xff) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        return ~sum & 0xffff;
    }

    private static int tcpSum6(byte[] pkt, byte[] src, byte[] dst, int tcpLen) {
        int sum = 0;
        for (int i = 0; i < 16; i += 2) sum += ((src[i] & 0xff) << 8) | (src[i + 1] & 0xff);
        for (int i = 0; i < 16; i += 2) sum += ((dst[i] & 0xff) << 8) | (dst[i + 1] & 0xff);
        sum += tcpLen;
        sum += 6;
        for (int i = 40; i < 40 + tcpLen - 1; i += 2) sum += u16(pkt, i);
        if ((tcpLen & 1) != 0) sum += (pkt[40 + tcpLen - 1] & 0xff) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        return ~sum & 0xffff;
    }

    private static int checksum(byte[] b, int off, int len) {
        int sum = 0;
        int i = 0;
        while (i < len - 1) {
            sum += u16(b, off + i);
            i += 2;
        }
        if (i < len) sum += (b[off + i] & 0xff) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        return ~sum & 0xffff;
    }

    private void parseDnsAnswers(byte[] d, int off, int len) {
        if (len < 12) return;
        int qd = u16(d, 4);
        int an = u16(d, 6);
        int pos = 12;
        try {
            for (int i = 0; i < qd; i++) {
                pos = skipName(d, pos, len);
                pos += 4;
            }
            for (int i = 0; i < an && pos + 10 <= len; i++) {
                pos = skipName(d, pos, len);
                int type = u16(d, pos);
                pos += 8;
                int rdlen = u16(d, pos);
                pos += 2;
                if (pos + rdlen > len) break;
                if (type == 1 && rdlen == 4) {
                    String ip =
                        (d[pos] & 0xff)
                            + "."
                            + (d[pos + 1] & 0xff)
                            + "."
                            + (d[pos + 2] & 0xff)
                            + "."
                            + (d[pos + 3] & 0xff);
                    String host = readName(d, 0, 12, len);
                    if (host != null) log.rememberDns(ip, host);
                }
                pos += rdlen;
            }
        } catch (Exception ignored) {
        }
    }

    private static String readName(byte[] d, int base, int pos, int end) {
        StringBuilder b = new StringBuilder();
        int guard = 0;
        while (pos < end && guard++ < 32) {
            int l = d[pos] & 0xff;
            if (l == 0) break;
            if ((l & 0xc0) == 0xc0) {
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
        while (pos < end && guard++ < 32) {
            int l = d[pos] & 0xff;
            if (l == 0) return pos + 1;
            if ((l & 0xc0) == 0xc0) return pos + 2;
            pos += 1 + l;
        }
        return pos;
    }

    private void evictUdp() {
        String first = udp.keySet().stream().findFirst().orElse(null);
        if (first != null) closeUdp(first);
    }

    private void evictTcp() {
        String first = tcp.keySet().stream().findFirst().orElse(null);
        if (first != null) closeTcp(first);
    }

    private void closeUdp(String key) {
        UdpPipe p = udp.remove(key);
        if (p == null) return;
        try {
            p.ch.close();
        } catch (Exception ignored) {
        }
    }

    private void closeTcp(String key) {
        TcpPipe p = tcp.remove(key);
        if (p == null) return;
        try {
            p.ch.close();
        } catch (Exception ignored) {
        }
    }

    private void closeAll() {
        for (String k : new java.util.ArrayList<>(udp.keySet())) closeUdp(k);
        for (String k : new java.util.ArrayList<>(tcp.keySet())) closeTcp(k);
        try {
            if (selector != null) selector.close();
        } catch (Exception ignored) {
        }
    }

    private static String keyOf(UdpPipe p) {
        return (p.v6 ? "6" : "4") + ":" + p.srcPort + ":" + ip(p.dst) + ":" + p.dstPort;
    }

    private static String keyOf(TcpPipe p) {
        return (p.v6 ? "6" : "4") + ":" + p.srcPort + ":" + ip(p.dst) + ":" + p.dstPort;
    }

    private static String ip(byte[] a) {
        InetAddress x = addr(a);
        return x == null ? "" : x.getHostAddress();
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
            | ((b[i + 1] & 0xff) << 16)
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

    static final class UdpPipe {
        DatagramChannel ch;
        boolean v6;
        byte[] src;
        byte[] dst;
        int srcPort;
        int dstPort;
    }

    static final class TcpPipe {
        static final int CONNECTING = 0;
        static final int ESTABLISHED = 1;
        SocketChannel ch;
        boolean v6;
        byte[] src;
        byte[] dst;
        int srcPort;
        int dstPort;
        int mySeq;
        long theirSeq;
        int status;
    }
}
