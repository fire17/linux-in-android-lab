#!/bin/sh
# Omarchy's terminal (Super+Return; also runs the "Install Omarchy apps" menu entries). alacritty needs OpenGL,
# which the phone's X server may not give (no GPU in the app): if alacritty exits at once, xterm (no GL) is used.
# usage: terminal.sh [command args...]
if command -v alacritty >/dev/null 2>&1; then
  t0=$(date +%s)
  if [ $# -gt 0 ]; then alacritty -e "$@"; else alacritty; fi
  rc=$?
  # A real session ends by the user (any rc, after a while). A start failure ends within ~2 s with an error.
  if [ "$rc" -eq 0 ] || [ $(( $(date +%s) - t0 )) -gt 2 ]; then exit "$rc"; fi
  echo "[omarchy] alacritty failed to start (rc $rc, no OpenGL?); using xterm" >&2
fi
if command -v xterm >/dev/null 2>&1; then
  if [ $# -gt 0 ]; then exec xterm -fa "JetBrainsMono Nerd Font" -fs 11 -bg '#1a1b26' -fg '#c0caf5' -e "$@"
  else exec xterm -fa "JetBrainsMono Nerd Font" -fs 11 -bg '#1a1b26' -fg '#c0caf5'; fi
fi
echo "[omarchy] no terminal works (alacritty failed, xterm not installed)" >&2
exit 127
