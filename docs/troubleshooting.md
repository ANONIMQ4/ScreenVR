# Troubleshooting

## Connected, But Black Screen

Check these in order:

1. USB reverse ports:

   ```bash
   adb reverse --list
   ```

   Expected:

   ```text
   tcp:8094 tcp:8094
   tcp:8095 tcp:8095
   ```

2. Mac server and encoder:

   ```bash
   ps -axo pid,%cpu,%mem,etime,command | rg 'mac-screen-server|ffmpeg|PID'
   ```

   Normally there should be one Python server and one `ffmpeg`.

3. macOS Screen Recording permission.

   If the launcher was rebuilt, macOS may treat it as a new app. Remove and re-add `ScreenVR Launcher.app` in:

   ```text
   System Settings -> Privacy & Security -> Screen & System Audio Recording
   ```

4. Android client URL:

   ```text
   rawh264://127.0.0.1:8094?w=1170&h=1080
   ```

## Kill Stuck Stream Processes

```bash
pkill -f mac-screen-server.py || true
ps -axo pid=,command= | awk '/ffmpeg/ && /-f h264/ && /pipe:1/ {print $1}' | xargs -r kill -9
```

## Current Good Presets

```text
Native:   1170x1080 30fps 5000kbps contain
Balanced: 960x540   30fps 2500kbps contain
Fast:     800x450   30fps 1600kbps contain
```

## App Id

Current Android package:

```text
dev.screenvr
```
