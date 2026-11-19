#!/bin/sh
# The top bar (i3 runs this with exec_always, so a reload restarts it): polybar with the config omarchy-theme.py
# renders (Omarchy's waybar layout, sized by the UI scale). Without a working polybar, i3's own bar is shown instead:
# the generated i3 config keeps it as "bar-fallback" in mode invisible.
pkill -x polybar 2>/dev/null
CFG="$HOME/.config/polybar/config.ini"
if command -v polybar >/dev/null 2>&1 && [ -f "$CFG" ]; then
  polybar -c "$CFG" linuxdesk >/tmp/polybar.log 2>&1 &
  pid=$!
  sleep 2
  if kill -0 "$pid" 2>/dev/null; then
    i3-msg -q bar mode invisible bar-fallback 2>/dev/null
    exit 0
  fi
  echo "[omarchy] polybar exited at start (see /tmp/polybar.log); using i3bar" >&2
fi
i3-msg -q bar mode dock bar-fallback 2>/dev/null
