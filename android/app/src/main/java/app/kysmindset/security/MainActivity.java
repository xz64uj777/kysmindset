package app.kysmindset.security;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.util.concurrent.Executor;

public class MainActivity extends FragmentActivity {
    private static final int VPN_REQ = 91;
    private static final int ADMIN_REQ = 92;
    private WebView web;
    private boolean gated = true;

    public static void requestLock(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setAction("app.kysmindset.security.LOCK");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        ctx.startActivity(i);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DeviceOwner.applyLockPolicies(this);
        applyLockWindow(true);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        try {
            WebSettings.class
                .getMethod("setAllowUniversalAccessFromFileURLs", boolean.class)
                .invoke(s, true);
        } catch (Exception ignored) {
        }
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new KysBridge(), "KysAndroid");
        web.loadUrl("file:///android_asset/www/index.html");
        startLockGate();
        handleLockIntent(getIntent());
    }

    @SuppressWarnings("deprecation")
    private void applyLockWindow(boolean on) {
        gated = on;
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on);
            setTurnScreenOn(on);
        }
        if (on) {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
            hideSystemBars();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            showSystemBars();
        }
        applyLockTask(on);
    }

    private void applyLockTask(boolean on) {
        DeviceOwner.applyLockPolicies(this);
        try {
            if (on && canPin()) startLockTask();
            else if (!on) stopLockTask();
        } catch (Exception ignored) {
        }
    }

    private boolean canPin() {
        if (DeviceOwner.isOwner(this) && DeviceOwner.lockTaskPermitted(this)) return true;
        return DeviceOwner.pinOnLock(this);
    }

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private void showSystemBars() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void startLockGate() {
        if (!LockGateService.deviceLockOn(this)) return;
        Intent i = new Intent(this, LockGateService.class);
        i.setAction(LockGateService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void stopLockGate() {
        Intent i = new Intent(this, LockGateService.class);
        i.setAction(LockGateService.ACTION_STOP);
        startService(i);
    }

    private void handleLockIntent(Intent intent) {
        if (intent == null) return;
        if ("app.kysmindset.security.LOCK".equals(intent.getAction())) {
            applyLockWindow(true);
            sendEvent("kys-gate", "lock");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLockIntent(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (LockGateService.deviceLockOn(this) && !gated) {
            gated = true;
            sendEvent("kys-gate", "lock");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gated) hideSystemBars();
        DeviceOwner.applyLockPolicies(this);
    }

    private void sendEvent(String name, String result) {
        if (web == null) return;
        String safe = result == null ? "" : result.replace("\\", "\\\\").replace("'", "\\'");
        String js =
            "window.dispatchEvent(new CustomEvent('"
                + name
                + "',{detail:'"
                + safe
                + "'}))";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private void sendBio(String result) {
        sendEvent("kys-bio", result);
    }

    private void sendKill(String result) {
        sendEvent("kys-kill", result);
    }

    private void sendUpdate(String json) {
        sendEvent("kys-update", json);
    }

    private void startVpn() {
        Intent i = new Intent(this, KillVpnService.class);
        startService(i);
        sendKill("on");
    }

    private void stopVpn() {
        Intent i = new Intent(this, KillVpnService.class);
        i.setAction(KillVpnService.ACTION_STOP);
        startService(i);
        sendKill("off");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ADMIN_REQ) {
            if (DeviceOwner.isAdmin(this)) {
                DeviceOwner.applyLockPolicies(this);
                startLockGate();
            }
            return;
        }
        if (requestCode != VPN_REQ) return;
        if (resultCode == RESULT_OK) startVpn();
        else sendKill("denied");
    }

    private void saveAllowlist(String json) {
        StringBuilder lines = new StringBuilder();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String pkg = arr.optString(i, "").trim();
                if (pkg.isEmpty()) continue;
                if (lines.length() > 0) lines.append('\n');
                lines.append(pkg);
            }
        } catch (Exception ignored) {
        }
        SharedPreferences prefs = getSharedPreferences(KillVpnService.PREFS, MODE_PRIVATE);
        prefs.edit().putString(KillVpnService.KEY_ALLOW, lines.toString()).apply();
    }

    private void restartVpnIfRunning() {
        if (!KillVpnService.active) return;
        Intent i = new Intent(this, KillVpnService.class);
        i.setAction(KillVpnService.ACTION_RESTART);
        startService(i);
    }

    public class KysBridge {
        @JavascriptInterface
        public String listApps() {
            JSONArray out = new JSONArray();
            try {
                PackageManager pm = getPackageManager();
                Intent launch = new Intent(Intent.ACTION_MAIN);
                launch.addCategory(Intent.CATEGORY_LAUNCHER);
                java.util.List<ResolveInfo> infos =
                    pm.queryIntentActivities(launch, PackageManager.MATCH_ALL);
                java.util.HashSet<String> seen = new java.util.HashSet<>();
                for (ResolveInfo info : infos) {
                    if (info.activityInfo == null) continue;
                    String pkg = info.activityInfo.packageName;
                    if (pkg == null || !seen.add(pkg)) continue;
                    if (pkg.equals(getPackageName())) continue;
                    JSONObject row = new JSONObject();
                    CharSequence label = info.loadLabel(pm);
                    row.put("pkg", pkg);
                    row.put("name", label != null ? label.toString() : pkg);
                    out.put(row);
                }
            } catch (Exception ignored) {
            }
            return out.toString();
        }

        @JavascriptInterface
        public void setAllowlist(String json) {
            saveAllowlist(json == null ? "[]" : json);
            runOnUiThread(MainActivity.this::restartVpnIfRunning);
        }

        @JavascriptInterface
        public void setKill(boolean on) {
            runOnUiThread(
                () -> {
                    if (!on) {
                        stopVpn();
                        return;
                    }
                    Intent prep = VpnService.prepare(MainActivity.this);
                    if (prep != null) {
                        startActivityForResult(prep, VPN_REQ);
                    } else {
                        startVpn();
                    }
                });
        }

        @JavascriptInterface
        public void setGate(boolean locked) {
            runOnUiThread(() -> applyLockWindow(locked));
        }

        @JavascriptInterface
        public void setDeviceLock(boolean on) {
            SharedPreferences prefs = getSharedPreferences(LockGateService.PREFS, MODE_PRIVATE);
            prefs.edit().putBoolean(LockGateService.KEY_DEVICE_LOCK, on).apply();
            runOnUiThread(
                () -> {
                    if (on) startLockGate();
                    else stopLockGate();
                });
        }

        @JavascriptInterface
        public String ownerStatus() {
            return DeviceOwner.statusJson(MainActivity.this);
        }

        @JavascriptInterface
        public void requestAdmin() {
            runOnUiThread(
                () -> startActivityForResult(DeviceOwner.adminAddIntent(MainActivity.this), ADMIN_REQ));
        }

        @JavascriptInterface
        public String applyOwner(boolean replaceKeyguard) {
            return DeviceOwner.apply(MainActivity.this, replaceKeyguard);
        }

        @JavascriptInterface
        public String applyPin(boolean on) {
            return DeviceOwner.applyPin(MainActivity.this, on);
        }

        @JavascriptInterface
        public void pinNow() {
            runOnUiThread(
                () -> {
                    DeviceOwner.setPinOnLock(MainActivity.this, true);
                    try {
                        startLockTask();
                    } catch (Exception ignored) {
                    }
                });
        }

        @JavascriptInterface
        public void lockNow() {
            runOnUiThread(() -> DeviceOwner.lockNow(MainActivity.this));
        }

        @JavascriptInterface
        public String removeAdmin() {
            return DeviceOwner.remove(MainActivity.this);
        }

        @JavascriptInterface
        public String appVersion() {
            return AppUpdate.versionJson(MainActivity.this);
        }

        @JavascriptInterface
        public void checkUpdate() {
            AppUpdate.check(MainActivity.this, MainActivity.this::sendUpdate);
        }

        @JavascriptInterface
        public void startUpdate() {
            AppUpdate.install(MainActivity.this, MainActivity.this::sendUpdate);
        }

        @JavascriptInterface
        public void biometric() {
            runOnUiThread(
                () -> {
                    int authenticators =
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
                            | BiometricManager.Authenticators.BIOMETRIC_WEAK;
                    int status =
                        BiometricManager.from(MainActivity.this).canAuthenticate(authenticators);
                    if (status != BiometricManager.BIOMETRIC_SUCCESS) {
                        sendBio("unavailable");
                        return;
                    }
                    Executor ex = ContextCompat.getMainExecutor(MainActivity.this);
                    BiometricPrompt prompt =
                        new BiometricPrompt(
                            MainActivity.this,
                            ex,
                            new BiometricPrompt.AuthenticationCallback() {
                                @Override
                                public void onAuthenticationSucceeded(
                                    @NonNull BiometricPrompt.AuthenticationResult result) {
                                    sendBio("ok");
                                }

                                @Override
                                public void onAuthenticationError(
                                    int errorCode, @NonNull CharSequence errString) {
                                    if (errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS
                                        || errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT
                                        || errorCode == BiometricPrompt.ERROR_HW_UNAVAILABLE) {
                                        sendBio("unavailable");
                                    } else {
                                        sendBio("fail");
                                    }
                                }
                            });
                    BiometricPrompt.PromptInfo info =
                        new BiometricPrompt.PromptInfo.Builder()
                            .setTitle("Unlock Kysmindset")
                            .setSubtitle("Verify fingerprint or face")
                            .setNegativeButtonText("Cancel")
                            .setAllowedAuthenticators(authenticators)
                            .build();
                    prompt.authenticate(info);
                });
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (gated) return;
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
