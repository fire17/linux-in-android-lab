#!/usr/bin/env bash
# Everything the APK needs that is too big / binary to commit. Called by .github/workflows/build.yml.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
bash "$here/fetch-scrcpy-server.sh"
bash "$here/fetch-proot.sh"
bash "$here/fetch-rootfs.sh"
echo "✓ all fetches done"
