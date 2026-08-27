# Kysmindset

Security lock screen + live dashboard. Real device/network posture from this app’s own traffic — no fake malware rows.

**PIN:** `1234`

## What’s in here

- Lock screen with PIN
- Kill switch (blocks this app’s outbound probes)
- Live HTTPS probes, device posture, tamper log
- End connection / pause process (app-scope)

This repo is the **web / PWA** app. Expo Go native wrap is a separate folder if you add it later.

## Run locally

Needs Node.js 20+.

```bash
npm install
npm run dev
```

Then open the URL Vite prints (this project is set to port 8080).

## Honest limits

A browser/PWA cannot list other apps, kill OS processes, or cut the whole phone’s data. Kill switch here stops **this app’s** fetches. A real device-wide filter needs an Android APK with `VpnService`.

## License

Private use / your project. Add a license file if you want one.
