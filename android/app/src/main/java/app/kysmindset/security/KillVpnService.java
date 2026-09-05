package app.kysmindset.security;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

public class KillVpnService extends VpnService {
    public static final String ACTION_STOP = "app.kysmindset.security.STOP_VPN";
    public static final String ACTION_RESTART = "app.kysmindset.security.RESTART_VPN";
    public static final String PREFS = "kys";
    public static final String KEY_ALLOW = "vpn_allow";
    public static final String KEY_WANTED = "vpn_wanted";
    public static final String KEY_AUTO = "auto_restart";
    private static final String CH = "kys-airgap";
    public static volatile boolean active;
    private ParcelFileDescriptor tun;
    private volatile boolean running;
    private VpnEngine engine;

    public static boolean wanted(Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_WANTED, false);
    }

    public static void setWanted(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_WANTED, on).apply();
    }

    public static boolean autoRestart(Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO, true);
    }

    public static void setAutoRestart(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_AUTO, on).apply();
    }

    public static boolean shouldRestore(Context ctx) {
        return wanted(ctx) && autoRestart(ctx);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            setWanted(this, false);
            stopTunnel();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundNote();
        if (ACTION_RESTART.equals(action)) {
            stopTunnel();
        }
        startTunnel();
        return START_STICKY;
    }

    private void startForegroundNote() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch =
                new NotificationChannel(CH, "Protection", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi =
            PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean air = ConnLog.airGap(this);
        int nAllow = allowCount();
        String text =
            air
                ? (nAllow == 0
                    ? "Blocking other apps — watching attempts"
                    : nAllow + " app" + (nAllow == 1 ? "" : "s") + " allowed, rest blocked")
                : "Watching every connection";
        Notification.Builder b =
            Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        Notification n =
            b.setContentTitle("Kysmindset 2 Protection")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                41,
                n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(41, n);
        }
    }

    private int allowCount() {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ALLOW, "");
        if (raw == null || raw.trim().isEmpty()) return 0;
        int n = 0;
        for (String p : raw.split("\n")) {
            if (!p.trim().isEmpty()) n++;
        }
        return n;
    }

    private void startTunnel() {
        if (tun != null) return;
        Builder b = new Builder();
        b.setSession("Kysmindset 2 Protection");
        b.setMtu(1500);
        b.addAddress("10.8.0.2", 32);
        b.addRoute("0.0.0.0", 0);
        b.addDnsServer("1.1.1.1");
        b.addDnsServer("8.8.8.8");
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                b.setMetered(false);
            } catch (Exception ignored) {
            }
        }
        try {
            b.addAddress("fd00:8::2", 128);
            b.addRoute("::", 0);
        } catch (Exception ignored) {
        }
        try {
            b.addDisallowedApplication(getPackageName());
        } catch (Exception ignored) {
        }
        b.setBlocking(true);
        tun = b.establish();
        if (tun == null) return;
        running = true;
        active = true;
        setWanted(this, true);
        ConnLog log = ConnLog.get(this);
        log.reloadPolicy();
        log.clear();
        engine = new VpnEngine(this, tun, log);
        engine.start();
    }

    private void stopTunnel() {
        running = false;
        if (engine != null) {
            engine.stop();
            engine = null;
        }
        try {
            if (tun != null) tun.close();
        } catch (Exception ignored) {
        }
        tun = null;
        active = false;
        stopForeground(true);
    }

    @Override
    public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        setWanted(this, false);
        stopTunnel();
        stopSelf();
    }
}
