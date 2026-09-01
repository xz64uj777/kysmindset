package app.kysmindset.security;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Optional Device Admin helper: on screen-off, lock the phone with the
 * <b>system</b> keyguard ({@code lockNow}).
 *
 * <p>This service must never start an activity over the lock screen, never
 * turn the screen back on, and never pin the app. Doing those things trapped
 * phones so Home and the app PIN were both unreachable.
 */
public class LockGateService extends Service {
    public static final String PREFS = "kysmindset-lock";
    public static final String KEY_DEVICE_LOCK = "deviceLock";
    public static final String ACTION_START = "app.kysmindset.security.LOCK_GATE_START";
    public static final String ACTION_STOP = "app.kysmindset.security.LOCK_GATE_STOP";
    public static final String ACTION_DISABLE = "app.kysmindset.security.LOCK_GATE_DISABLE";
    private static final String CHANNEL = "kys-lock";
    private static final int NOTIF = 42;
    private static final long LOCK_DEBOUNCE_MS = 2000;

    public static volatile boolean active = false;
    private long lastLockAt = 0;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) return;
            if (!deviceLockOn(context)) return;
            if (!DeviceOwner.isAdmin(context)) return;
            long now = System.currentTimeMillis();
            if (now - lastLockAt < LOCK_DEBOUNCE_MS) return;
            lastLockAt = now;
            DeviceOwner.lockNow(context);
        }
    };

    public static boolean deviceLockOn(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
        return p.getBoolean(KEY_DEVICE_LOCK, false);
    }

    public static void setDeviceLockOn(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEVICE_LOCK, on)
            .apply();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch =
                new NotificationChannel(CHANNEL, "Phone lock", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screen, f);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action) || ACTION_DISABLE.equals(action)) {
            if (ACTION_DISABLE.equals(action)) setDeviceLockOn(this, false);
            active = false;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!deviceLockOn(this)) {
            active = false;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        active = true;
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.setAction(Intent.ACTION_MAIN);
        open.addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent pi =
            PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
        Intent disable = new Intent(this, LockGateService.class);
        disable.setAction(ACTION_DISABLE);
        PendingIntent pOff =
            PendingIntent.getService(
                this,
                1,
                disable,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
        Notification n =
            new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("Kysmindset phone lock")
                .setContentText("Screen off uses your system PIN. Home still works.")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .addAction(0, "Turn off", pOff)
                .setOngoing(true)
                .setSilent(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF, n);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(screen);
        } catch (Exception ignored) {
        }
        active = false;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
