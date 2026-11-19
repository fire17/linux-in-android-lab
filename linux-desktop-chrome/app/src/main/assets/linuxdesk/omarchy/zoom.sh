#!/bin/sh
# Zoom the whole sway desktop (bars, dock, every window), not only Chrome: steps sway's output scale.
#   zoom.sh in|out|reset|apply     (the dock's zoom buttons, Super+equal / Super+minus / Super+0)
# Steps 1.0 1.25 1.5 1.75 2.0; the choice is kept in ~/.config/linuxdesk-zoom, which start-sway.sh applies again
# after it sizes the output (start and rotation). No file = 1.5 (checked live on the phone, 2026-11-20).
# `apply` sets the saved scale without stepping.
STEPS="1.0 1.25 1.5 1.75 2.0"
DEFAULT=1.5
FILE="${HOME:-/root}/.config/linuxdesk-zoom"

saved() {
  z=$(cat "$FILE" 2>/dev/null)
  case "$z" in ''|*[!0-9.]*) z=$DEFAULT ;; esac
  echo "$z"
}
# The scale sway uses now (first output); the saved one when swaymsg cannot answer.
current() {
  swaymsg -t get_outputs 2>/dev/null | python3 -c '
import json, sys
try:
    o = json.load(sys.stdin)
    print(o[0]["scale"] if o else "")
except Exception:
    pass
' 2>/dev/null | grep . || saved
}

cur=$(current)
case "${1:-}" in
  in)    z=$(echo "$STEPS" | tr ' ' '\n' | awk -v c="$cur" '$1 > c + 0.01 { print; exit }') ;;
  out)   z=$(echo "$STEPS" | tr ' ' '\n' | awk -v c="$cur" '$1 < c - 0.01 { z = $1 } END { print z }') ;;
  reset) z=$DEFAULT ;;
  apply) z=$(saved) ;;
  *) echo "usage: zoom.sh in|out|reset|apply (now $cur)" >&2; exit 2 ;;
esac
# Already at the last step: nothing to do.
[ -n "$z" ] || exit 0
swaymsg output '*' scale "$z" >/dev/null || exit 1
mkdir -p "$(dirname "$FILE")" && echo "$z" > "$FILE"
