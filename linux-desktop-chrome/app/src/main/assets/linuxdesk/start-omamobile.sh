#!/bin/sh
# LinuxDesk "Omarchy Mobile" session: Omarchy Mobile's phone shell (github.com/derekross/omarchy-mobile, MIT, community
# port; notes/omamobile/REPORT.md) on lorie. A fork of start-hyprland.sh: Weston (x11 backend, windowed, PORTRAIT) is
# the Wayland host, the patched Hyprland 0.56.1 from /opt/omarchy-android runs nested in it at the phone scale (2) with
# Omarchy's config + Omarchy Mobile's phone.lua (looknfeel.lua dofile's it; setup-omamobile.sh stage 6), and Omarchy's
# shell comes up with the phone plugins: status bar, home screens, app drawer, switcher, back edge, quick settings.
#
# Like a phone, the session opens on the HOME SCREEN: Chrome is NOT started (it is in the app drawer, with LinuxDesk's
# flags in ~/.config/chrome-flags.conf). LINUXDESK_OPEN_BROWSER=1 starts it as well, start-hyprland.sh style.
# The session holds until Hyprland exits (or the app's Stop); same argv contract as every start script (browser flags +
# URL, ChromiumFlags.kt), used for the drawer's Chrome flags and for the never-black fallback (Chrome alone on X11).
#
# phone.lua was written for Hyprland 0.57. Checked against upstream v0.56.1's Lua bindings (2026-11-20, lane
# omamobile): every hl.* call, rule field and option it uses exists in 0.56.1, and unknown keys are logged, not fatal.
# Differences: quirks.skip_non_kms_dmabuf_formats is already off in 0.56.1 (its pcall'd line is a no-op), and the PiP
# `move` rule uses the old width in 0.56.1 (#16150), fixed in ~/.config/hypr/linuxdesk-omamobile.lua.
# NOT checked: the omarchy-android patches on top of 0.56.1 (none are known to touch src/config/lua).
#
# Knobs: LINUXDESK_OMAMOBILE_SCALE (default 2), LINUXDESK_OMAMOBILE_PORTRAIT=0 (use the whole display when it is
# landscape), LINUXDESK_WESTON_RENDERER (gl = start-hyprland.sh's proven zink path, default; pixman),
# LINUXDESK_OPEN_BROWSER=1 (or LINUXDESK_OMAMOBILE_CHROME=1), LINUXDESK_HYPR_HOLD=0 (end with Chrome, the old contract).
# Test hooks as start-hyprland.sh: LINUXDESK_DIR, LINUXDESK_HYPR_ROOT, LINUXDESK_HYPR_RT, LINUXDESK_MEMINFO,
# LINUXDESK_HYPR_WAIT.
export DISPLAY="${DISPLAY:-:0}"
export LANG="${LANG:-C.UTF-8}"
LD=${LINUXDESK_DIR:-/opt/linuxdesk}
OA=${LINUXDESK_HYPR_ROOT:-/opt/omarchy-android}
RT=${LINUXDESK_HYPR_RT:-/tmp/runtime-omamobile}
MEMINFO=${LINUXDESK_MEMINFO:-/proc/meminfo}
UH=/home/desk
HB=$OA/hyprland/bin
LDP=$OA/aquamarine/lib:$OA/compat/lib
WWAIT=${LINUXDESK_HYPR_WAIT:-30}
HWAIT=${LINUXDESK_HYPR_WAIT:-20}
S=${LINUXDESK_OMAMOBILE_SCALE:-2}
say() { echo "[omamobile] $*"; }

PIDS=""
WATCH=""
stop_ours() {
  [ -n "$WATCH" ] && kill "$WATCH" 2>/dev/null
  for p in $PIDS; do kill -TERM -- "-$p" 2>/dev/null || kill -TERM "$p" 2>/dev/null; done
  [ -n "$PIDS" ] && sleep 1
  for p in $PIDS; do
    kill -0 "$p" 2>/dev/null && { kill -KILL -- "-$p" 2>/dev/null || kill -KILL "$p" 2>/dev/null; }
  done
  PIDS=""; WATCH=""
}
BIN=""
for b in google-chrome-stable chromium; do command -v "$b" >/dev/null 2>&1 && { BIN=$b; break; }; done
# Never a black screen: Chrome alone on lorie X11 (this rootfs has no sway).
fallback() {  # fallback <why> "$@"
  why=$1; shift
  say "$why; Chrome alone on X11 instead (Stop and Start again to retry the phone shell)"
  stop_ours
  [ -n "$BIN" ] || { say "no browser installed either"; exit 127; }
  ( j=0; while [ $j -lt 240 ]; do
      xdotool search --onlyvisible --class chrom >/dev/null 2>&1 && { echo "LINUXDESK_WINDOW_UP"; exit 0; }
      sleep 0.5; j=$((j + 1)); done ) &
  "$BIN" "$@"
  exit $?
}

# ---- 1. memory gate (start-hyprland.sh) ------------------------------------------------------------------------------
ma=$(awk '$1 == "MemAvailable:" { print $2 }' "$MEMINFO" 2>/dev/null)
sf=$(awk '$1 == "SwapFree:" { print $2 }' "$MEMINFO" 2>/dev/null)
case "$ma$sf" in ''|*[!0-9]*) fallback "memory unknown (MemAvailable='$ma' SwapFree='$sf')" "$@" ;; esac
if [ "$ma" -lt 2621440 ] || [ "$sf" -lt 1258291 ]; then
  fallback "memory gate: MemAvailable $((ma / 1024)) MB (need 2560), SwapFree $((sf / 1024)) MB (need 1229)" "$@"
fi
say "memory gate OK (MemAvailable $((ma / 1024)) MB, SwapFree $((sf / 1024)) MB)"

# ---- 2. what must exist ----------------------------------------------------------------------------------------------
[ -x "$HB/Hyprland" ] || fallback "no patched Hyprland at $HB/Hyprland" "$@"
[ -x "$HB/hyprctl" ] || fallback "no hyprctl at $HB/hyprctl" "$@"
for c in weston dbus-run-session quickshell; do command -v "$c" >/dev/null 2>&1 || fallback "$c is not installed" "$@"; done
HCFG=$UH/.config/hypr/hyprland.lua
[ -f "$HCFG" ] || fallback "no Hyprland config $HCFG (setup-omamobile.sh stage 6?)" "$@"
grep -qs 'hypr/phone\.lua' "$UH"/.config/hypr/*.lua || say "note: phone.lua is not loaded by the user config (desktop rules)"
# The app refreshes /opt/linuxdesk every Start: current PRoot shims (systemctl, uwsm, sudo, systemd-cat, systemd-run,
# systemd-inhibit, loginctl, bwrap) into /usr/local/bin.
[ -f "$LD/clean/install-shims.sh" ] && { sh "$LD/clean/install-shims.sh" || say "note: the PRoot shims could not be refreshed"; }
OMARCHY_PATH=$(cat /etc/linuxdesk/omamobile-omarchy-path 2>/dev/null || echo /usr/share/omarchy)
[ -f "$OMARCHY_PATH/shell/shell.qml" ] || OMARCHY_PATH=/usr/share/omarchy

# ---- 3. Chrome in the drawer gets LinuxDesk's flags --------------------------------------------------------------------
# google-chrome-stable / chromium read ~/.config/{chrome,chromium}-flags.conf. Keep the user's (and omamobile-setup-user's
# phone UA / scale) lines; replace only the block between our markers with the app's flags (no X11 / window geometry:
# here Chrome is a Wayland client of Hyprland).
write_flags() {
  for f in "$UH/.config/chrome-flags.conf" "$UH/.config/chromium-flags.conf"; do
    t=$f.new
    { [ -f "$f" ] && sed '/^# >>> linuxdesk/,/^# <<< linuxdesk/d' "$f"
      echo "# >>> linuxdesk (start-omamobile.sh rewrites this block every Start)"
      GL=0
      for a in "$@"; do
        case "$a" in
          --window-position=*|--window-size=*|--start-maximized|--ozone-platform=*|--kiosk|--app=*|--top-chrome-touch-ui=*) ;;
          --force-device-scale-factor=*) ;;   # omamobile-setup-user's phone value (0.83) stays
          --use-gl=*|--disable-gpu) GL=1; echo "$a" ;;
          --*) echo "$a" ;;
        esac
      done
      [ "$GL" = 1 ] || echo --disable-gpu
      echo --ozone-platform=wayland
      echo "# <<< linuxdesk"
    } > "$t" 2>/dev/null && mv -f "$t" "$f" && chown 1000:1000 "$f" 2>/dev/null
  done
}
write_flags "$@"

# ---- 4. runtime dir + the plugin index (start-hyprland.sh) --------------------------------------------------------------
mkdir -p "$RT" && chmod 700 "$RT" || fallback "cannot create $RT" "$@"
rm -rf "$RT"/wayland-* "$RT"/hypr "$RT"/bus 2>/dev/null
AS=""
if command -v setpriv >/dev/null 2>&1 && setpriv --reuid=1000 --regid=1000 --clear-groups true 2>/dev/null; then
  AS="setpriv --reuid=1000 --regid=1000 --clear-groups"
  chown -R 1000:1000 "$RT" 2>/dev/null
fi
out=$RT/omarchy-plugin-scan.txt; tmp=$out.new; root=$OMARCHY_PATH/shell/plugins
: > "$tmp"
for m in "$root"/*/manifest.json "$root"/*/*/manifest.json "$root"/*/*/*.manifest.json; do
  [ -f "$m" ] || continue
  case "${m##*/}" in manifest.json) sub=${m%/manifest.json} ;; *) sub=${m%/*} ;; esac
  printf '===firstparty::%s===\n' "$sub" >> "$tmp"; cat "$m" >> "$tmp"; printf '\n=== EOM ===\n' >> "$tmp"
done
for sub in "$UH/.config/omarchy/plugins"/*/; do
  [ -f "$sub/manifest.json" ] || continue
  printf '===thirdparty::%s===\n' "${sub%/}" >> "$tmp"; cat "$sub/manifest.json" >> "$tmp"; printf '\n=== EOM ===\n' >> "$tmp"
done
mv -f "$tmp" "$out"
say "plugin index: $(grep -c '^===firstparty::' "$out") Omarchy + $(grep -c '^===thirdparty::' "$out") phone plugins"

# ---- 5. Weston, windowed, PORTRAIT, on lorie ---------------------------------------------------------------------------
i=0
while [ ! -e /tmp/.X11-unix/X0 ] && [ $i -lt 40 ] && [ -z "${LINUXDESK_DIR:-}" ]; do sleep 0.5; i=$((i + 1)); done
GEO=$(xdotool getdisplaygeometry 2>/dev/null)
W=${GEO% *}; H=${GEO#* }
case "$W$H" in ''|*[!0-9]*) W=968; H=2176 ;; esac
# Landscape display (an unfolded Fold): a phone-shaped 6:13 window, like upstream's run-desktop. Top-left (lorie has no
# window manager to centre it).
if [ "${LINUXDESK_OMAMOBILE_PORTRAIT:-1}" = 1 ] && [ "$W" -gt "$H" ]; then W=$((H * 6 / 13)); fi
# Hyprland keeps a scale only when the logical size is whole: round both sides down to a multiple of the scale.
case "$S" in ''|*[!0-9]*) S=2 ;; esac
W=$((W / S * S)); H=$((H / S * S))
ICD=$(ls /usr/share/vulkan/icd.d/freedreno_icd*.json 2>/dev/null | head -n 1)
WR=${LINUXDESK_WESTON_RENDERER:-gl}
# shellcheck disable=SC2086  # AS is a command prefix, intended to split
setsid $AS env XDG_RUNTIME_DIR="$RT" DISPLAY="$DISPLAY" MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink \
  VK_ICD_FILENAMES="$ICD" weston -B x11 --renderer="$WR" --shell=kiosk --socket=wayland-omarchy \
  --width="$W" --height="$H" --scale=1 --idle-time=0 > /tmp/omamobile-weston.log 2>&1 < /dev/null &
WPID=$!; PIDS="$PIDS $WPID"
i=0
while [ ! -S "$RT/wayland-omarchy" ] && [ $i -lt $((WWAIT * 2)) ]; do
  kill -0 "$WPID" 2>/dev/null || break
  sleep 0.5; i=$((i + 1))
done
if [ ! -S "$RT/wayland-omarchy" ]; then
  tail -n 10 /tmp/omamobile-weston.log 2>/dev/null | sed 's/^/[omamobile]   /'
  fallback "weston did not come up" "$@"
fi
say "weston up (pid $WPID, ${W}x${H} portrait, scale $S -> $((W / S))x$((H / S)) logical)"

# ---- 6. Hyprland nested in Weston, the phone profile ----------------------------------------------------------------------
start_hyprland() {
  set -- dbus-run-session --config-file="$UH/.config/omarchy/proot-session-bus.conf"
  [ -f "$UH/.config/omarchy/proot-session-bus.conf" ] || set -- dbus-run-session
  VICD=$ICD; M=$OA/mesa/root/usr; MLDP=$LDP; MENV=""
  # The bundle's own KGSL Mesa when present (start-omarchy-clean.sh): ALARM's Mesa has no kgsl driver.
  if [ -d "$M/lib" ]; then
    MLDP=$M/lib:$LDP; MENV="LIBGL_DRIVERS_PATH=$M/lib/dri"
    VICD=$(ls "$M"/share/vulkan/icd.d/freedreno_icd*.json 2>/dev/null | head -n 1); VICD=${VICD:-$ICD}
  fi
  # shellcheck disable=SC2086
  ( cd "$UH" 2>/dev/null || cd /
    exec setsid $AS env -u DISPLAY -u WAYLAND_DISPLAY $MENV \
    HOME="$UH" USER=desk LOGNAME=desk XDG_CONFIG_HOME="$UH/.config" XDG_RUNTIME_DIR="$RT" WAYLAND_DISPLAY=wayland-omarchy \
    XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=Hyprland XDG_SESSION_DESKTOP=Hyprland LANG=C.UTF-8 LC_ALL=C.UTF-8 \
    OMARCHY_PROOT=1 OMARCHY_GPU_MODE=kgsl OMARCHY_UNLIMITED_PROCS=1 \
    OMARCHY_OUTPUT_WIDTH="$W" OMARCHY_OUTPUT_HEIGHT="$H" OMARCHY_REFRESH_MHZ=60000 OMARCHY_SCALE="$S" \
    OMARCHY_KEYBOARD_LAYOUT=us NO_AT_BRIDGE=1 QT_ACCESSIBILITY=0 GTK_A11Y=none XWAYLAND_NO_GLAMOR=1 \
    INPUT_METHOD=none QT_IM_MODULE=wayland GTK_IM_MODULE=wayland XMODIFIERS=@im=none SDL_IM_MODULE=none \
    GTK_USE_PORTAL=1 GDK_DEBUG=portals OMARCHY_SCREENSHOT_EDITOR=omamobile-screenshot-open \
    MESA_LOADER_DRIVER_OVERRIDE=kgsl FD_KGSL_ENABLE_DMABUF=1 VK_ICD_FILENAMES="$VICD" \
    LD_LIBRARY_PATH="$MLDP" \
    AQ_NO_KMS_REQUIREMENT=1 AQ_ANDROID_DMABUF_HEAP=0 AQ_ANDROID_DMABUF_GLFINISH=0 AQ_ANDROID_DMABUF_VULKAN=0 \
    AQ_WAYLAND_REFRESH_MHZ=60000 HYPRLAND_ANDROID_DMABUF=0 HYPRLAND_SHM_GLFINISH=0 HYPRLAND_SHM_DAMAGE=1 \
    HYPRLAND_SHM_PROFILE=0 HYPRLAND_GL_DMABUF_EXPORT=0 AQ_NO_MODIFIERS=1 HYPRLAND_EGL_NO_MODIFIERS=1 \
    HYPRLAND_EGL_SURFACELESS=1 HYPRLAND_NO_SD_NOTIFY=1 HYPRLAND_NO_SD_VARS=1 HYPRLAND_NO_RT=1 \
    QT_QPA_PLATFORM=wayland GDK_BACKEND=wayland SDL_VIDEODRIVER=wayland QT_QUICK_BACKEND="${LINUXDESK_QT_BACKEND:-}" \
    OMARCHY_PATH="$OMARCHY_PATH" \
    PATH="$HB:/usr/local/bin:/usr/bin:/bin" \
    XDG_DATA_DIRS="$OA/hyprland/share:/usr/local/share:/usr/share" \
    "$@" "$HB/Hyprland" --config "$HCFG" ) > /tmp/omamobile-hyprland.log 2>&1 < /dev/null &
  HPID=$!; PIDS="$PIDS $HPID"
}
hyprctl() {
  sig=$(ls -t "$RT/hypr" 2>/dev/null | head -n 1)
  XDG_RUNTIME_DIR=$RT HYPRLAND_INSTANCE_SIGNATURE=$sig LD_LIBRARY_PATH=$LDP "$HB/hyprctl" "$@"
}
start_hyprland
i=0; up=0
while [ $i -lt $((HWAIT * 2)) ]; do
  kill -0 "$HPID" 2>/dev/null || break
  if hyprctl version >/dev/null 2>&1; then up=1; break; fi
  sleep 0.5; i=$((i + 1))
done
if [ $up -ne 1 ]; then
  tail -n 12 /tmp/omamobile-hyprland.log 2>/dev/null | sed 's/^/[omamobile]   /'
  fallback "Hyprland did not come up" "$@"
fi
# No start-hyprland watchdog / guiutils warning bars (also in linuxdesk-omamobile.lua; this covers a config error).
hyprctl eval 'hl.config({ misc = { disable_hyprland_guiutils_check = true, disable_watchdog_warning = true } })' >/dev/null 2>&1
say "Hyprland up (pid $HPID); Omarchy's autostart starts the shell, phone.lua's rules are loaded"

# ---- 7. the phone helpers, through the compositor (they get Hyprland's env: Wayland, the session bus) ----------------------
# squeekboard: omamobile-setup-user also adds it to autostart.lua; the pgrep guard keeps one. osk-guard turns the OSK
# setting back on (its user unit, here without systemd). omamobile-osk-watch is spawned by the keyboard/launcher plugins
# themselves; omamobile-portal-access is D-Bus activated. Not started (hardware or opt-in): sleepd, doubletap, keys,
# omatel-agent, voxtype (PipeWire would fight PulseHost; REPORT.md risk 3).
hx() { hyprctl eval "hl.exec_cmd([[$1]])" >/dev/null 2>&1 || say "note: could not start: $1"; }
hx "pgrep -x squeekboard >/dev/null || exec setsid squeekboard > /tmp/omamobile-squeekboard.log 2>&1"
command -v omamobile-osk-guard >/dev/null 2>&1 \
  && hx "pgrep -f '[o]mamobile-osk-guard' >/dev/null || exec setsid omamobile-osk-guard > /tmp/omamobile-osk-guard.log 2>&1"
# HOOK(indicators): when start-hyprland.sh gains the Wi-Fi/battery/BT overlay step (indicators lane, notes/indicators/),
# mirror it HERE (after Hyprland answers, before the window watcher), through hx() so it gets the session's env.

# ---- 8. on screen = the shell's home screen (no Chrome window to wait for) ----------------------------------------------
# LINUXDESK_WINDOW_UP tells the app (RUNNING) and the setup screen that the desktop is visible: the phone bar or the
# home screen layer. After 90 s without either, say so anyway (Hyprland itself is up) so the app never waits forever.
(
  j=0
  while [ $j -lt 180 ]; do
    if hyprctl -j layers 2>/dev/null | grep -Eq '"namespace": *"(omarchy-bar|omamobile-home)"'; then
      echo "[omamobile] the phone shell is on screen"; echo "LINUXDESK_WINDOW_UP ${W}x${H}"; exit 0
    fi
    sleep 0.5; j=$((j + 1))
  done
  echo "[omamobile] note: no shell layer after 90 s (see Hyprland's log /tmp/omamobile-hyprland.log)"
  echo "LINUXDESK_WINDOW_UP ${W}x${H}"
) &
WATCH=$!

CODE=0
# Tami 11:02 ("not launch Chrome automatically ... I want the actual OS to be fully active"): the browser starts only
# with LINUXDESK_OPEN_BROWSER=1 (launcher "Open Chrome at Start"; LINUXDESK_OMAMOBILE_CHROME=1 is the older name).
[ "${LINUXDESK_OMAMOBILE_CHROME:-0}" = 1 ] && LINUXDESK_OPEN_BROWSER=1
if [ "${LINUXDESK_OPEN_BROWSER:-0}" = 1 ] && [ -n "$BIN" ]; then
  WL=$(ls "$RT" 2>/dev/null | grep -E '^wayland-[0-9]+$' | head -n 1)
  say "LINUXDESK_OPEN_BROWSER=1: starting $BIN as well"
  # Flags from ~/.config/chrome-flags.conf (written above); as desk, like a drawer launch.
  # shellcheck disable=SC2086
  XDG_RUNTIME_DIR=$RT WAYLAND_DISPLAY=${WL:-wayland-1} $AS env HOME="$UH" "$BIN"
  CODE=$?
fi
# Hold: the phone shell IS the session (start-hyprland.sh's LINUXDESK_HYPR_HOLD block). It stays up until Hyprland
# exits or the app's Stop kills us (proot --kill-on-exit ends the rest). The app's "Restart browser" touches $TRIGGER:
# Chrome opens through the compositor (as desk, flags from ~/.config/chrome-flags.conf, like a drawer launch).
TRIGGER=${LINUXDESK_BROWSER_TRIGGER:-/tmp/linuxdesk-open-browser}
if { [ "${LINUXDESK_HYPR_HOLD:-1}" = 1 ] || [ "${LINUXDESK_OPEN_BROWSER:-0}" != 1 ]; } && kill -0 "$HPID" 2>/dev/null; then
  say "the phone shell holds the session (Hyprland pid $HPID); Chrome is in the app drawer"
  rm -f "$TRIGGER"
  while kill -0 "$HPID" 2>/dev/null; do
    if [ -e "$TRIGGER" ]; then
      rm -f "$TRIGGER"
      [ -n "$BIN" ] && hx "$BIN > /tmp/omamobile-chrome.log 2>&1" && say "opened $BIN on request ($TRIGGER)"
    fi
    sleep 2
  done
  say "Hyprland exited"
  CODE=0
fi
stop_ours
exit $CODE
