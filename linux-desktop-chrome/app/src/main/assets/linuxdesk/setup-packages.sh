#!/bin/sh
# LinuxDesk first-run package setup. Runs INSIDE the Alpine guest under PRoot (fake root).
# It is idempotent, and on success writes the marker /etc/linuxdesk/packages-v1, which the app checks.
#
# Versions available in Alpine v3.24 (aarch64), checked 2026-11-19 against the APKINDEX files:
#   chromium 156.0.8078.79-r0 (community)    xkeyboard-config 2.47-r0 (main)
#   font-noto-hebrew 2026.11.01-r0           font-dejavu 2.37-r6
#   xdotool 4.20260303.1-r0                  xwininfo 1.1.6-r0 / xprop 1.2.8-r0
# Expect a download of roughly 150-250 MB (chromium and its deps: mesa, ffmpeg libs, gtk3, ...).
set -eu

echo "[setup] Alpine $(cat /etc/alpine-release 2>/dev/null || echo '?') on $(uname -m)"
echo "[setup] repositories:"; cat /etc/apk/repositories

# xkeyboard-config: the embedded X server (lorie) compiles its keymap from /usr/share/X11/xkb.
#                   Without it the X server refuses to start.
# font-*:           Chromium renders through fontconfig. Noto Hebrew + DejaVu give Hebrew and Latin text.
# xdotool:          fit-window.sh uses it to size Chromium's window to the X screen (there is no window manager).
# dbus is optional. Chromium logs warnings without it but works.
# font-misc-misc + font-cursor-misc: X core fonts ("fixed", "cursor"). lorie looks for them under
#                   dirname(TMPDIR)/usr/share/fonts/X11. A stock X server aborts if it cannot open "fixed".
# python3:          runs /opt/linuxdesk/genie-daemon.py (genie access; stdlib only, no pip). python3 3.14.10-r1 (main).
PKGS="xkeyboard-config font-misc-misc font-cursor-misc font-dejavu font-noto-hebrew font-noto-emoji xdotool xwininfo xprop chromium python3"

attempt=1
until apk update; do
  if [ "$attempt" -ge 3 ]; then echo "[setup] apk update failed 3 times (no network?)"; exit 2; fi
  attempt=$((attempt + 1)); echo "[setup] retrying apk update in 5s"; sleep 5
done

# --no-progress keeps the log readable in the app. The stdout of this script is the in-app log.
#
# Under PRoot some post-install triggers can fail harmlessly (for example cache updates), and apk then exits
# non-zero. So don't abort here; the checks below decide whether the install is usable.
apk add --no-progress $PKGS || echo "[setup] apk reported errors (often harmless trigger failures under PRoot); verifying ..."

command -v chromium-browser >/dev/null 2>&1 || command -v chromium >/dev/null 2>&1 || {
  echo "[setup] chromium binary not found after install"; exit 3; }
[ -d /usr/share/X11/xkb ] || { echo "[setup] /usr/share/X11/xkb missing after install"; exit 4; }

fc-cache -f >/dev/null 2>&1 || true

mkdir -p /etc/linuxdesk
echo "$PKGS" > /etc/linuxdesk/packages-v1
echo "[setup] done: $(chromium-browser --version 2>/dev/null || chromium --version 2>/dev/null || echo chromium)"
