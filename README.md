# ScreenVR

Low-latency Mac screen streaming to an Android phone for flat or SBS/Cardboard viewing.

```text
Mac ScreenCaptureKit -> ffmpeg H.264 RTP -> LAN UDP 5004 -> Android MediaCodec -> OpenGL SBS view
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

## Known-Good RTP Stream

The current best performing stream uses RTP/H.264 over LAN:

```bash
python3 server/rtp-screen-server.py \
  --dest <PHONE_LAN_IP> \
  --size 1200x800 \
  --fps 60 \
  --bitrate 3000k \
  --queue-depth 3 \
  --capture-backend screencapturekit \
  --payload-size 1200 \
  --control-host 0.0.0.0
```

Android URL:

```text
rtph264://0.0.0.0:5004?w=1200&h=800&fps=60
```

## Legacy Raw TCP Stream

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
