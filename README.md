# Kysmindset

Security lock screen + live dashboard from this project.

**PIN:** `1234`

## Run on a computer

```bash
npm install
npm run dev
```

## Android APK

GitHub Actions builds the **real app** (Vite build copied into the Android WebView) and publishes:

https://github.com/xz64uj777/kysmindset/releases

Download `app-debug.apk`. Play Protect may warn on a debug build.

## What is included

- Lock screen, PIN, biometrics prompt
- Dashboard tabs (Overview, Alerts, Network, System, Honeypot, Timeline, Posture, History, Config)
- Real outbound probes + kill switch (this app's traffic)
- PWA manifest / service worker

No Grok sandbox scaffolding, no fake APK stub UI.
