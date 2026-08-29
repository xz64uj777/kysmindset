# Kysmindset

Security lock screen + live dashboard.

**PIN:** `1234`

## Android APK

GitHub Actions publishes:

https://github.com/xz64uj777/kysmindset/releases/tag/apk-latest

Download `app-debug.apk`. Play Protect may warn on a debug build.

## Device lock (what most people should use)

You do **not** need Device Owner, ADB, or a factory reset.

1. Install the APK and open **Config → Device lock**.
2. Tap **Enable device admin** and allow it on the Android screen.
3. Leave **Device lock screen** on in Config.

What that does:

- Screen off → the phone locks (`lockNow`).
- Screen on → Kysmindset comes up over the keyguard.
- Optional **Pin app while locked** uses Android screen pinning so Home is blocked until you unpin.

This is close to a device lock. It is not the same as Device Owner: Android will not let a normal app hide Home permanently or delete the system PIN.

## Device Owner (optional / advanced)

Only if you can factory-reset or you have a phone with no accounts:

```bash
adb shell dpm set-device-owner app.kysmindset.security/.KysDeviceAdminReceiver
```

That can hide Home/Recents and, if the phone has no PIN of its own, replace the system keyguard.

## Run on a computer

```bash
npm install
npm run dev
```
