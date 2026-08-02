# Building, installing, and finding out what went wrong

## Build

JDK 21 (Android Studio's JBR), Gradle 8.13, AGP 8.12.1, compileSdk 34, **targetSdk 29**
(with `requestLegacyExternalStorage` — the combination proven to give direct file access on
this firmware; a sideloaded app has no Play Store targeting requirement).

```bash
cd player && ./gradlew assembleDebug
```

`player/local.properties` must point at the SDK or Gradle stops with "SDK location not
found". It is git-ignored, so a fresh clone needs it written once.

A skin must be present at `player/app/src/main/assets/skins/*.wsz` — see `skinning.md`.

## Install

**Bump `versionCode` in `player/app/build.gradle` first.** This is not optional: with an
unchanged versionCode the device's installer reports success and quietly keeps the app it
already has, which looks exactly like new code doing nothing. It cost a whole round trip
once.

There is no ADB, so builds go on through the device's own web installer:

1. Open `http://<device>:18888` in a **real desktop browser**.
2. Choose the APK. It uploads and installs.

Driving that upload from `curl` does not work — four approaches were tried and the server
returns its index page every time. A USB stick and the device's File app is the fallback.

With nothing playing, the main window's title strip shows the running version. That is the
one-glance check that the install took.

## Debugging without a debugger

There is no logcat. The app carries its own:

* An in-memory ring buffer (`:core/Logs`).
* An on-screen console — **long-press the main window's title bar**.
* An HTTP log shipper to a dev machine (`MainActivity.DEV_HOST`).
* A crash handler that survives to the next launch.

Before reaching for any of that, remember the device answers HTTP directly and has no
authentication: `curl http://<device>:9529/ZidooMusicControl/v2/getState` beats asking the
owner to read a screen. See `api.md`.

And before reaching for the device at all, see `testing.md` — most things can be proved on
the laptop.
