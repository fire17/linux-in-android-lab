#!/usr/bin/env bash
# One-time, per-device setup for the OnDevice AVF track (Pixel 7+ recommended).
# Run from a computer with adb, phone connected. Nothing here needs root or an unlocked bootloader.
# Source: scion-avf docs/SPIKE-1-AVF-LAUNCH.md (verified 2026-11-19).
#
#   ./scripts/avf-grant.sh            # grant + exempt
#   ./scripts/avf-grant.sh --revert   # undo everything
#
# SECURITY NOTE: hidden_api_blacklist_exemptions is a GLOBAL setting (all apps). We add only the
# two AVF prefixes, comma-separated (colon-separated is silently ignored). Revert when done.
set -euo pipefail
PKG=com.fire17.phoneinphone
EXEMPT='Landroid/system/virtualmachine/VirtualMachineCustomImageConfig$Builder;,Landroid/system/virtualmachine/VirtualMachineConfig$Builder;->setCustomImageConfig('

if [[ "${1:-}" == "--revert" ]]; then
  adb shell settings delete global hidden_api_blacklist_exemptions || true
  adb shell pm revoke "$PKG" android.permission.MANAGE_VIRTUAL_MACHINE || true
  adb shell pm revoke "$PKG" android.permission.USE_CUSTOM_VIRTUAL_MACHINE || true
  adb shell am force-stop "$PKG"
  echo "reverted"; exit 0
fi

adb shell pm list features | grep -q virtualization_framework \
  || { echo "This device does not declare android.software.virtualization_framework — AVF track will report FEATURE_MISSING."; }
adb shell pm grant "$PKG" android.permission.MANAGE_VIRTUAL_MACHINE
adb shell pm grant "$PKG" android.permission.USE_CUSTOM_VIRTUAL_MACHINE
adb shell settings put global hidden_api_blacklist_exemptions "'$EXEMPT'"
adb shell am force-stop "$PKG"   # exemption applies to processes started AFTER the change
echo "granted. current exemptions:"
adb shell settings get global hidden_api_blacklist_exemptions
