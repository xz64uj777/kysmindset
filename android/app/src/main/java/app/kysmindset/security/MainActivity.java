package app.kysmindset.security;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.KeyguardManager;
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
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

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
    private boolean pageReady = false;
    private boolean lockAfterUnlock = false;
    private String pendingGate = null;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        clearOverlayFlags();
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        final WebViewAssetLoader assets =
            new WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(
            new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                    return assets.shouldInterceptRequest(req.getUrl());
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                    android.net.Uri u = req.getUrl();
                    if (u == null) return true;
                    String host = u.getHost() == null ? "" : u.getHost();
                    String scheme = u.getScheme() == null ? "" : u.getScheme();
                    if ("appassets.androidplatform.net".equals(host)) return false;
                    if ("https".equals(scheme) && (host.endsWith("googleapis.com") || host.endsWith("gstatic.com"))) {
                        return false;
                    }
                    return true;
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    pageReady = true;
                    flushGate();
                }
            });
        web.addJavascriptInterface(new KysBridge(), "KysAndroid");
        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html");
        if (isLockIntent(getIntent())) requestAppLock();
        if (LockGateService.deviceLockOn(this) && DeviceOwner.isAdmin(this)) {
            startLockGate();
        }
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher()
                .registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    (OnBackInvokedCallback) this::leaveToHome);
        }
    }

    /** Never sit on top of the Android lock screen. Does not change pinning. */
    private void clearOverlayFlags() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(false);
            setTurnScreenOn(false);
        }
        getWindow()
            .clearFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private boolean inLockTask() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        return am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    private void stopPin() {
        try {
            stopLockTask();
        } catch (Exception ignored) {
        }
    }

    private void startPin() {
        DeviceOwner.applyLockPolicies(this);
        try {
            startLockTask();
        } catch (Exception ignored) {
        }
    }

    private void maintainPin() {
        clearOverlayFlags();
        if (DeviceOwner.pinOnLock(this)) {
            if (!inLockTask()) startPin();
        } else {
            stopPin();
        }
    }

    private void recoverFromKiosk() {
        DeviceOwner.setPinOnLock(this, false);
        clearOverlayFlags();
        stopPin();
    }

    private void applyLockWindow(boolean locked) {
        clearOverlayFlags();
        if (!locked) return;
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

    private void leaveToHome() {
        if (DeviceOwner.pinOnLock(this) && inLockTask()) return;
        lockAfterUnlock = false;
        clearOverlayFlags();
        stopPin();
        moveTaskToBack(true);
    }

    private boolean isLockIntent(Intent intent) {
        return intent != null && LockGateService.ACTION_LOCK.equals(intent.getAction());
    }

    private boolean keyguardLocked() {
        KeyguardManager kg = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return kg != null && kg.isKeyguardLocked();
    }

    private void requestAppLock() {
        pendingGate = "lock";
        lockAfterUnlock = true;
        injectLockJs();
    }

    private void injectLockJs() {
        if (web == null) return;
        final String js =
            "(function(){try{sessionStorage.removeItem('kysmindset_unlocked')}catch(e){}"
                + "window.dispatchEvent(new CustomEvent('kys-gate',{detail:'lock'}));})()";
        Runnable fire = () -> {
            if (web != null && pageReady) web.evaluateJavascript(js, null);
        };
        web.post(fire);
        web.postDelayed(fire, 400);
        web.postDelayed(fire, 1200);
    }

    private void flushGate() {
        if (pendingGate == null) return;
        pendingGate = null;
        injectLockJs();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maintainPin();
        if (isLockIntent(intent)) requestAppLock();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        maintainPin();
        if (lockAfterUnlock && !keyguardLocked()) {
            lockAfterUnlock = false;
            requestAppLock();
        }
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
        maintainPin();
        if (requestCode == ADMIN_REQ) {
            if (DeviceOwner.isAdmin(this) && LockGateService.deviceLockOn(this)) {
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
        public String getAllowlist() {
            SharedPreferences prefs = getSharedPreferences(KillVpnService.PREFS, MODE_PRIVATE);
            String raw = prefs.getString(KillVpnService.KEY_ALLOW, "");
            JSONArray arr = new JSONArray();
            if (raw != null) {
                for (String pkg : raw.split("\n")) {
                    String p = pkg.trim();
                    if (!p.isEmpty()) arr.put(p);
                }
            }
            return arr.toString();
        }

        @JavascriptInterface
        public boolean killActive() {
            return KillVpnService.active;
        }

        @JavascriptInterface
        public void setAutoRestart(boolean on) {
            KillVpnService.setAutoRestart(MainActivity.this, on);
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
        public void goHome() {
            runOnUiThread(MainActivity.this::leaveToHome);
        }

        @JavascriptInterface
        public void setDeviceLock(boolean on) {
            LockGateService.setDeviceLockOn(MainActivity.this, on);
            runOnUiThread(
                () -> {
                    maintainPin();
                    if (on && DeviceOwner.isAdmin(MainActivity.this)) startLockGate();
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
            String json = DeviceOwner.applyPin(MainActivity.this, on);
            runOnUiThread(MainActivity.this::maintainPin);
            return json;
        }

        @JavascriptInterface
        public void pinNow() {
            runOnUiThread(
                () -> {
                    DeviceOwner.setPinOnLock(MainActivity.this, true);
                    startPin();
                });
        }

        @JavascriptInterface
        public void lockNow() {
            runOnUiThread(
                () -> {
                    lockAfterUnlock = true;
                    injectLockJs();
                    if (LockGateService.deviceLockOn(MainActivity.this)
                        && DeviceOwner.isAdmin(MainActivity.this)) {
                        startLockGate();
                    }
                    DeviceOwner.lockNow(MainActivity.this);
                });
        }

        @JavascriptInterface
        public String removeAdmin() {
            DeviceOwner.setPinOnLock(MainActivity.this, false);
            LockGateService.setDeviceLockOn(MainActivity.this, false);
            runOnUiThread(
                () -> {
                    recoverFromKiosk();
                    stopLockGate();
                });
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
        public boolean verifyPin(String pin) {
            return PinStore.verify(MainActivity.this, pin);
        }

        @JavascriptInterface
        public boolean setPin(String pin) {
            return PinStore.set(MainActivity.this, pin);
        }

        @JavascriptInterface
        public boolean setPinIfUnset(String pin) {
            return PinStore.setIfUnset(MainActivity.this, pin);
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
                            .setTitle("Unlock Kysmindset 2")
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
        leaveToHome();
    }
}
