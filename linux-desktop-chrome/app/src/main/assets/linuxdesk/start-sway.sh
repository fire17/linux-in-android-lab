#!/bin/sh
# LinuxDesk "Omarchy (Sway)": start sway nested on lorie (wlroots x11 backend), then Chrome as its first window.
# Runs INSIDE the Arch Linux ARM guest under PRoot. Same argv contract as start-omarchy.sh / start-browser.sh: all
# arguments are browser flags plus the URL (ChromiumFlags.kt). Plan: notes/sway-desktop-plan.md section 3.
#
# How it differs from start-omarchy.sh (i3):
#   - sway runs as a child of THIS script, i.e. under the session's own proot. Exp B0 showed that a sway started via
#     deskd's shell.exec dies with deskd (it is a tracee of deskd's proot), so never start it from there.
#   - lorie has no DRI3, so the renderer is pixman. No WM runs on lorie, so the sway output is sized to the X screen
#     with swaymsg and resized again when the phone rotates.
#   - Chrome is a Wayland client (--ozone-platform=wayland) so sway tiles it. If it dies at once on Wayland it is
#     started again through sway's XWayland. deskd's CDP pipe does not change.
#   - If sway is missing or not up within 10 s, the i3 desktop starts instead: exec start-omarchy.sh with the same argv.
# This script exits with the browser's code (127 when none is installed), like start-omarchy.sh; proot's
# --kill-on-exit then ends sway and anything started from it.
export DISPLAY="${DISPLAY:-:0}"
export XDG_RUNTIME_DIR=/tmp/runtime-sway
export LANG="${LANG:-C.UTF-8}"
export HOME="${HOME:-/root}"
export WLR_BACKENDS=x11 WLR_RENDERER=pixman WLR_LIBINPUT_NO_DEVICES=1 WLR_X11_OUTPUTS=1
export XDG_CURRENT_DESKTOP=sway XDG_SESSION_TYPE=wayland
unset WAYLAND_DISPLAY SWAYSOCK
FALLBACK=/opt/linuxdesk/start-omarchy.sh
mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR"
# proot --kill-on-exit ended the previous session's sway and dbus, so their sockets here are stale.
rm -f "$XDG_RUNTIME_DIR"/wayland-* "$XDG_RUNTIME_DIR"/sway-ipc.* "$XDG_RUNTIME_DIR"/bus

i=0
while [ ! -e /tmp/.X11-unix/X0 ] && [ $i -lt 40 ]; do sleep 0.5; i=$((i + 1)); done
[ -e /tmp/.X11-unix/X0 ] || echo "[sway] warning: /tmp/.X11-unix/X0 not found, trying anyway"

BIN=""
for b in google-chrome-stable chromium; do command -v "$b" >/dev/null 2>&1 && { BIN=$b; break; }; done
if [ -z "$BIN" ]; then
  echo "[sway] no browser installed (setup-omarchy.sh did not finish?)"
  exit 127
fi

if ! command -v sway >/dev/null 2>&1; then
  echo "[sway] sway is not installed; starting the i3 desktop instead"
  exec "$FALLBACK" "$@"
fi

chmod +x /opt/linuxdesk/omarchy/*.sh 2>/dev/null || true
# bubblewrap cannot sandbox under PRoot, so glycin failed every image (no wallpaper; wofi crashed on Super+Space).
# The shim runs glycin's loaders unsandboxed; /usr/local/bin is ahead of /usr/bin on PATH (2026-11-20, 0.2.29).
cmp -s /opt/linuxdesk/omarchy/bwrap-shim.sh /usr/local/bin/bwrap 2>/dev/null \
  || install -m 755 /opt/linuxdesk/omarchy/bwrap-shim.sh /usr/local/bin/bwrap 2>/dev/null || true

# Omarchy's apps (setup-omarchy.sh stage 8): same background install as start-omarchy.sh; never blocks the browser.
# The sway packages themselves are installed by setup's stage 9, which skips on low memory; this script only runs once
# sway exists, so a missing sway-v1 marker here means only a newer SWAY_REV (configs) is pending. Both share one
# background job: two pacman runs at once would fight over pacman's lock.
SWAYPKG=0
if [ "$(cat /etc/linuxdesk/sway-v1 2>/dev/null)" != 1 ] && [ -f /opt/linuxdesk/setup-omarchy.sh ]; then SWAYPKG=1; fi
APPS=0
if [ "$(cat /etc/linuxdesk/omarchy-stage-8 2>/dev/null)" != 1 ] && [ -f /opt/linuxdesk/setup-omarchy.sh ]; then
  APPS=1; echo "[sway] installing Omarchy's apps in the background (log: /etc/linuxdesk/omarchy-apps.log)"
fi
if [ "$SWAYPKG" = 1 ] || [ "$APPS" = 1 ]; then
  (
    if [ "$SWAYPKG" = 1 ]; then
      nice -n 10 sh /opt/linuxdesk/setup-omarchy.sh sway 2>&1 | grep --line-buffered '^\[omarchy\] sway:'
    fi
    if [ "$APPS" = 1 ]; then
      nice -n 10 sh /opt/linuxdesk/setup-omarchy.sh apps 2>&1 | tee -a /etc/linuxdesk/omarchy-apps.log \
        | grep --line-buffered '^\[omarchy\] apps:'
    fi
  ) &
fi

# A session bus first: without one B0 saw the tray, power-profiles and systemctl calls fail.
DBUS=""
if command -v dbus-daemon >/dev/null 2>&1; then
  dbus-daemon --session --address="unix:path=$XDG_RUNTIME_DIR/bus" --nofork --nopidfile >/tmp/sway-dbus.log 2>&1 &
  DBUS=$!
  i=0
  while [ ! -S "$XDG_RUNTIME_DIR/bus" ] && [ $i -lt 20 ]; do sleep 0.1; i=$((i + 1)); done
  export DBUS_SESSION_BUS_ADDRESS="unix:path=$XDG_RUNTIME_DIR/bus"
else
  echo "[sway] warning: dbus-daemon not installed, no session bus"
fi

CFG="$HOME/.config/sway/config"
[ -f "$CFG" ] || { echo "[sway] $CFG missing, using /etc/sway/config"; CFG=/etc/sway/config; }

XBEFORE=$(ls /tmp/.X11-unix 2>/dev/null)
sway -c "$CFG" >/tmp/sway.log 2>&1 &
SWAY=$!

# Up = alive, IPC socket answering and a Wayland socket made (max 10 s).
i=0; up=0
while [ $i -lt 40 ]; do
  kill -0 "$SWAY" 2>/dev/null || break
  sock=$(ls "$XDG_RUNTIME_DIR"/sway-ipc.*."$SWAY".sock 2>/dev/null | head -n 1)
  wl=$(cd "$XDG_RUNTIME_DIR" && ls wayland-[0-9] 2>/dev/null | head -n 1)
  if [ -n "$sock" ] && [ -n "$wl" ] && SWAYSOCK=$sock swaymsg -t get_version >/dev/null 2>&1; then up=1; break; fi
  sleep 0.25; i=$((i + 1))
done
if [ $up -ne 1 ]; then
  if kill -0 "$SWAY" 2>/dev/null; then why="not answering on IPC after 10 s"; else why="exited"; fi
  echo "[sway] sway $why; last lines of /tmp/sway.log:"
  tail -n 15 /tmp/sway.log 2>/dev/null | sed 's/^/[sway]   /'
  kill "$SWAY" 2>/dev/null
  [ -n "$DBUS" ] && kill "$DBUS" 2>/dev/null
  echo "[sway] starting the i3 desktop instead"
  exec "$FALLBACK" "$@"
fi
export SWAYSOCK="$sock" WAYLAND_DISPLAY="$wl"
echo "[sway] started (pid $SWAY, $WAYLAND_DISPLAY, config $CFG)"

# Fill lorie: size the sway output to the X screen now and whenever RandR changes it (rotation).
fit_output() {
  swaymsg output '*' mode --custom "${1}x${2}" >/dev/null 2>&1 || swaymsg output X11-1 mode "${1}x${2}" >/dev/null 2>&1
  swaymsg output '*' position 0 0 >/dev/null 2>&1
}
geo=$(xdotool getdisplaygeometry 2>/dev/null)
[ -n "$geo" ] && fit_output $geo
(
  last="$geo"
  while kill -0 "$SWAY" 2>/dev/null; do
    sleep 1
    g=$(xdotool getdisplaygeometry 2>/dev/null)
    if [ -n "$g" ] && [ "$g" != "$last" ]; then fit_output $g; last="$g"; fi
  done
) &
FIT=$!

# Drop flags that fight a window manager; Chrome draws through Wayland instead of lorie's X. GPU guard (as in
# start-omarchy.sh): no --use-gl= from GPU mode → --disable-gpu, whatever Mesa is installed; SwiftShader stays.
n=$#; GL=0
while [ "$n" -gt 0 ]; do
  a=$1; shift; n=$((n - 1))
  case "$a" in
    --window-position=*|--window-size=*|--start-maximized) ;;
    --ozone-platform=*) ;;
    --use-gl=*|--disable-gpu) GL=1; set -- "$@" "$a" ;;
    *) set -- "$@" "$a" ;;
  esac
done
[ "$GL" = 1 ] || { set -- --disable-gpu "$@"; echo "[sway] GPU mode off: added --disable-gpu"; }

# LINUXDESK_WINDOW_UP: the app shows RUNNING when the first browser window is in sway's tree.
(
  j=0
  while [ $j -lt 240 ]; do
    if swaymsg -t get_tree 2>/dev/null | grep -Eqi '"(app_id|class)": "[^"]*chrom'; then
      echo "LINUXDESK_WINDOW_UP $(xdotool getdisplaygeometry 2>/dev/null | tr ' ' x)"
      exit 0
    fi
    sleep 0.5; j=$((j + 1))
  done
) &
WATCH=$!

echo "[sway] exec $BIN --ozone-platform=wayland $*"
T0=$(date +%s)
"$BIN" --ozone-platform=wayland "$@"
CODE=$?
# XWayland fallback: Chrome failed at once on Wayland (no window yet); run it as an X client of sway's XWayland.
if [ $CODE -ne 0 ] && [ $(( $(date +%s) - T0 )) -lt 10 ] && kill -0 "$WATCH" 2>/dev/null; then
  xd=""
  for s in $(ls /tmp/.X11-unix 2>/dev/null); do
    echo "$XBEFORE" | grep -qx "$s" || { xd=":${s#X}"; break; }
  done
  if [ -n "$xd" ]; then
    echo "[sway] $BIN exited with code $CODE on Wayland; retrying through XWayland ($xd)"
    DISPLAY=$xd "$BIN" --ozone-platform=x11 "$@"
    CODE=$?
  else
    echo "[sway] $BIN exited with code $CODE on Wayland; no XWayland display (xwayland disabled in the config?)"
  fi
fi
kill "$WATCH" "$FIT" 2>/dev/null
echo "[sway] $BIN exited with code $CODE"
swaymsg exit >/dev/null 2>&1
[ -n "$DBUS" ] && kill "$DBUS" 2>/dev/null
exit $CODE
