package app.kysmindset.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Live connection table for the Network tab. */
public final class ConnLog {
    public static final String KEY_BLOCK = "vpn_block";
    public static final String KEY_AIRGAP = "vpn_airgap";
    private static final int MAX = 400;
    private static ConnLog inst;

    public static synchronized ConnLog get(Context ctx) {
        if (inst == null) inst = new ConnLog(ctx.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final ConnectivityManager cm;
    private final PackageManager pm;
    private final LinkedHashMap<String, Row> rows = new LinkedHashMap<>();
    private final Map<String, String> dns = new HashMap<>();
    private final Set<String> allow = new HashSet<>();
    private final Set<String> block = new HashSet<>();
    private boolean airGap;
    private long bytesIn;
    private long bytesOut;
    private int drops;

    private ConnLog(Context ctx) {
        this.ctx = ctx;
        this.cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.pm = ctx.getPackageManager();
        reloadPolicy();
    }

    public synchronized void reloadPolicy() {
        SharedPreferences p = ctx.getSharedPreferences(KillVpnService.PREFS, Context.MODE_PRIVATE);
        allow.clear();
        block.clear();
        split(p.getString(KillVpnService.KEY_ALLOW, ""), allow);
        split(p.getString(KEY_BLOCK, ""), block);
        airGap = p.getBoolean(KEY_AIRGAP, false);
    }

    public static void setAirGap(Context ctx, boolean on) {
        ctx.getSharedPreferences(KillVpnService.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AIRGAP, on)
            .apply();
        get(ctx).reloadPolicy();
    }

    public static boolean airGap(Context ctx) {
        return ctx.getSharedPreferences(KillVpnService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AIRGAP, false);
    }

    public static void setBlocked(Context ctx, String pkg, boolean on) {
        SharedPreferences p = ctx.getSharedPreferences(KillVpnService.PREFS, Context.MODE_PRIVATE);
        Set<String> s = new HashSet<>();
        split(p.getString(KEY_BLOCK, ""), s);
        if (on) s.add(pkg);
        else s.remove(pkg);
        p.edit().putString(KEY_BLOCK, join(s)).apply();
        get(ctx).reloadPolicy();
    }

    public synchronized void clear() {
        rows.clear();
        bytesIn = 0;
        bytesOut = 0;
        drops = 0;
    }

    public synchronized void rememberDns(String ip, String host) {
        if (ip == null || host == null || host.isEmpty()) return;
        dns.put(ip, host);
        for (Row r : rows.values()) {
            if (ip.equals(r.ip) && (r.host == null || r.host.isEmpty())) r.host = host;
        }
    }

    public String hostOf(String ip) {
        synchronized (this) {
            return dns.get(ip);
        }
    }

    public synchronized void addBytes(int len, boolean outgoing) {
        if (outgoing) bytesOut += len;
        else bytesIn += len;
    }

    /**
     * @return true if the packet should be forwarded
     */
    public boolean onPacket(
        int proto,
        InetAddress src,
        int srcPort,
        InetAddress dst,
        int dstPort,
        int len,
        boolean outgoing) {
        if (dst == null) return true;
        String ip = dst.getHostAddress();
        if (ip == null) return true;
        int uid = lookupUid(proto, src, srcPort, dst, dstPort);
        String pkg = pkgOf(uid);
        String name = labelOf(pkg, uid);
        boolean drop = shouldDrop(pkg);
        String key = (pkg == null ? String.valueOf(uid) : pkg) + "|" + proto + "|" + ip + "|" + dstPort;
        synchronized (this) {
            if (outgoing) bytesOut += len;
            else bytesIn += len;
            if (drop) drops++;
            Row r = rows.get(key);
            if (r == null) {
                if (rows.size() >= MAX) {
                    Iterator<String> it = rows.keySet().iterator();
                    if (it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }
                r = new Row();
                r.pkg = pkg == null ? "" : pkg;
                r.name = name;
                r.proto = proto == 6 ? "TCP" : proto == 17 ? "UDP" : String.valueOf(proto);
                r.ip = ip;
                r.port = dstPort;
                r.host = dns.get(ip);
                rows.put(key, r);
            }
            r.packets++;
            r.bytes += len;
            r.lastAt = System.currentTimeMillis();
            r.blocked = drop;
            if (r.host == null) r.host = dns.get(ip);
        }
        return !drop;
    }

    public synchronized String json() {
        JSONArray arr = new JSONArray();
        List<Row> list = new ArrayList<>(rows.values());
        list.sort((a, b) -> Long.compare(b.lastAt, a.lastAt));
        try {
            for (Row r : list) {
                JSONObject o = new JSONObject();
                o.put("pkg", r.pkg);
                o.put("name", r.name);
                o.put("proto", r.proto);
                o.put("ip", r.ip);
                o.put("port", r.port);
                o.put("host", r.host == null ? "" : r.host);
                o.put("bytes", r.bytes);
                o.put("packets", r.packets);
                o.put("lastAt", r.lastAt);
                o.put("blocked", r.blocked);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        JSONObject out = new JSONObject();
        try {
            out.put("active", KillVpnService.active);
            out.put("airGap", airGap);
            out.put("bytesIn", bytesIn);
            out.put("bytesOut", bytesOut);
            out.put("drops", drops);
            out.put("rows", arr);
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    private boolean shouldDrop(String pkg) {
        if (pkg != null && block.contains(pkg)) return true;
        if (pkg != null && allow.contains(pkg)) return false;
        return airGap;
    }

    private int lookupUid(int proto, InetAddress src, int srcPort, InetAddress dst, int dstPort) {
        if (Build.VERSION.SDK_INT < 29 || cm == null || src == null || dst == null) return -1;
        try {
            return cm.getConnectionOwnerUid(
                proto, new InetSocketAddress(src, srcPort), new InetSocketAddress(dst, dstPort));
        } catch (Exception e) {
            return -1;
        }
    }

    private String pkgOf(int uid) {
        if (uid <= 0) return null;
        try {
            String[] pkgs = pm.getPackagesForUid(uid);
            if (pkgs != null && pkgs.length > 0) return pkgs[0];
        } catch (Exception ignored) {
        }
        return null;
    }

    private String labelOf(String pkg, int uid) {
        if (pkg != null) {
            try {
                CharSequence l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                if (l != null && l.length() > 0) return l.toString();
            } catch (Exception ignored) {
            }
            return pkg;
        }
        if (uid > 0) return "App " + uid;
        return "Unknown";
    }

    private static void split(String raw, Set<String> into) {
        if (raw == null) return;
        for (String p : raw.split("\n")) {
            String t = p.trim();
            if (!t.isEmpty()) into.add(t);
        }
    }

    private static String join(Set<String> s) {
        StringBuilder b = new StringBuilder();
        for (String p : s) {
            if (b.length() > 0) b.append('\n');
            b.append(p);
        }
        return b.toString();
    }

    static final class Row {
        String pkg;
        String name;
        String proto;
        String ip;
        String host;
        int port;
        int packets;
        long bytes;
        long lastAt;
        boolean blocked;
    }
}
