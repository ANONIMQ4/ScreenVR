# ScreenVR

Low-latency Mac screen streaming to an Android phone for flat or SBS/Cardboard viewing.

```text
Mac screen -> ffmpeg raw H.264 -> TCP 8094 -> adb reverse -> Android MediaCodec -> OpenGL SBS view
```

## Structure

```text
android-app/ Android client app
macos/       macOS launcher app
server/      Mac screen streaming server
scripts/     build, install, and cleanup helpers
docs/        troubleshooting notes
```

## Requirements

- macOS
- Android phone with USB debugging enabled
- Android SDK with `adb`
- JDK 21
- Homebrew `ffmpeg`
- macOS Screen Recording permission for `ScreenVR Launcher.app`

## Build Android

```bash
scripts/build-android.sh
```

Install to the connected phone:

```bash
scripts/install-android.sh
```

Android package:

```text
dev.screenvr
```

## Build Mac Launcher

```bash
scripts/build-mac-app.sh
```

The app is created at:

```text
macos/build/ScreenVR Launcher.app
```

Open the app, allow Screen Recording in macOS Privacy settings, then restart it. The launcher starts the stream automatically and exposes presets plus width, height, FPS, bitrate, and fit controls.

## Manual Stream

Useful when debugging without the launcher:

```bash
adb reverse tcp:8094 tcp:8094
adb reverse tcp:8095 tcp:8095
server/mac-screen-server.py --fps 30 --size 1170x1080 --bitrate 5000k --fit contain
```

Android URL:

```text
rawh264://127.0.0.1:8094?w=1170&h=1080
```

## Clean Workspace

```bash
scripts/clean-workspace.sh
```

This removes Gradle output, macOS build output, diagnostics, logs, and `.DS_Store` files.
