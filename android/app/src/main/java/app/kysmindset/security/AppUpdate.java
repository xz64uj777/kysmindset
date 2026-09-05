package app.kysmindset.security;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AppUpdate {
    public static final String RELEASE_API =
        "https://api.github.com/repos/xz64uj777/kysmindset/releases/tags/apk-latest";
    public static final String APK_URL =
        "https://github.com/xz64uj777/kysmindset/releases/download/apk-latest/Kysmindset-2.apk";

    public interface Sink {
        void emit(String json);
    }

    private AppUpdate() {}

    public static int localCode(Context ctx) {
        try {
            PackageInfo p = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return (int) p.getLongVersionCode();
            //noinspection deprecation
            return p.versionCode;
        } catch (Exception e) {
            return 1;
        }
    }

    public static String localName(Context ctx) {
        try {
            PackageInfo p = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return p.versionName != null ? p.versionName : "1.0.0";
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    public static String versionJson(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("android", true);
            o.put("code", localCode(ctx));
            o.put("name", localName(ctx));
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    public static void check(Context ctx, Sink sink) {
        new Thread(
                () -> {
                    try {
                        JSONObject rel = fetchJson(RELEASE_API);
                        int remote = parseRemoteCode(rel);
                        int local = localCode(ctx);
                        String url = apkUrl(rel);
                        JSONObject o = new JSONObject();
                        if (remote <= 0) {
                            o.put("state", "check-fail");
                            o.put("error", "Could not read the GitHub version");
                            o.put("local", local);
                            o.put("name", localName(ctx));
                            o.put("url", url);
                            sink.emit(o.toString());
                            return;
                        }
                        o.put("state", remote > local ? "available" : "current");
                        o.put("local", local);
                        o.put("remote", remote);
                        o.put("name", localName(ctx));
                        o.put("published", rel.optString("published_at", ""));
                        o.put("updated", rel.optString("updated_at", ""));
                        o.put("url", url);
                        sink.emit(o.toString());
                    } catch (Exception e) {
                        sink.emit(err("check-fail", usefulMessage(e)));
                    }
                })
            .start();
    }

    public static void install(Context ctx, Sink sink) {
        new Thread(
                () -> {
                    File apk = new File(new File(ctx.getCacheDir(), "update"), "Kysmindset-2.apk");
                    try {
                        if (apk.getParentFile() != null) apk.getParentFile().mkdirs();

                        JSONObject rel = fetchJson(RELEASE_API);
                        int remote = parseRemoteCode(rel);
                        int local = localCode(ctx);
                        String src = apkUrl(rel);

                        if (remote > 0 && remote <= local) {
                            sink.emit(err("current", "Kysmindset 2 is already up to date"));
                            return;
                        }

                        sink.emit(progress("download", 0));
                        download(src, apk, sink);
                        if (!apk.isFile() || apk.length() < 10_000) {
                            sink.emit(err("bad-apk", "Downloaded APK was empty or incomplete"));
                            return;
                        }

                        String bad = verifyApk(ctx, apk);
                        if (bad != null) {
                            //noinspection ResultOfMethodCallIgnored
                            apk.delete();
                            sink.emit(err("bad-apk", bad));
                            return;
                        }

                        sink.emit(progress("install", 100));
                        if (Build.VERSION.SDK_INT >= 26
                            && !ctx.getPackageManager().canRequestPackageInstalls()) {
                            Intent perm = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                            perm.setData(Uri.parse("package:" + ctx.getPackageName()));
                            perm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startOnMain(
                                ctx,
                                perm,
                                sink,
                                "Could not open the Install unknown apps setting");
                            sink.emit(
                                err(
                                    "need-permission",
                                    "Allow installs from Kysmindset 2, return here, then tap Update now again"));
                            return;
                        }

                        // Use Android's visible package installer directly. The previous Session.commit
                        // path could return STATUS_PENDING_USER_ACTION to MainActivity without opening
                        // the approval Intent, which made the updater appear to stop after download.
                        installView(ctx, apk, sink);
                    } catch (Exception e) {
                        sink.emit(err("install-fail", usefulMessage(e)));
                    }
                })
            .start();
    }

    /** Null if the APK is this app, newer than the installed build, and signed with the same key. */
    static String verifyApk(Context ctx, File apk) {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo incoming = archiveInfo(pm, apk);
        if (incoming == null || incoming.packageName == null) {
            return "Download is not a valid Android APK";
        }
        if (!ctx.getPackageName().equals(incoming.packageName)) {
            return "Downloaded APK is a different app (" + incoming.packageName + ")";
        }

        long incomingCode = versionCode(incoming);
        int local = localCode(ctx);
        if (incomingCode <= local) {
            return "Downloaded APK is not newer than the installed version";
        }

        Signature[] mine;
        Signature[] theirs;
        try {
            mine = signers(installedInfo(ctx, pm));
            theirs = signers(incoming);
        } catch (Exception e) {
            return "Could not verify the APK signing certificate";
        }
        if (mine.length == 0 || theirs.length == 0) {
            return "Could not verify the APK signing certificate";
        }
        if (!sameSigner(mine, theirs)) {
            return "Downloaded APK is signed with a different key, so Android cannot update this install";
        }
        return null;
    }

    private static long versionCode(PackageInfo info) {
        if (info == null) return 0;
        if (Build.VERSION.SDK_INT >= 28) return info.getLongVersionCode();
        //noinspection deprecation
        return info.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo installedInfo(Context ctx, PackageManager pm) throws Exception {
        String pkg = ctx.getPackageName();
        if (Build.VERSION.SDK_INT >= 33) {
            return pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(sigFlags()));
        }
        return pm.getPackageInfo(pkg, sigFlags());
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo archiveInfo(PackageManager pm, File apk) {
        String path = apk.getAbsolutePath();
        PackageInfo pi = archive(pm, path, sigFlags());
        if (pi == null || signers(pi).length == 0) {
            pi = archive(pm, path, PackageManager.GET_SIGNATURES);
        }
        return pi;
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo archive(PackageManager pm, String path, int flags) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags));
            }
            return pm.getPackageArchiveInfo(path, flags);
        } catch (Exception e) {
            return null;
        }
    }

    private static int sigFlags() {
        if (Build.VERSION.SDK_INT >= 28) return PackageManager.GET_SIGNING_CERTIFICATES;
        return PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signers(PackageInfo info) {
        if (info == null) return new Signature[0];
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            if (info.signingInfo.hasMultipleSigners()) {
                Signature[] s = info.signingInfo.getApkContentsSigners();
                return s != null ? s : new Signature[0];
            }
            Signature[] s = info.signingInfo.getSigningCertificateHistory();
            return s != null ? s : new Signature[0];
        }
        return info.signatures != null ? info.signatures : new Signature[0];
    }

    private static boolean sameSigner(Signature[] a, Signature[] b) {
        java.util.HashSet<Signature> set = new java.util.HashSet<>();
        for (Signature s : a) {
            if (s != null) set.add(s);
        }
        for (Signature s : b) {
            if (s != null && set.contains(s)) return true;
        }
        return false;
    }

    private static void installView(Context ctx, File apk, Sink sink) throws Exception {
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".files", apk);

        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

        if (ctx.getPackageManager().resolveActivity(install, PackageManager.MATCH_DEFAULT_ONLY) == null) {
            throw new ActivityNotFoundException("No Android package installer is available");
        }

        new Handler(Looper.getMainLooper())
            .post(
                () -> {
                    try {
                        prepareSystemHandoff(ctx);
                        ctx.startActivity(install);
                        sink.emit(progress("prompt", 100));
                    } catch (Exception e) {
                        sink.emit(err("install-fail", usefulMessage(e)));
                    }
                });
    }

    private static void startOnMain(Context ctx, Intent intent, Sink sink, String fallback) {
        new Handler(Looper.getMainLooper())
            .post(
                () -> {
                    try {
                        prepareSystemHandoff(ctx);
                        ctx.startActivity(intent);
                    } catch (Exception e) {
                        sink.emit(err("install-fail", fallback + ": " + usefulMessage(e)));
                    }
                });
    }

    /** Let Android Settings / Package Installer come to the foreground even if app pinning is on. */
    private static void prepareSystemHandoff(Context ctx) {
        MainActivity.skipNextAutoLock(ctx);
        if (ctx instanceof MainActivity) {
            try {
                ((MainActivity) ctx).stopLockTask();
            } catch (Exception ignored) {
            }
        }
    }

    private static void download(String src, File dest, Sink sink) throws Exception {
        HttpURLConnection c = open(src);
        int code = c.getResponseCode();
        int hops = 0;
        while (code >= 300 && code < 400 && hops < 8) {
            String next = c.getHeaderField("Location");
            c.disconnect();
            if (next == null || next.isEmpty()) throw new Exception("GitHub redirect was missing a target");
            c = open(next);
            code = c.getResponseCode();
            hops++;
        }
        if (code != 200) throw new Exception("Download failed: HTTP " + code);
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(dest, false)) {
            byte[] buf = new byte[64 * 1024];
            long got = 0;
            int n;
            int last = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                int pct = total > 0 ? (int) Math.min(99, (got * 100) / total) : 0;
                if (pct != last && (pct % 5 == 0 || pct >= 99)) {
                    last = pct;
                    sink.emit(progress("download", pct));
                }
            }
            out.getFD().sync();
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject fetchJson(String src) throws Exception {
        HttpURLConnection c = open(src);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("GitHub release check failed: HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                byte[] raw = readAll(in);
                return new JSONObject(new String(raw, StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String src) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(src).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(60_000);
        c.setRequestProperty("User-Agent", "Kysmindset-Updater");
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, */*");
        return c;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static int parseRemoteCode(JSONObject rel) {
        String body = rel.optString("body", "");
        String name = rel.optString("name", "");
        String tag = rel.optString("tag_name", "");
        int n = firstInt(body, "versionCode\\s*:?\\s*(\\d+)");
        if (n > 0) return n;
        n = firstInt(name + " " + tag + " " + body, "2\\.[0-9]+\\.(\\d+)");
        return n;
    }

    private static int firstInt(String text, String regex) {
        if (text == null || text.isEmpty()) return 0;
        Matcher m = Pattern.compile(regex).matcher(text);
        if (!m.find()) return 0;
        try {
            return Integer.parseInt(m.group(1));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String apkUrl(JSONObject rel) {
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) {
            // Prefer the stable public filename even if GitHub returns app-debug.apk first.
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("name", "");
                if ("Kysmindset-2.apk".equals(name)) {
                    String u = a.optString("browser_download_url", "");
                    if (!u.isEmpty()) return u;
                }
            }
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("name", "");
                if (name.endsWith(".apk")) {
                    String u = a.optString("browser_download_url", "");
                    if (!u.isEmpty()) return u;
                }
            }
        }
        return APK_URL;
    }

    private static String progress(String state, int pct) {
        JSONObject o = new JSONObject();
        try {
            o.put("state", state);
            o.put("pct", pct);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private static String err(String state, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("state", state);
            o.put("error", message == null ? "error" : message);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private static String usefulMessage(Exception e) {
        if (e == null) return "Unknown update error";
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
