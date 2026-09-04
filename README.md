# Kysmindset 2

Its own app. Not a replacement for original **Kysmindset**. Different id, so both can sit on the phone.

Lock screen and security center for **this app**. Real telemetry. Real Grok analysis. Honest limits.

**App PIN on first launch:** `1234` — that is this app’s PIN, not your phone PIN. Change it in Config. On the APK the PIN is stored on the phone, not in the web page.

## What is real

- Hosts this page actually loaded (Resource Timing)
- JS heap this page is using (when the browser exposes it)
- Protection that fails **this app’s** third-party fetches (and a device VPN on the APK if you grant it)
- PIN + fingerprint/face unlock for **this app**
- Auto-lock of this app when you leave and come back (APK and home-screen install)
- Device Admin on the APK: `lockNow` uses your **system** PIN. Home and Back always leave
- Grok 4.5 analysis of the live snapshot, only when you tap **Run Grok scan**
- Local scan with no AI call

## What this is not

- Not the original Kysmindset app (that one stays `app.kysmindset.security`)
- Not an antivirus and not a phone-wide process list
- The web build cannot cut Facebook or other apps
- This app does not replace the Android lock screen
- Device Admin cannot hide Home forever (that is Device Owner, which needs ADB)
- No SOS, Slack, fake CPU meters, fake malware, or invented decoy hits

## Android APK

Install **Kysmindset 2**. Original Kysmindset can stay installed.

1. Open [Releases](https://github.com/xz64uj777/kysmindset/releases)
2. Install `Kysmindset-2.apk`
3. Open Kysmindset 2 → Config → **Enable Device Admin** if you want Lock phone now
4. Overview → **Start Protection** asks for Android VPN if you want other apps offline

Later builds install **over** this one. Config → App update only installs an APK that is this app and this key.

If **Auto Restart** is on (default) and Protection was on, it comes back after a reboot or after an in-app update.

If an older build already trapped the phone: boot **Safe Mode** (hold Power → long-press Reboot), uninstall Kysmindset 2, or Settings → Security → Device admin apps → turn it off.

Signing: every GitHub APK uses the same personal sideload key in `android/kysmindset-upload.jks` so updates do not wipe the app. This is not a Play Store key. Do not install Kysmindset 2 APKs from anywhere except this repo.
