package app.kysmindset.security;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

/**
 * App lock PIN. Lives in Android encrypted prefs (Keystore), not the WebView.
 * Default 1234 until the user sets one. If encryption is unavailable, falls
 * back to private app prefs — still not page storage.
 */
public final class PinStore {
    public static final String DEFAULT = "1234";
    private static final String PREFS = "kys-pin";
    private static final String KEY = "pin";
    private static SharedPreferences cached;

    private PinStore() {}

    static synchronized SharedPreferences prefs(Context ctx) {
        if (cached != null) return cached;
        Context app = ctx.getApplicationContext();
        try {
            String alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            cached =
                EncryptedSharedPreferences.create(
                    PREFS,
                    alias,
                    app,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            cached = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        return cached;
    }

    public static boolean hasPin(Context ctx) {
        String v = prefs(ctx).getString(KEY, null);
        return v != null && v.matches("\\d{4}");
    }

    public static boolean verify(Context ctx, String pin) {
        if (pin == null || !pin.matches("\\d{4}")) return false;
        String stored = prefs(ctx).getString(KEY, null);
        if (stored == null || !stored.matches("\\d{4}")) return DEFAULT.equals(pin);
        return stored.equals(pin);
    }

    public static boolean set(Context ctx, String pin) {
        if (pin == null || !pin.matches("\\d{4}")) return false;
        prefs(ctx).edit().putString(KEY, pin).apply();
        return true;
    }

    /** Copy the old page PIN once, if native storage is still empty. */
    public static boolean setIfUnset(Context ctx, String pin) {
        if (hasPin(ctx)) return false;
        return set(ctx, pin);
    }
}
