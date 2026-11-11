<p align="center"><img src="docs/media/wend-logo.svg" width="96" height="96" alt="Wend logo"></p>

<h1 align="center">Wend</h1>

<p align="center">A launcher for Android 10 car head units, built for the PX6.</p>

https://github.com/user-attachments/assets/1cf1a694-c676-4b99-805c-ce1088c85269

## Features

- **Live Google Maps in the home screen.** The real Maps app, not a map SDK, keeps its task and route while you switch apps. Tap the dock to open it fullscreen; Home shrinks it back into its panel.
- **Music controls** for whichever app is playing, with a music app of your choice (YouTube Music by default) for the panel's open button.
- **Floating windows.** Open a video fullscreen while navigating and Maps floats above it with its next-turn card; open Maps and a playing YouTube video floats above it. Drag a window to any corner, tap it for play/pause, fullscreen and close, double-tap to swap, or drag it off the bottom to close it.
- **Resumes guidance** if Maps is killed mid-trip.
- **App drawer** with instant search, and a **dock** of up to five apps.
- **Settings sheet** for the map side and width, dock apps, music app and floating windows.

## Install on the PX6

Wend uses platform APIs, so it must be signed with the head unit's platform key.

```sh
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :app:assembleRelease
scripts/sign-px6.py app/build/outputs/apk/release/app-release-unsigned.apk app/build/outputs/apk/release/wend-px6.apk
adb install app/build/outputs/apk/release/wend-px6.apk
```

Then press Home, pick Wend and tap Always. Requires JDK 17+ and Android SDK platform 35. `sign-px6.py` is pinned to the PX6's public Rockchip development certificate; for the AOSP Android 10 emulator pass `--target aosp29`.

## Optional: Maps bridge

[`maps-bridge`](maps-bridge/README.md) is an LSPosed module that runs inside Google Maps and hands Wend its turn-by-turn guidance and destination. Install it on a rooted unit with Maps' notifications turned off, or to resume trips Maps itself won't restore. Wend works without it.

## Docs

- [Floating windows](docs/floating-windows.md): how the windows behave and how they were validated
- [Launcher UI](docs/launcher-ui.md): app drawer, dock and settings
- [YouTube](docs/youtube-window-transitions.md) and [Maps](docs/maps-window-transitions.md) window transitions
- [Maps recovery](docs/maps-recovery.md) and the [development log](docs/development-log.md)
- [Offline development](task-fixture/README.md) with fixture apps on the emulator

## Status

Developed on Android 10 emulators with real Google Maps, YouTube and YouTube Music, and running on a PX6 since November 2026. Demo video: Big Buck Bunny, Blender Foundation, CC BY 3.0.
