# Kysmindset

Security lock screen + live dashboard. Real device/network posture from this app’s own traffic — no fake malware rows.

**PIN:** `1234`

## Download the Android APK

GitHub builds a debug APK on every push to `main`.

1. Open [Releases](https://github.com/xz64uj777/kysmindset/releases)
2. Download `app-debug.apk`
3. On the phone: allow install from the browser / Files
4. Play Protect may warn — expected for a debug APK you built yourself
5. Open **Kysmindset**, PIN `1234`

You can also start a build by hand: repo → **Actions** → **Build Android APK** → **Run workflow**.

## What’s in here

- Lock screen with PIN
- Kill switch (blocks this app’s outbound probes)
- Live HTTPS probes
- Android WebView APK (`android/`)

## Run the web app locally

Needs Node.js 20+.

```bash
npm install
npm run dev
```

## Honest limits

This APK wraps the app in a WebView. It can block **this app’s** fetches. It cannot list other apps or cut system-wide data without a `VpnService`.
