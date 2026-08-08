# Working RTP Launch

Known-good performance point:

- Android HID/head tracking commit: `607c64d`
- RTP streaming server commit: `b10e97d`
- Transport: RTP/H.264 over LAN
- Phone IP: `192.168.0.202`
- Mac LAN IP: `192.168.0.122`
- Android stream URL: `rtph264://0.0.0.0:5004?w=1200&h=800&fps=60`
- Server size/FPS/bitrate: `1200x800`, `60 fps`, `3000k`
- Capture backend: `screencapturekit`
- Queue depth: `3`
- Payload size: `1200`
- Control endpoint: `http://192.168.0.122:8095/config`

Do not use `adb reverse` for this mode. Do not start `rawh264://...` when chasing the known-good performance point.

Head tracking and streaming come from two different known-good points:

- `607c64d` is the working Android Bluetooth HID head tracker for ETS2.
- `b10e97d` is the working RTP streaming performance point.

## Start

```bash
scripts/start-working-rtp.sh
```

The script:

1. Extracts `server/rtp-screen-server.py` and `server/sck-capture.swift` from `b10e97d` into `/tmp/screenvr-b10`.
2. Starts RTP to the phone at `192.168.0.202:5004`.
3. Writes Android app prefs so the client opens `rtph264://0.0.0.0:5004?w=1200&h=800&fps=60`.
4. Starts `dev.screenvr/.MainActivity`.

After the app opens, press `Play` once if the image does not appear immediately.

Expected overlay when healthy is roughly:

```text
gl 56-58 read 57-59 in 57-59 out 56-58 drop 0-2 lag ~10ms q 0
```

## Manual Server Command

```bash
python3 /tmp/screenvr-b10/rtp-screen-server.py \
  --dest 192.168.0.202 \
  --size 1200x800 \
  --fps 60 \
  --bitrate 3000k \
  --queue-depth 3 \
  --capture-backend screencapturekit \
  --payload-size 1200 \
  --control-host 0.0.0.0
```

## Notes

- If the phone shows `rawh264://...`, the RTP server can be perfect and the app will still show no image.
- `adb input text` can fail to replace the URL reliably. The script updates `SharedPreferences` through `run-as ... tee`.
- If the phone IP changes, update `PHONE_IP` in `scripts/start-working-rtp.sh`.
