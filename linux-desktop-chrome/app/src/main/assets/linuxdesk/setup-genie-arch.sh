#!/bin/sh
# LinuxDesk "Genie access" guest setup for the Omarchy (Arch Linux ARM) distro. The Alpine twin is
# setup-genie.sh, which the Alpine distro keeps using unchanged. Same contract: runs INSIDE the guest under PRoot
# (fake root), only when the Genie-access switch is ON; idempotent; on success writes the SAME marker
# /etc/linuxdesk/genie-v2 that GenieRuntime checks (+ audio-v1 when pulseaudio is present).
#
# Package map (Alpine → Arch, names checked 2026-11-19 in the ALARM dbs):
#   python3 → python           py3-pillow → python-pillow     xwd → xorg-xwd      imagemagick → imagemagick
#   xdotool → xdotool          xwininfo → xorg-xwininfo       xprop → xorg-xprop
#   pulseaudio → pulseaudio    pulseaudio-utils → libpulse (pactl/parec/paplay live in libpulse on Arch)
set -eu

PKGS="python python-pillow xorg-xwd imagemagick xdotool xorg-xwininfo xorg-xprop pulseaudio libpulse"

echo "[genie-setup] $(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-Arch Linux ARM}") on $(uname -m)"
attempt=1
until pacman -Sy --noconfirm >/dev/null; do
  if [ "$attempt" -ge 3 ]; then echo "[genie-setup] pacman -Sy failed 3 times (no network?)"; exit 2; fi
  attempt=$((attempt + 1)); echo "[genie-setup] retrying pacman -Sy in 5s"; sleep 5
done
# Under PRoot some install hooks fail harmlessly; the checks below decide.
pacman -S --needed --noconfirm $PKGS || echo "[genie-setup] pacman reported errors (often harmless hook failures under PRoot); verifying ..."

for b in python3 xwd xdotool; do
  command -v "$b" >/dev/null 2>&1 || { echo "[genie-setup] $b missing after install"; exit 3; }
done
python3 -c "import PIL" 2>/dev/null || echo "[genie-setup] note: Pillow not importable; deskd will use ImageMagick"

# The guest user for shell.exec as:"user" is "desk" (uid/gid 1000), like on Alpine. Arch Linux ARM ships a stock
# user "alarm" with uid 1000, so that user is renamed to desk (same uid; deskd looks the user up by name).
if ! id -u desk >/dev/null 2>&1; then
  if id -u alarm >/dev/null 2>&1; then
    usermod -l desk -d /home/desk -m alarm 2>/dev/null || usermod -l desk -d /home/desk alarm
    groupmod -n desk alarm 2>/dev/null || true
  else
    useradd -m -u 1000 -U -d /home/desk -s /bin/sh desk
  fi
fi
mkdir -p /home/desk && chown 1000:1000 /home/desk 2>/dev/null || true

mkdir -p /run/linuxdesk && chmod 700 /run/linuxdesk
rm -f /etc/linuxdesk/genie-enabled /etc/linuxdesk/genie.key

mkdir -p /etc/linuxdesk
command -v pulseaudio >/dev/null 2>&1 && echo "pulseaudio libpulse" > /etc/linuxdesk/audio-v1 \
  || echo "[genie-setup] note: pulseaudio missing; the session stays silent"
echo "$PKGS" > /etc/linuxdesk/genie-v2
echo "[genie-setup] done: $(python3 --version 2>&1), deskd at /opt/linuxdesk/deskd/deskd.py"
