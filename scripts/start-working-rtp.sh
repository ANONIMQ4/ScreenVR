#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh"

PHONE_IP="${PHONE_IP:-192.168.0.202}"
MAC_IP="${MAC_IP:-192.168.0.122}"
SERVER_COMMIT="${SERVER_COMMIT:-b10e97d}"
TMP_DIR="${TMP_DIR:-/tmp/screenvr-b10}"
LOG_DIR="${LOG_DIR:-/tmp/screenvr}"

mkdir -p "$TMP_DIR" "$LOG_DIR"

git -C "$ROOT" show "$SERVER_COMMIT:server/rtp-screen-server.py" > "$TMP_DIR/rtp-screen-server.py"
git -C "$ROOT" show "$SERVER_COMMIT:server/sck-capture.swift" > "$TMP_DIR/sck-capture.swift"
chmod +x "$TMP_DIR/rtp-screen-server.py"

pkill -f 'rtp-screen-server.py|mac-screen-server.py|sck-capture|ffmpeg' 2>/dev/null || true

cat > "$LOG_DIR/screen_vr_client_rtp.xml" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <float name="lens_zoom" value="105.0" />
    <int name="lens_mask" value="1" />
    <int name="raw_width" value="1200" />
    <int name="raw_bitrate" value="3000" />
    <string name="control_url">http://${MAC_IP}:8095/config</string>
    <int name="raw_height" value="800" />
    <float name="lens_strength" value="35.0" />
    <int name="lens_mode" value="2" />
    <float name="lens_size_x" value="92.0" />
    <float name="lens_size_y" value="92.0" />
    <boolean name="sbs_enabled" value="true" />
    <float name="lens_center" value="0.0" />
    <int name="eye_offset" value="0" />
    <int name="raw_fps" value="60" />
    <string name="raw_fit">contain</string>
    <string name="stream_url">rtph264://0.0.0.0:5004?w=1200&amp;h=800&amp;fps=60</string>
</map>
XML

adb shell am force-stop dev.screenvr >/dev/null 2>&1 || true
adb shell run-as dev.screenvr mkdir -p shared_prefs
adb shell run-as dev.screenvr tee shared_prefs/screen_vr_client.xml >/dev/null < "$LOG_DIR/screen_vr_client_rtp.xml"
adb shell run-as dev.screenvr chmod 660 shared_prefs/screen_vr_client.xml
adb shell am start -n dev.screenvr/.MainActivity >/dev/null

echo "Starting known-good RTP profile:"
echo "  commit: $SERVER_COMMIT"
echo "  dest:   $PHONE_IP:5004"
echo "  url:    rtph264://0.0.0.0:5004?w=1200&h=800&fps=60"
echo "  params: 1200x800 60fps 3000k queue-depth=3 screencapturekit"
echo
echo "Press Play once in the Android app if video does not appear immediately."
echo

exec python3 "$TMP_DIR/rtp-screen-server.py" \
  --dest "$PHONE_IP" \
  --size 1200x800 \
  --fps 60 \
  --bitrate 3000k \
  --queue-depth 3 \
  --capture-backend screencapturekit \
  --payload-size 1200 \
  --control-host 0.0.0.0
