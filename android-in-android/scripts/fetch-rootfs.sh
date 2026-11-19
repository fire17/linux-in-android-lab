#!/usr/bin/env bash
# Fetches the Alpine Linux 3.24.4 minirootfs (MIT/GPL mix, ~4 MB each) used by OnDevice rung A2
# ("Linux userland in the app"). sha256 values from dl-cdn.alpinelinux.org latest-releases.yaml
# (2026-11-19).
#
# The ANDROID userland (rung A3+) is NOT fetched here: a redroid 16 arm64 image is ~734 MB
# compressed (registry manifest, 2026-11-19), far too large for an APK. Build it on a Linux box
# with scripts/make-android-userland.sh and import it in the app.
source "$(dirname "$0")/_lib.sh"

V="3.24.4"
BASE="https://dl-cdn.alpinelinux.org/alpine/v3.24/releases"
OUT="$ROOT/app/src/main/assets/rootfs"

fetch "$BASE/aarch64/alpine-minirootfs-$V-aarch64.tar.gz" \
  4b14c18f7e90a063b61462ea1462a5732c154bf6adad594974fe6487cd98a8de \
  "$OUT/alpine-minirootfs-aarch64.rootfs.bin"
fetch "$BASE/x86_64/alpine-minirootfs-$V-x86_64.tar.gz" \
  cdaa38cb8de3537967ee5b6c9b6cd00cc962da2b018e6eea48696bb274923012 \
  "$OUT/alpine-minirootfs-x86_64.rootfs.bin"
