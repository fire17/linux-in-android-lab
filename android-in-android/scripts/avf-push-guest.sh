#!/usr/bin/env bash
# Puts an arm64 Linux kernel (+ initrd) into the app's private files/guest/ for the AVF probe.
# Uses `run-as` (works for debuggable builds) so the files are owned by the app uid — `adb push`
# straight into app data would leave them owned by shell and unreadable (scion SPIKE-1).
#
# Default guest: Debian 13 "trixie" arm64 netboot kernel + initrd (it prints "Linux version ..."
# to hvc0 if console=hvc0 works, which is all the probe needs to prove "a kernel booted in a VM
# inside this app"). sha256 from deb.debian.org .../current/images/SHA256SUMS (2026-11-19).
# NOTE: "current" moves on point releases; if the sha256 check fails, update the pins below.
#
#   ./scripts/avf-push-guest.sh [kernel] [initrd]
set -euo pipefail
source "$(dirname "$0")/_lib.sh"
PKG=com.fire17.phoneinphone

KERNEL="${1:-}"; INITRD="${2:-}"
if [[ -z "$KERNEL" ]]; then
  BASE="https://deb.debian.org/debian/dists/trixie/main/installer-arm64/current/images/netboot/debian-installer/arm64"
  fetch "$BASE/linux"     9c37ace54ef7e1919411fd912e9f34d6982ddd7f4c8ef3c8aee56f4c4d70db67 "$CACHE/guest/Image"
  fetch "$BASE/initrd.gz" f8f48fe31f84fca1a43c3d57c09cd1c2ee728549dd18a986463efd0305249451 "$CACHE/guest/initrd.gz"
  KERNEL="$CACHE/guest/Image"; INITRD="$CACHE/guest/initrd.gz"
fi

adb shell run-as "$PKG" mkdir -p files/guest
adb push "$KERNEL" /data/local/tmp/pip-Image
adb shell "cat /data/local/tmp/pip-Image | run-as $PKG sh -c 'cat > files/guest/Image'"
if [[ -n "$INITRD" ]]; then
  adb push "$INITRD" /data/local/tmp/pip-initrd
  adb shell "cat /data/local/tmp/pip-initrd | run-as $PKG sh -c 'cat > files/guest/initrd.img'"
fi
adb shell rm -f /data/local/tmp/pip-Image /data/local/tmp/pip-initrd
adb shell run-as "$PKG" ls -l files/guest
