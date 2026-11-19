#!/bin/sh
# LinuxDesk: start Chromium on the embedded X server. Runs INSIDE the Alpine guest under PRoot.
# All arguments are Chromium flags plus the URL. The app computes them from Android DisplayMetrics
# (see ChromiumFlags.kt).
#
# There is no desktop and no window manager: one Chromium window on DISPLAY=:0, sized to the whole X screen
# by fit-window.sh. The X screen is the app's fullscreen SurfaceView, so the phone screen IS this window.
export DISPLAY="${DISPLAY:-:0}"
export XDG_RUNTIME_DIR=/tmp/runtime-root
export LANG="${LANG:-C.UTF-8}"
mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR"

# Wait up to ~20 s for the X server's socket (lorie creates it in this rootfs's /tmp).
i=0
while [ ! -S /tmp/.X11-unix/X0 ] && [ ! -e /tmp/.X11-unix/X0 ] && [ $i -lt 40 ]; do
  sleep 0.5; i=$((i + 1))
done
[ -e /tmp/.X11-unix/X0 ] || echo "[browser] warning: /tmp/.X11-unix/X0 not found, trying anyway"

BIN=chromium-browser
command -v "$BIN" >/dev/null 2>&1 || BIN=chromium
if ! command -v "$BIN" >/dev/null 2>&1; then
  echo "[browser] chromium is not installed (setup-packages.sh did not finish?)"
  exit 127
fi

/opt/linuxdesk/fit-window.sh &
FIT=$!

echo "[browser] exec $BIN $*"
"$BIN" "$@"
CODE=$?
kill "$FIT" 2>/dev/null
echo "[browser] $BIN exited with code $CODE"
exit $CODE
