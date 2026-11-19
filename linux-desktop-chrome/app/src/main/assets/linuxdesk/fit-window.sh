#!/bin/sh
# LinuxDesk: keep Chromium's top-level window exactly covering the X screen.
#
# There is no window manager, so --start-maximized does nothing. And when the phone rotates, lorie
# resizes the X screen through RandR, but nothing tells Chromium. This loop polls the screen size with
# xdotool and moves and resizes Chromium's MAIN windows to 0,0 at screen size whenever they differ.
#
# It must skip:
#   - override-redirect windows: menus, the omnibox dropdown, tooltips. These are separate X windows with
#     the same WM_CLASS, and stretching them would break the UI.
#   - transient windows (WM_TRANSIENT_FOR): dialogs. They stay at their own size.
# It prints LINUXDESK_WINDOW_UP once, when the first main window appears; the app then shows RUNNING.
#
# Cost: a few small X calls per second. Unverified on a device: whether Chromium follows an external
# resize cleanly when no WM is present. If not, the fallback is a minimal WM (Alpine has ratpoison and evilwm).
export DISPLAY="${DISPLAY:-:0}"
announced=0
last=""

is_main_window() {
  info=$(xwininfo -id "$1" 2>/dev/null) || return 1
  echo "$info" | grep -q 'Override Redirect State: yes' && return 1
  echo "$info" | grep -q 'Map State: IsViewable' || return 1
  xprop -id "$1" WM_TRANSIENT_FOR 2>/dev/null | grep -q 'window id' && return 1
  return 0
}

while :; do
  geo=$(xdotool getdisplaygeometry 2>/dev/null)          # "W H"
  if [ -n "$geo" ]; then
    set -- $geo
    W=$1; H=$2
    for w in $(xdotool search --onlyvisible --class 'chrom' 2>/dev/null); do
      is_main_window "$w" || continue
      if [ $announced -eq 0 ]; then echo "LINUXDESK_WINDOW_UP ${W}x${H}"; announced=1; fi
      cur=$(xdotool getwindowgeometry "$w" 2>/dev/null | awk '/Geometry:/ {print $2}')
      if [ "$cur" != "${W}x${H}" ]; then
        xdotool windowmove "$w" 0 0 windowsize "$w" "$W" "$H" 2>/dev/null
      fi
    done
    if [ $announced -eq 1 ] && [ "$last" != "${W}x${H}" ]; then echo "[fit] screen ${W}x${H}"; last="${W}x${H}"; fi
  fi
  sleep 1
done
