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
 * Optional Device Admin helper.
 *
 * <p>Screen-off: {@code lockNow} — the <b>system</b> keyguard. Never start an
 * activity over that lock screen (that trapped phones).
 *
 * <p>After the user unlocks Android ({@code USER_PRESENT}): bring Kysmindset
 * to the front for the in-app PIN. Home and Back still leave unless they pinned.
 */
public class LockGateService extends Service {
    public static final String PREFS = "kysmindset-lock";
    public static final String KEY_DEVICE_LOCK = "deviceLock";
    public static final String ACTION_START = "app.kysmindset.security.LOCK_GATE_START";
    public static final String ACTION_STOP = "app.kysmindset.security.LOCK_GATE_STOP";
    public static final String ACTION_DISABLE = "app.kysmindset.security.LOCK_GATE_DISABLE";
    public static final String ACTION_LOCK = "app.kysmindset.security.LOCK";
    private static final String CHANNEL = "kys-lock";
    private static final int NOTIF = 42;
    private static final int NOTIF_UNLOCK = 43;
    private static final long LOCK_DEBOUNCE_MS = 2000;

    public static volatile boolean active = false;
    private long lastLockAt = 0;
    private long lastOpenAt = 0;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (!deviceLockOn(context) || !DeviceOwner.isAdmin(context)) return;
            String a = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                long now = System.currentTimeMillis();
                if (now - lastLockAt < LOCK_DEBOUNCE_MS) return;
                lastLockAt = now;
                DeviceOwner.lockNow(context);
                return;
            }
            if (Intent.ACTION_USER_PRESENT.equals(a)) {
                openAfterUnlock(context);
            }
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

    static Intent lockIntent(Context ctx) {
        Intent launch = new Intent(ctx, MainActivity.class);
        launch.setAction(ACTION_LOCK);
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return launch;
    }

    private void openAfterUnlock(Context context) {
        long now = System.currentTimeMillis();
        if (now - lastOpenAt < LOCK_DEBOUNCE_MS) return;
        lastOpenAt = now;
        Intent launch = lockIntent(context);
        try {
            context.startActivity(launch);
        } catch (Exception ignored) {
        }
        // Android 10+ often blocks background activity starts. Full-screen
        // intent after USER_PRESENT (keyguard already gone) is the backup.
        PendingIntent pi =
            PendingIntent.getActivity(
                context,
                2,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n =
            new NotificationCompat.Builder(context, CHANNEL)
                .setContentTitle("Kysmindset")
                .setContentText("Unlock this app")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setAutoCancel(true)
                .setSilent(false)
                .build();
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_UNLOCK, n);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch =
                new NotificationChannel(CHANNEL, "Phone lock", NotificationManager.IMPORTANCE_HIGH);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
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
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(NOTIF_UNLOCK);
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
                .setContentText("System PIN first, then this app. Home still leaves.")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .addAction(0, "Turn off", pOff)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
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
