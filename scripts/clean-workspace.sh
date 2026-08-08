#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

rm -rf .gradle .github/modernize .vscode android-app/build dist
find . -maxdepth 1 -type f -name "*.png" -delete
find . -name ".DS_Store" -type f -delete
find . -path ./.git -prune -o -type d -empty -delete
