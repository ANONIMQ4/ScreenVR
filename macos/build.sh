#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_DIR="$ROOT/macos/build/ScreenVR Launcher.app"
MACOS_DIR="$APP_DIR/Contents/MacOS"
RESOURCES_DIR="$APP_DIR/Contents/Resources"

rm -rf "$APP_DIR"
mkdir -p "$MACOS_DIR" "$RESOURCES_DIR"

swiftc "$ROOT/macos/ScreenVRLauncher.swift" \
  -framework AppKit \
  -o "$MACOS_DIR/ScreenVR Launcher"

cp "$ROOT/server/mac-screen-server.py" "$RESOURCES_DIR/mac-screen-server.py"
chmod +x "$RESOURCES_DIR/mac-screen-server.py"

cat > "$APP_DIR/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key>
  <string>ScreenVR Launcher</string>
  <key>CFBundleIdentifier</key>
  <string>dev.screenvr.launcher</string>
  <key>CFBundleName</key>
  <string>ScreenVR Launcher</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>0.1.0</string>
  <key>LSMinimumSystemVersion</key>
  <string>12.0</string>
  <key>NSHighResolutionCapable</key>
  <true/>
</dict>
</plist>
PLIST

echo "$APP_DIR"
