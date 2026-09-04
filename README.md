# Kysmindset

Lock screen and security center for **this origin**. Real telemetry. Real Grok analysis. Honest limits.

**App PIN on first launch:** `1234` — that is this app’s PIN, not your phone PIN. Change it in Config.

## What is real

- Hosts this page actually loaded (Resource Timing)
- JS heap this page is using (when the browser exposes it)
- Kill that fails **this app’s** third-party fetches (and a device VPN on the APK if you grant it)
- PIN + fingerprint/face unlock for **this app**
- Auto-lock of this app when the installed PWA is hidden
- Device Admin on the APK: `lockNow` uses your **system** PIN. Home and Back always leave
- Grok 4.5 analysis of the live snapshot, only when you tap **Run Grok scan**
- Local scan with no AI call

## What this is not

- Not an antivirus and not a phone-wide process list
- The web build cannot cut Facebook or other apps
- This app does not replace the Android lock screen
- Device Admin cannot hide Home forever (that is Device Owner, which needs ADB)
- No SOS, Slack, fake CPU meters, fake malware, or invented decoy hits
- Decoy paths only intercept when the service worker is controlling (installed PWA)

## Android APK

Install a **2.1.1+** APK. 2.0.x could overlay the keyguard and trap the phone — that is fixed.

1. Open [Releases](https://github.com/xz64uj777/kysmindset/releases)
2. Install `app-debug.apk`
3. Open Kysmindset → Config → **Enable Device Admin** if you want Lock phone now
4. Kill asks for Android VPN if you want other apps offline

If an older build already trapped the phone: boot **Safe Mode** (hold Power → long-press Reboot), uninstall Kysmindset, or Settings → Security → Device admin apps → turn it off.

2.1.1 hygiene: backup off, cleartext off, WebView locked to bundled assets, debug keystore no longer in the repo. A new APK may not install *over* an old one — uninstall the old app first, then install. Device Owner must be enrolled again after that.
