# Troubleshooting

## Connected, But Black Screen

Check these in order:

1. Mac server and encoder:

   ```bash
   ps -axo pid,%cpu,%mem,etime,command | rg 'rtp-screen-server|sck-capture|ffmpeg|PID'
   ```

   Normally there should be one Python server, one `sck-capture`, and one `ffmpeg`.

2. macOS Screen Recording permission.

   Make sure the terminal or app that starts the stream is allowed in:

   ```text
   System Settings -> Privacy & Security -> Screen & System Audio Recording
   ```

3. Android client URL:

   ```text
   rtph264://0.0.0.0:5004?w=1200&h=800&fps=60
   ```

## Kill Stuck Stream Processes

```bash
pkill -f 'rtp-screen-server.py|sck-capture|ffmpeg' || true
```

## Current Good Presets

```text
Known good: 1200x800 60fps 3000kbps contain
```

## App Id

Current Android package:

```text
dev.screenvr
```
