#!/usr/bin/env bash
# Populate every binary the APK needs (run before ./gradlew assembleDebug). ~10 MB total.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "$HERE/fetch-proot.sh"
bash "$HERE/fetch-rootfs.sh"
echo "[fetch-all] payload ready"
