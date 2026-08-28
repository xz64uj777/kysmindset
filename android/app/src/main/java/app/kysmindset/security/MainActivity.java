package app.kysmindset.security;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.VpnService;
import android.os.Bundle;
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
    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
    }

    private void sendEvent(String name, String result) {
        String js =
            "window.dispatchEvent(new CustomEvent('"
                + name
                + "',{detail:'"
                + result
                + "'}))";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private void sendBio(String result) {
        sendEvent("kys-bio", result);
    }

    private void sendKill(String result) {
        sendEvent("kys-kill", result);
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
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
