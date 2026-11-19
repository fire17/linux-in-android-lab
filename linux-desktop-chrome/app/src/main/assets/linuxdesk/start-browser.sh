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

# Genie access (opt-in, OFF by default): Chromium exposes DevTools on 127.0.0.1:9222 and the genie
# daemon (genie-daemon.py, key-authed) listens on 127.0.0.1:8765. Turn it on with the marker file
# /etc/linuxdesk/genie-enabled (debug APK: `adb shell run-as com.fire17.linuxdesk touch
# files/rootfs/etc/linuxdesk/genie-enabled`) or LINUXDESK_GENIE=1. Without it, this script runs
# Chromium exactly as before. Caveat: the DevTools port itself has no auth, so any app on the phone
# could drive the browser while genie access is on (spec 01 prefers --remote-debugging-pipe later).
GENIE=""
if [ "${LINUXDESK_GENIE:-0}" = "1" ] || [ -e /etc/linuxdesk/genie-enabled ]; then
  if ! command -v python3 >/dev/null 2>&1; then
    echo "[genie] python3 missing, installing it (one time)"
    apk add --no-progress python3 >/dev/null 2>&1 || echo "[genie] apk add python3 failed"
  fi
  set -- --remote-debugging-port=9222 --remote-debugging-address=127.0.0.1 "$@"
  if command -v python3 >/dev/null 2>&1; then
    python3 /opt/linuxdesk/genie-daemon.py --port 8765 --cdp-port 9222 &
    GENIE=$!
  else
    echo "[genie] daemon not started (no python3); DevTools is on 127.0.0.1:9222"
  fi
fi

# GPU guard (as in start-omarchy.sh): no --use-gl= from GPU mode → --disable-gpu, whatever Mesa is installed.
GL=0
for a in "$@"; do case "$a" in --use-gl=*|--disable-gpu) GL=1 ;; esac; done
[ "$GL" = 1 ] || { set -- --disable-gpu "$@"; echo "[browser] GPU mode off: added --disable-gpu"; }

echo "[browser] exec $BIN $*"
"$BIN" "$@"
CODE=$?
kill "$FIT" 2>/dev/null
[ -n "$GENIE" ] && kill "$GENIE" 2>/dev/null
echo "[browser] $BIN exited with code $CODE"
exit $CODE
