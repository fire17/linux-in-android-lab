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
#   - If sway is missing or not up within 10 s: one `LINUXDESK_ERROR <reason>` line (the app pops it up) and exit 1;
#     nothing else starts (Tami 13:42, AC5CC9EB: "NOT a fallback"). LINUXDESK_ALLOW_FALLBACK=1 (default OFF) brings
#     back the old fallback: exec start-omarchy.sh (i3) with the same argv.
#   - Status markers for the app: LINUXDESK_STAGE_NOW Sway|Chrome as each starts, LINUXDESK_DESKTOP Sway once it answers.
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
# nosway <why>: sway did not come up. Reported, or the i3 desktop with LINUXDESK_ALLOW_FALLBACK=1.
nosway() {
  why=$1; shift
  if [ "${LINUXDESK_ALLOW_FALLBACK:-0}" = 1 ]; then
    echo "[sway] $why; starting the i3 desktop instead (LINUXDESK_ALLOW_FALLBACK=1)"
    exec "$FALLBACK" "$@"
  fi
  echo "[sway] $why; no other desktop is started (LINUXDESK_ALLOW_FALLBACK=1 = the old fallback)"
  echo "LINUXDESK_ERROR Sway did not start: $why"
  exit 1
}
mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR"
# proot --kill-on-exit ended the previous session's sway and dbus, so their sockets here are stale.
rm -f "$XDG_RUNTIME_DIR"/wayland-* "$XDG_RUNTIME_DIR"/sway-ipc.* "$XDG_RUNTIME_DIR"/bus

i=0
while [ ! -e /tmp/.X11-unix/X0 ] && [ $i -lt 40 ]; do sleep 0.5; i=$((i + 1)); done
[ -e /tmp/.X11-unix/X0 ] || echo "[sway] warning: /tmp/.X11-unix/X0 not found, trying anyway"

BIN=""
for b in google-chrome-stable chromium; do command -v "$b" >/dev/null 2>&1 && { BIN=$b; break; }; done
if [ -z "$BIN" ] && [ "${LINUXDESK_OPEN_BROWSER:-0}" = 1 ]; then
  echo "[sway] no browser installed (setup-omarchy.sh did not finish?)"
  echo "LINUXDESK_ERROR Chrome is not installed in this rootfs (setup did not finish?)"
  exit 127
fi

command -v sway >/dev/null 2>&1 || nosway "sway is not installed" "$@"

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
# 2 = setup-omarchy.sh's SWAY_REV (2: the end4 extras matugen nwg-dock swaync inter-font); keep the two in step.
if [ "$(cat /etc/linuxdesk/sway-v1 2>/dev/null)" != 2 ] && [ -f /opt/linuxdesk/setup-omarchy.sh ]; then SWAYPKG=1; fi
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
# The theme renders once at setup (setup-omarchy.sh sway); re-render if the config is missing or was generated by an
# older sway-theme.py (see SWAY_REV there) or for another UI scale, keeping the theme (like start-omarchy.sh for i3).
if [ -f /opt/linuxdesk/omarchy/sway-theme.py ] \
  && ! grep -qxF "$(python3 /opt/linuxdesk/omarchy/sway-theme.py --stamp 2>/dev/null || echo '#none')" "$CFG" 2>/dev/null; then
  python3 /opt/linuxdesk/omarchy/sway-theme.py "$(cat /etc/linuxdesk/omarchy-theme 2>/dev/null || echo tokyo-night)" \
    || echo "[sway] note: theme render failed; keeping the old config"
fi
[ -f "$CFG" ] || { echo "[sway] $CFG missing, using /etc/sway/config"; CFG=/etc/sway/config; }

XBEFORE=$(ls /tmp/.X11-unix 2>/dev/null)
echo "LINUXDESK_STAGE_NOW Sway"
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
  nosway "sway $why: $(grep -v '^[[:space:]]*$' /tmp/sway.log 2>/dev/null | tail -n 1 | cut -c1-160)" "$@"
fi
export SWAYSOCK="$sock" WAYLAND_DISPLAY="$wl"
echo "[sway] started (pid $SWAY, $WAYLAND_DISPLAY, config $CFG)"
echo "LINUXDESK_DESKTOP Sway"

# Fill lorie: size the sway output to the X screen now and whenever RandR changes it (rotation). Then the desktop's
# zoom: zoom.sh apply sets the scale saved in ~/.config/linuxdesk-zoom (the dock's zoom buttons; 1.5 when none).
fit_output() {
  swaymsg output '*' mode --custom "${1}x${2}" >/dev/null 2>&1 || swaymsg output X11-1 mode "${1}x${2}" >/dev/null 2>&1
  swaymsg output '*' position 0 0 >/dev/null 2>&1
  sh /opt/linuxdesk/omarchy/zoom.sh apply >/dev/null 2>&1 || swaymsg output '*' scale 1.5 >/dev/null 2>&1
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

# Tami 11:02: "Can we please not launch Chrome automatically, or at least have a toggle for it? I want the actual OS to
# be fully active." Default: no browser, the desktop is the session (RUNNING as soon as sway answers, above).
# LINUXDESK_OPEN_BROWSER=1 (launcher "Open Chrome at Start") keeps the Chrome-first start below.
CODE=0; WATCH=""
if [ "${LINUXDESK_OPEN_BROWSER:-0}" = 1 ]; then
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

echo "LINUXDESK_STAGE_NOW Chrome"
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
kill "$WATCH" 2>/dev/null
echo "[sway] $BIN exited with code $CODE"
else
  echo "LINUXDESK_WINDOW_UP $(xdotool getdisplaygeometry 2>/dev/null | tr ' ' x)"
  echo "[sway] desktop up without a browser (LINUXDESK_OPEN_BROWSER=0); open it from the dock or the app"
fi
kill "$FIT" 2>/dev/null
# Tami 06:49: closing Chrome must not take the desktop down. sway stays up until it exits itself (logout) or the app's
# Stop; the dock reopens Chrome. LINUXDESK_SWAY_HOLD=0 keeps the old Chrome-is-the-session behaviour (only with a
# browser at start). The app's "Restart browser" touches $TRIGGER: Chrome opens again with the same flags, a Wayland
# client of sway (started from here, not `swaymsg exec`: the argv stays exact, no re-quoting through sway's parser).
TRIGGER=${LINUXDESK_BROWSER_TRIGGER:-/tmp/linuxdesk-open-browser}
rm -f "$TRIGGER"
if { [ "${LINUXDESK_SWAY_HOLD:-1}" = 1 ] || [ "${LINUXDESK_OPEN_BROWSER:-0}" != 1 ]; } && kill -0 "$SWAY" 2>/dev/null; then
  echo "[sway] the desktop stays up without $BIN (sway pid $SWAY); reopen it from the dock"
  while kill -0 "$SWAY" 2>/dev/null; do
    if [ -e "$TRIGGER" ]; then
      rm -f "$TRIGGER"
      if [ -n "$BIN" ]; then
        echo "LINUXDESK_STAGE_NOW Chrome"
        "$BIN" --ozone-platform=wayland "$@" > /tmp/linuxdesk-browser.log 2>&1 < /dev/null &
        echo "[sway] opened $BIN on request ($TRIGGER)"
      else
        echo "LINUXDESK_ERROR Chrome is not installed in this rootfs"
      fi
    fi
    sleep 2
  done
  echo "[sway] sway exited"
  # Not the app's Stop (that kills this script first): the desktop ended by itself, so the app says so (a pop-up).
  echo "LINUXDESK_ERROR Sway exited: $(grep -v '^[[:space:]]*$' /tmp/sway.log 2>/dev/null | tail -n 1 | cut -c1-160)"
  CODE=0
fi
swaymsg exit >/dev/null 2>&1
[ -n "$DBUS" ] && kill "$DBUS" 2>/dev/null
exit $CODE
