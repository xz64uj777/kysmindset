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
    public static final String ADB =
        "adb shell dpm set-device-owner app.kysmindset.security/.KysDeviceAdminReceiver";

    private DeviceOwner() {}

    public static ComponentName admin(Context ctx) {
        return new ComponentName(ctx, KysDeviceAdminReceiver.class);
    }

    public static DevicePolicyManager dpm(Context ctx) {
        return (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
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

    public static Intent adminAddIntent(Context ctx) {
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(ctx));
        i.putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Kysmindset uses device admin to lock the phone and, once it is Device Owner, to replace the system lock screen."
        );
        return i;
    }

    public static void applyLockPolicies(Context ctx) {
        DevicePolicyManager m = dpm(ctx);
        if (m == null || !isOwner(ctx)) return;
        ComponentName a = admin(ctx);
        try {
            m.setLockTaskPackages(a, new String[] {ctx.getPackageName()});
        } catch (Exception ignored) {
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                m.setLockTaskFeatures(a, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
            } catch (Exception ignored) {
            }
        }
        try {
            m.setUninstallBlocked(a, ctx.getPackageName(), true);
        } catch (Exception ignored) {
        }
        try {
            m.setKeyguardDisabledFeatures(
                a,
                DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL
            );
        } catch (Exception ignored) {
        }
        try {
            m.setDeviceOwnerLockScreenInfo(a, "Kysmindset");
        } catch (Exception ignored) {
        }
        SharedPreferences p = ctx.getSharedPreferences(LockGateService.PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean(KEY_REPLACE_KEYGUARD, false)) {
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
        SharedPreferences p = ctx.getSharedPreferences(LockGateService.PREFS, Context.MODE_PRIVATE);
        p.edit().putBoolean(KEY_REPLACE_KEYGUARD, replaceKeyguard).apply();
        applyLockPolicies(ctx);
        boolean kg = false;
        if (isOwner(ctx)) {
            kg = tryDisableKeyguard(ctx, replaceKeyguard);
        }
        return statusJson(ctx, kg, replaceKeyguard);
    }

    @SuppressWarnings("deprecation")
    public static String remove(Context ctx) {
        DevicePolicyManager m = dpm(ctx);
        if (m != null) {
            try {
                if (isOwner(ctx)) m.clearDeviceOwnerApp(ctx.getPackageName());
            } catch (Exception ignored) {
            }
            try {
                m.removeActiveAdmin(admin(ctx));
            } catch (Exception ignored) {
            }
        }
        return statusJson(ctx);
    }

    public static String statusJson(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(LockGateService.PREFS, Context.MODE_PRIVATE);
        boolean want = p.getBoolean(KEY_REPLACE_KEYGUARD, false);
        return statusJson(ctx, false, want);
    }

    private static String statusJson(Context ctx, boolean keyguardOff, boolean wantReplace) {
        JSONObject o = new JSONObject();
        try {
            o.put("android", true);
            o.put("owner", isOwner(ctx));
            o.put("admin", isAdmin(ctx));
            o.put("lockTask", lockTaskPermitted(ctx));
            o.put("keyguardOff", keyguardOff);
            o.put("replaceKeyguard", wantReplace);
            o.put("adb", ADB);
            o.put("component", admin(ctx).flattenToString());
        } catch (Exception ignored) {
        }
        return o.toString();
    }
}
