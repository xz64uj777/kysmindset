# Kysmindset

Security lock screen + live dashboard.

**PIN:** `1234`

## Android APK

GitHub Actions publishes:

https://github.com/xz64uj777/kysmindset/releases/tag/apk-latest

Download `app-debug.apk`. Play Protect may warn on a debug build.

## Device Owner (real device lock)

Android will not let an app become Device Owner after you have signed into accounts. Enroll from a computer:

1. Install the APK and open Kysmindset once.
2. Turn on USB debugging.
3. Remove Google / Samsung accounts, or factory-reset the phone first. `dpm` refuses otherwise.
4. Run:

```bash
adb shell dpm set-device-owner app.kysmindset.security/.KysDeviceAdminReceiver
```

Success looks like: `Device owner set to package app.kysmindset.security`

5. Open **Config → Device Owner**. Role should read **Device owner**.
6. Optional: turn on **Replace system keyguard**. That only works if the phone has no PIN/pattern of its own.

While the Kysmindset lock is up, lock-task mode hides Home and Recents. Unlocking with the app PIN returns the phone to normal use.

Device admin (the in-app Enable button) is weaker: it can `lockNow` but cannot replace the system keyguard.

## Run on a computer

```bash
npm install
npm run dev
```
