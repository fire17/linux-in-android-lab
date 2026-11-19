#!/bin/sh
# LinuxDesk "Genie access" guest setup. Runs INSIDE the Alpine guest under PRoot (fake root), only when the
# Genie-access switch is ON (spec 01 section 2, phase A). Idempotent; on success writes the marker
# /etc/linuxdesk/genie-v2 (v2 added pulseaudio; an existing genie-v1 install re-runs this), which the app checks. It never touches setup-packages.sh or its packages-v1 marker.
#
#   python3      runs /opt/linuxdesk/deskd/deskd.py (stdlib only)
#   py3-pillow   in-process JPEG encode for screen.capture source=x11 (the cheapest path under ptrace)
#   xwd          X screen capture (xwd -root)
#   imagemagick  fallback encoder (convert xwd:- jpeg:-) if Pillow is unusable
#   xdotool      input.* (XTEST) and window geometry; xwininfo/xprop for input.windows
#   pulseaudio   guest sound server (audio/PulseHost.kt): null sink 'ld' -> phone speaker, phone mic -> Linux.
#                pulseaudio-utils = pactl. PulseHost also installs these itself when Genie access is OFF.
#                Chromium talks to PulseAudio natively (libpulse), so alsa-plugins-pulse is not needed.
set -eu

PKGS="python3 py3-pillow xwd imagemagick xdotool xwininfo xprop pulseaudio pulseaudio-utils"

echo "[genie-setup] Alpine $(cat /etc/alpine-release 2>/dev/null || echo '?') on $(uname -m)"
attempt=1
until apk update >/dev/null; do
  if [ "$attempt" -ge 3 ]; then echo "[genie-setup] apk update failed 3 times (no network?)"; exit 2; fi
  attempt=$((attempt + 1)); echo "[genie-setup] retrying apk update in 5s"; sleep 5
done
# Under PRoot some post-install triggers fail harmlessly; the checks below decide.
apk add --no-progress $PKGS || echo "[genie-setup] apk reported errors (often harmless trigger failures under PRoot); verifying ..."

for b in python3 xwd xdotool; do
  command -v "$b" >/dev/null 2>&1 || { echo "[genie-setup] $b missing after install"; exit 3; }
done
python3 -c "import PIL" 2>/dev/null || echo "[genie-setup] note: Pillow not importable; deskd will use ImageMagick"

# The guest user for shell.exec as:"user" (uid/gid 1000). as:"root" stays proot's fake root (spec 01 1.3).
if ! id -u desk >/dev/null 2>&1; then
  adduser -D -u 1000 -h /home/desk -s /bin/sh desk
fi
mkdir -p /home/desk && chown 1000:1000 /home/desk 2>/dev/null || true

# deskd's runtime dir (selftest.log; the app writes genie.hash here at pairing).
mkdir -p /run/linuxdesk && chmod 700 /run/linuxdesk

# Retire the chrome-lane v1 opt-in: that marker makes start-browser.sh open an UNAUTHED DevTools port 9222.
# deskd drives Chromium over --remote-debugging-pipe instead, and refuses to start while the marker exists.
rm -f /etc/linuxdesk/genie-enabled /etc/linuxdesk/genie.key

mkdir -p /etc/linuxdesk
command -v pulseaudio >/dev/null 2>&1 && echo "pulseaudio pulseaudio-utils" > /etc/linuxdesk/audio-v1 \
  || echo "[genie-setup] note: pulseaudio missing; the session stays silent"
echo "$PKGS" > /etc/linuxdesk/genie-v2
echo "[genie-setup] done: $(python3 --version 2>&1), deskd at /opt/linuxdesk/deskd/deskd.py"
