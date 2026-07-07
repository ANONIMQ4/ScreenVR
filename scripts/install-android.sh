#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/env.sh"
cd "$ROOT"

./gradlew :android-app:assembleDebug
adb install -r android-app/build/outputs/apk/debug/android-app-debug.apk
