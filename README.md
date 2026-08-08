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

## Known-Good RTP + ETS2 Head Tracking

The current best performing LAN stream uses RTP/H.264 from commit `b10e97d`
and Android Bluetooth HID head tracking from commit `607c64d`.

```bash
scripts/start-working-rtp.sh
python3 tools/ets2-bthid/apply_controls.py
```

The RTP profile is `1200x800`, `60 fps`, `3000k`, `queue-depth 3`,
`screencapturekit`, with Android URL:

```text
rtph264://0.0.0.0:5004?w=1200&h=800&fps=60
```

The ETS2 helper maps the latest S23 Bluetooth HID device in every Steam profile:

```text
trackiryaw   = joy2.x
trackirpitch = joy2.rx
trackirroll  = 0
```

More details: `docs/working-rtp-launch.md`.

## Clean Workspace

```bash
scripts/clean-workspace.sh
```

This removes Gradle output, macOS build output, diagnostics, logs, and `.DS_Store` files.
