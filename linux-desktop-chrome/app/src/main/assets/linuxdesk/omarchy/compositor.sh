#!/bin/sh
# picom (rounded corners, fades) with the xrender backend: the phone's X server has Composite, RENDER and DAMAGE but
# no OpenGL. On by default; `echo off > /etc/linuxdesk/compositor` turns it off (from the next Start or theme change).
# A picom that is missing or exits at once leaves the desktop exactly as without it.
pkill -x picom 2>/dev/null
[ "$(cat /etc/linuxdesk/compositor 2>/dev/null)" = off ] && exit 0
CFG="$HOME/.config/picom/picom.conf"
command -v picom >/dev/null 2>&1 && [ -f "$CFG" ] || exit 0
picom --config "$CFG" >/tmp/picom.log 2>&1 &
pid=$!
sleep 2
kill -0 "$pid" 2>/dev/null || echo "[omarchy] picom exited at start (see /tmp/picom.log); running without a compositor" >&2
exit 0
