package app.kysmindset.security;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

public final class DeviceOwner {
    public static final String KEY_REPLACE_KEYGUARD = "replaceKeyguard";
    public static final String KEY_PIN_ON_LOCK = "pinOnLock";
    public static final String ADB =
        "adb shell dpm set-device-owner app.kysmindset.security/.KysDeviceAdminReceiver";

    private DeviceOwner() {}

    public static ComponentName admin(Context ctx) {
        return new ComponentName(ctx, KysDeviceAdminReceiver.class);
    }

    public static DevicePolicyManager dpm(Context ctx) {
        return (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(LockGateService.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isOwner(Context ctx) {
        DevicePolicyManager m = dpm(ctx);
        return m != null && m.isDeviceOwnerApp(ctx.getPackageName());
    }

    public static boolean isAdmin(Context ctx) {
        DevicePolicyManager m = dpm(ctx);
        return m != null && m.isAdminActive(admin(ctx));
    }

    public static boolean lockTaskPermitted(Context ctx) {
        DevicePolicyManager m = dpm(ctx);
        return m != null && m.isLockTaskPermitted(ctx.getPackageName());
    }

    public static boolean pinOnLock(Context ctx) {
        return prefs(ctx).getBoolean(KEY_PIN_ON_LOCK, false);
    }

    public static void setPinOnLock(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_PIN_ON_LOCK, on).apply();
    }

    public static Intent adminAddIntent(Context ctx) {
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(ctx));
        i.putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Allows Kysmindset to lock the phone with your system PIN when you ask it to. It will not cover the Android lock screen or hide Home."
        );
        return i;
    }

    public static void applyLockPolicies(Context ctx) {
        DevicePolicyManager m = dpm(ctx);
        if (m == null) return;
        ComponentName a = admin(ctx);
        if (!isOwner(ctx)) return;
        try {
            m.setLockTaskPackages(a, new String[] {ctx.getPackageName()});
        } catch (Exception ignored) {
        }
        // Keep Recents, notifications, keyguard, and power menu.
        // Home is blocked only while the user opted into pin. FEATURE_NONE trapped phones.
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                int features =
                    DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW
                        | DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                        | DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
                        | DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS;
                if (!pinOnLock(ctx)) {
                    features |= DevicePolicyManager.LOCK_TASK_FEATURE_HOME;
                }
                m.setLockTaskFeatures(a, features);
            } catch (Exception ignored) {
            }
        }
        try {
            m.setDeviceOwnerLockScreenInfo(a, "Kysmindset");
        } catch (Exception ignored) {
        }
        if (prefs(ctx).getBoolean(KEY_REPLACE_KEYGUARD, false)) {
            tryDisableKeyguard(ctx, true);
        }
    }

    public static boolean tryDisableKeyguard(Context ctx, boolean disabled) {
        DevicePolicyManager m = dpm(ctx);
        if (m == null || !isOwner(ctx)) return false;
        try {
            return m.setKeyguardDisabled(admin(ctx), disabled);
        } catch (Exception e) {
            return false;
        }
    }

    public static void lockNow(Context ctx) {
        DevicePolicyManager m = dpm(ctx);
        if (m == null || !isAdmin(ctx)) return;
        try {
            m.lockNow();
        } catch (Exception ignored) {
        }
    }

    public static String apply(Context ctx, boolean replaceKeyguard) {
        prefs(ctx).edit().putBoolean(KEY_REPLACE_KEYGUARD, replaceKeyguard).apply();
        applyLockPolicies(ctx);
        boolean kg = false;
        if (isOwner(ctx)) {
            kg = tryDisableKeyguard(ctx, replaceKeyguard);
        }
        return statusJson(ctx, kg, replaceKeyguard);
    }

    public static String applyPin(Context ctx, boolean on) {
        setPinOnLock(ctx, on);
        applyLockPolicies(ctx);
        return statusJson(ctx);
    }

    @SuppressWarnings("deprecation")
    public static String remove(Context ctx) {
        DevicePolicyManager m = dpm(ctx);
        if (m != null) {
            try {
                if (isOwner(ctx)) {
                    tryDisableKeyguard(ctx, false);
                    m.clearDeviceOwnerApp(ctx.getPackageName());
                }
            } catch (Exception ignored) {
            }
            try {
                m.removeActiveAdmin(admin(ctx));
            } catch (Exception ignored) {
            }
        }
        setPinOnLock(ctx, false);
        LockGateService.setDeviceLockOn(ctx, false);
        return statusJson(ctx);
    }

    public static String statusJson(Context ctx) {
        boolean want = prefs(ctx).getBoolean(KEY_REPLACE_KEYGUARD, false);
        return statusJson(ctx, false, want);
    }

    private static String statusJson(Context ctx, boolean keyguardOff, boolean wantReplace) {
        JSONObject o = new JSONObject();
        try {
            o.put("android", true);
            o.put("owner", isOwner(ctx));
            o.put("admin", isAdmin(ctx));
            o.put("lockTask", lockTaskPermitted(ctx));
            o.put("pinOnLock", pinOnLock(ctx));
            o.put("keyguardOff", keyguardOff);
            o.put("replaceKeyguard", wantReplace);
            o.put("adb", ADB);
            o.put("component", admin(ctx).flattenToString());
        } catch (Exception ignored) {
        }
        return o.toString();
    }
}
