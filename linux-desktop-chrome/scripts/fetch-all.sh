#!/usr/bin/env bash
# Get everything the build needs that is not committed. Run this before `gradle assembleDebug`.
#   1. termux-x11 sources at a pinned commit + submodules (~200 MB of sources; builds the :lorie module)
#   2. PRoot binaries for jniLibs (sha256-pinned, ~2 MB)
#   3. Alpine minirootfs tarballs for assets (sha256-pinned, ~8 MB)
# Meant for CI. Don't run it on a nearly full disk.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "$HERE/fetch-termux-x11.sh"
bash "$HERE/fetch-proot.sh"
bash "$HERE/fetch-rootfs.sh"
echo "[fetch-all] ready"
