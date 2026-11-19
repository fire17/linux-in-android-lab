#!/usr/bin/env bash
# Builds the Android userland tarball for OnDevice rungs A3-A6, from a redroid image.
# Run on a Linux (or macOS + Docker/OrbStack) machine with ~3 GB free — NOT in CI, NOT in the APK.
#
#   ./scripts/make-android-userland.sh [image] [out.tar.gz]
#   default image: redroid/redroid:16.0.0_64only-latest   (arm64 layer ≈ 734 MB compressed)
#
# What it does: `docker create` (never runs it — no binder modules needed), `docker export` the
# filesystem, keeps /system /vendor /apex /product /system_ext /odm /linkerconfig, and gzips.
# redroid images ship APEXes FLATTENED (directories, not .apex files) which is what a proot guest
# needs; if your image has only .apex files, rung A4 will report it.
#
# Then put it on the phone: either the in-app "Import Android userland" button, or
#   adb push out.tar.gz /sdcard/Download/   and pick it in the app.
# Licensing: redroid is Apache-2.0 (AOSP); it contains NO Google apps. Do not add GApps.
set -euo pipefail
IMG="${1:-redroid/redroid:16.0.0_64only-latest}"
OUT="${2:-android-userland.tar.gz}"
PLATFORM="${PLATFORM:-linux/arm64}"

docker pull --platform "$PLATFORM" "$IMG"
CID="$(docker create --platform "$PLATFORM" "$IMG")"
trap 'docker rm -f "$CID" >/dev/null 2>&1 || true' EXIT
WORK="$(mktemp -d)"
trap 'docker rm -f "$CID" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT
docker export "$CID" | tar -x -C "$WORK" -f -      # needs ~2-3 GB temp space
KEEP=""
for d in system vendor apex product system_ext odm linkerconfig; do
  [[ -e "$WORK/$d" ]] && KEEP="$KEEP $d"
done
echo "packing:$KEEP"
# shellcheck disable=SC2086
tar -c -C "$WORK" $KEEP | gzip -1 > "$OUT"
ls -lh "$OUT"
tar tzf "$OUT" | grep -m1 'system/build.prop' && echo "✓ contains system/build.prop"
