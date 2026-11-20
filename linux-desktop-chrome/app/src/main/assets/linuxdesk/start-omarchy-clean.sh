#!/bin/sh
# LinuxDesk "Omarchy (clean)" session launcher (notes/fresh/PLAN.md section 3). Runs INSIDE rootfs-omarchy-clean under
# PRoot. Same argv contract as start-omarchy.sh / start-sway.sh / start-browser.sh: all arguments are browser flags
# plus the URL (ChromiumFlags.kt). Exits with the browser's code; proot --kill-on-exit then ends the session.
#
# The REAL Omarchy session only (Tami 13:42, AC5CC9EB: "a separate option and NOT a fallback ... I can't understand what
# is actually running"): when rung A does not come up, the script prints one `LINUXDESK_ERROR <reason>` line (the app
# shows it as a pop-up) and exits 1; nothing else starts. LINUXDESK_ALLOW_FALLBACK=1 (default OFF) brings back the old
# ladder (each rung only if the one above did not come up within its timeout):
#   A. the REAL Omarchy session: Weston (x11 backend on lorie, pixman) -> Hyprland nested in it (the patched SHM
#      stack from `setup-omarchy-clean.sh hypr-stack`; stock Hyprland cannot allocate without /dev/dri) -> the Omarchy
#      shell (Quickshell, software Qt Quick) through Omarchy's own autostart.lua -> Chrome as a Wayland client.
#      Instead of `uwsm start` (needs a systemd user manager) Hyprland runs directly under dbus-run-session.
#   B. sway on lorie (pixman), with the real omarchy-* tools on PATH and the Omarchy theme colours (start-sway.sh's
#      proven path). Used when there is no patched stack, or Weston / Hyprland did not come up.
#   C. Chrome alone on lorie X11 (when neither compositor exists).
# LINUXDESK_CLEAN_DESKTOP=hyprland|sway|chrome forces a rung (for tests; a forced rung does not fall back either).
# Status markers for the app: LINUXDESK_STAGE_NOW <component> as each one starts ("Starting <component> …") and
# LINUXDESK_DESKTOP <name> once the compositor answers ("<name> running" after LINUXDESK_WINDOW_UP).
export DISPLAY="${DISPLAY:-:0}"
export LANG="${LANG:-C.UTF-8}"
U=desk
H=/home/desk
LDP=""
RT=/tmp/runtime-omarchy
export XDG_RUNTIME_DIR=$RT
mkdir -p "$RT" && chmod 700 "$RT" && chown 1000:1000 "$RT" 2>/dev/null
# proot --kill-on-exit ended the last session's compositors; their sockets are stale now.
rm -f "$RT"/wayland-* "$RT"/hypr/*/.socket*.sock "$RT"/bus 2>/dev/null
say() { echo "[omarchy] $*"; }
stage() { echo "LINUXDESK_STAGE_NOW $*"; }
last_line() { grep -v '^[[:space:]]*$' "$1" 2>/dev/null | tail -n 1 | cut -c1-160; }
FAIL=""
nohypr() { FAIL=$1; say "clean: $1"; return 1; }   # nohypr <why>: rung A did not come up
# Run as the Omarchy user (uid 1000) without PAM: setpriv (util-linux, in base). Under PRoot -0 the uid is faked, so
# this only matters to programs that check getuid() (Hyprland warns as root). Falls back to root when it fails.
as_user() {
  if command -v setpriv >/dev/null 2>&1 && setpriv --reuid=1000 --regid=1000 --clear-groups true 2>/dev/null; then
    setpriv --reuid=1000 --regid=1000 --clear-groups env HOME=$H USER=$U "$@"
  else
    env HOME=$H USER=$U "$@"
  fi
}

i=0
while [ ! -e /tmp/.X11-unix/X0 ] && [ $i -lt 40 ]; do sleep 0.5; i=$((i + 1)); done
[ -e /tmp/.X11-unix/X0 ] || say "warning: /tmp/.X11-unix/X0 not found, trying anyway"

BIN=""
for b in google-chrome-stable chromium; do command -v "$b" >/dev/null 2>&1 && { BIN=$b; break; }; done
[ -n "$BIN" ] || [ "${LINUXDESK_OPEN_BROWSER:-0}" != 1 ] || { say "no browser installed (setup-omarchy-clean.sh did not finish?)"; echo "LINUXDESK_ERROR Chrome is not installed in this rootfs (setup did not finish?)"; exit 127; }

# The app refreshes /opt/linuxdesk every Start: put the current shims in place (systemctl, uwsm, sudo, systemd-cat).
sh /opt/linuxdesk/clean/install-shims.sh || say "note: the PRoot shims could not be refreshed"
chmod +x /opt/linuxdesk/omarchy/*.sh /opt/linuxdesk/clean/*.sh 2>/dev/null || true

OMARCHY_PATH=$(cat /etc/linuxdesk/clean-omarchy-path 2>/dev/null || echo /usr/share/omarchy)
[ -d "$OMARCHY_PATH/shell" ] || OMARCHY_PATH=/usr/share/omarchy
export OMARCHY_PATH
GEO=$(xdotool getdisplaygeometry 2>/dev/null)
W=${GEO% *}; Hh=${GEO#* }
case "$W$Hh" in ''|*[!0-9]*) W=968; Hh=2179 ;; esac

# Browser flags: drop the ones that fight a compositor; keep the GPU guard of start-omarchy.sh.
n=$#; GL=0
while [ "$n" -gt 0 ]; do
  a=$1; shift; n=$((n - 1))
  case "$a" in
    --window-position=*|--window-size=*|--start-maximized|--ozone-platform=*) ;;
    --use-gl=*|--disable-gpu) GL=1; set -- "$@" "$a" ;;
    *) set -- "$@" "$a" ;;
  esac
done
[ "$GL" = 1 ] || { set -- --disable-gpu "$@"; say "GPU mode off: added --disable-gpu"; }

# One process tree for every rung: the window watcher prints LINUXDESK_WINDOW_UP once Chrome has a window.
watch_window() {  # watch_window <how>: hyprctl | swaymsg | xdotool
  (
    j=0
    while [ $j -lt 240 ]; do
      case "$1" in
        hyprctl) hyprctl -j clients 2>/dev/null | grep -qi '"class": *"[^"]*chrom' && break ;;
        swaymsg) swaymsg -t get_tree 2>/dev/null | grep -Eqi '"(app_id|class)": "[^"]*chrom' && break ;;
        *) xdotool search --onlyvisible --class 'chrom' >/dev/null 2>&1 && break ;;
      esac
      sleep 0.5; j=$((j + 1))
    done
    [ $j -lt 240 ] && echo "LINUXDESK_WINDOW_UP ${W}x${Hh}"
  ) &
  WATCH=$!
}
PIDS=""
cleanup() { [ -n "${WATCH:-}" ] && kill "$WATCH" 2>/dev/null; for p in $PIDS; do kill "$p" 2>/dev/null; done; }

# ---------------------------------------------------------------------------------------------------------------- A
HS=/opt/omarchy-android
# The patched Hyprland stack is fetched in the BACKGROUND at the first Starts (1.18 GB; memory-gated, nice 19,
# resumable), so this session comes up on sway now and the next Start gets the real Hyprland session. Opt out:
# touch /etc/linuxdesk/clean-no-hypr-stack. One run at a time (flock); proot --kill-on-exit ends it with the session.
if [ ! -f /etc/linuxdesk/clean-hypr-stack-v1 ] && [ ! -f /etc/linuxdesk/clean-no-hypr-stack ] \
   && [ -f /opt/linuxdesk/setup-omarchy-clean.sh ] && [ "${LINUXDESK_CLEAN_DESKTOP:-}" = "" ]; then
  say "clean: fetching the patched Hyprland stack in the background (log /etc/linuxdesk/clean-hypr-stack.log); the next Start uses it"
  ( exec 7>/etc/linuxdesk/clean-hypr-stack.lock
    if command -v flock >/dev/null 2>&1 && ! flock -n 7; then exit 0; fi
    nice -n 19 sh /opt/linuxdesk/setup-omarchy-clean.sh hypr-stack >>/etc/linuxdesk/clean-hypr-stack.log 2>&1
  ) &
fi
try_hyprland() {
  [ -x "$HS/hyprland/bin/Hyprland" ] || { nohypr "no patched Hyprland stack yet (setup-omarchy-clean.sh hypr-stack; it downloads in the background)"; return 1; }
  command -v weston >/dev/null 2>&1 || { nohypr "weston is not installed"; return 1; }
  # A library missing from the bundle's link set kills Hyprland at load (Tami 11:38: libxcb-errors.so.0 -> sway).
  # Install the ALARM package that owns it, then re-check; still missing -> say which, and use sway.
  hl=$HS/aquamarine/lib:$HS/compat/lib
  ml=$(LD_LIBRARY_PATH=$hl ldd "$HS/hyprland/bin/Hyprland" 2>/dev/null | awk '/not found/ {print $1}')
  if [ -n "$ml" ]; then
    say "clean: Hyprland is missing $(echo $ml); installing the packages that provide them"
    for l in $ml; do
      p=$(pacman -Fq "$l" 2>/dev/null | head -n 1); p=${p##*/}
      [ -n "$p" ] || case "$l" in libxcb-errors.so*) p=xcb-util-errors ;; esac
      [ -n "$p" ] && { pacman -S --noconfirm --needed "$p" >/dev/null 2>&1 && say "clean: installed $p (for $l)" || say "clean: could not install $p (for $l)"; }
    done
    ml=$(LD_LIBRARY_PATH=$hl ldd "$HS/hyprland/bin/Hyprland" 2>/dev/null | awk '/not found/ {print $1}')
    [ -z "$ml" ] || { nohypr "Hyprland still misses $(echo $ml)"; return 1; }
  fi
  # Memory: Weston + Hyprland + the shell + Chrome need headroom (Tami's rule: MemAvailable >= 2.5 GB).
  avail=$(awk '$1 == "MemAvailable:" { print $2 }' /proc/meminfo 2>/dev/null)
  if [ -n "$avail" ] && [ "$avail" -lt 2621440 ]; then nohypr "not enough memory: MemAvailable $((avail / 1024)) MB < 2560 MB"; return 1; fi

  # Weston: the Wayland host on lorie. pixman (lorie has no DRI3, so gl/zink cannot import client buffers: A2 23:28).
  rm -f "$RT/wayland-omarchy" "$RT/wayland-omarchy.lock"
  stage Weston
  # WINDOWED, never --fullscreen: on lorie (no _NET_WM_STATE_FULLSCREEN) weston hangs before creating its socket, so
  # this always timed out and fell back to sway (Tami 12:29: clean-weston.log ends at "Output ... color profile").
  # start-hyprland.sh learned this at 02:49; windowed at W x H it still fills lorie's screen. Poll 30 s, not 10.
  as_user env XDG_RUNTIME_DIR=$RT DISPLAY=$DISPLAY weston -B x11 --renderer=pixman --shell=kiosk \
    --socket=wayland-omarchy --width=$W --height=$Hh --scale=1 --idle-time=0 >/tmp/clean-weston.log 2>&1 &
  WPID=$!; PIDS="$PIDS $WPID"
  i=0; while [ ! -S "$RT/wayland-omarchy" ] && [ $i -lt 120 ]; do kill -0 $WPID 2>/dev/null || break; sleep 0.25; i=$((i + 1)); done
  if [ ! -S "$RT/wayland-omarchy" ]; then
    say "clean: weston did not come up; last lines of /tmp/clean-weston.log:"
    tail -n 12 /tmp/clean-weston.log | sed 's/^/[omarchy]   /'
    kill $WPID 2>/dev/null; PIDS=""
    nohypr "Weston did not come up: $(last_line /tmp/clean-weston.log)"; return 1
  fi
  say "clean: weston up (pid $WPID, ${W}x${Hh})"

  # Hyprland: the omarchy-android SHM env (A2 v2, proven 2026-11-19), the real Omarchy config from /etc/skel.
  # The bundle's own Mesa stage (lfdevs KGSL Freedreno) only when its libraries resolve in THIS rootfs. 12:11 crash:
  # its libgallium-26.3.0-devel needs libLLVM.so.22.1, the rootfs has llvm-libs 23 (libLLVM.so.23.1 only) -> EGL does
  # not load -> Hyprland abort()s in loadGLProc (CHyprOpenGLImpl) ~1 s after start. Otherwise the rootfs Mesa
  # (setup-gpu.sh's kgsl build), the one start-hyprland.sh has always used.
  M=$HS/mesa/root/usr
  MOK=0
  [ -d "$M/lib" ] && ! LD_LIBRARY_PATH=$M/lib:$HS/aquamarine/lib:$HS/compat/lib ldd "$M"/lib/libgallium-*.so 2>/dev/null \
    | grep -q 'not found' && MOK=1
  if [ "$MOK" = 1 ]; then say "clean: Mesa: the bundle's ($M)"; else
    say "clean: Mesa: the rootfs's (the bundle's misses: $(LD_LIBRARY_PATH=$M/lib ldd "$M"/lib/libgallium-*.so 2>/dev/null | awk '/not found/ {print $1}' | tr '\n' ' '))"
    [ -e /usr/lib/dri/kgsl_dri.so ] || say "clean: note: /usr/lib/dri has no kgsl_dri.so (setup-gpu.sh not run?); Hyprland may not get EGL"
  fi
  # Hyprland's crash reporter writes to ~/.cache/hyprland (12:05: "failed to mkdir() crash report directory").
  mkdir -p "$H/.cache/hyprland" 2>/dev/null && chown 1000:1000 "$H/.cache" "$H/.cache/hyprland" 2>/dev/null
  if [ "$MOK" = 1 ]; then
    ICD=$(ls "$M"/share/vulkan/icd.d/freedreno_icd*.json /usr/share/vulkan/icd.d/freedreno_icd*.json 2>/dev/null | head -n 1)
  else
    ICD=$(ls /usr/share/vulkan/icd.d/freedreno_icd*.json 2>/dev/null | head -n 1)
  fi
  HENV="HOME=$H USER=$U XDG_RUNTIME_DIR=$RT WAYLAND_DISPLAY=wayland-omarchy XDG_SESSION_TYPE=wayland"
  HENV="$HENV XDG_CURRENT_DESKTOP=Hyprland XDG_SESSION_DESKTOP=Hyprland LANG=$LANG OMARCHY_PATH=$OMARCHY_PATH"
  HENV="$HENV OMARCHY_SCALE=1 OMARCHY_OUTPUT_WIDTH=$W OMARCHY_OUTPUT_HEIGHT=$Hh NO_AT_BRIDGE=1 QT_ACCESSIBILITY=0"
  HENV="$HENV QT_QUICK_BACKEND=software QT_QPA_PLATFORM=wayland GDK_BACKEND=wayland XWAYLAND_NO_GLAMOR=1"
  HENV="$HENV MESA_LOADER_DRIVER_OVERRIDE=${LINUXDESK_HYPR_GL:-kgsl} FD_KGSL_ENABLE_DMABUF=1 ${ICD:+VK_ICD_FILENAMES=$ICD}"
  LDP=$HS/aquamarine/lib:$HS/compat/lib
  if [ "$MOK" = 1 ]; then
    LDP=$M/lib:$LDP
    HENV="$HENV LIBGL_DRIVERS_PATH=$M/lib/dri"
    [ -f "$M/share/glvnd/egl_vendor.d/50_mesa.json" ] && HENV="$HENV __EGL_VENDOR_LIBRARY_FILENAMES=$M/share/glvnd/egl_vendor.d/50_mesa.json"
  fi
  HENV="$HENV LD_LIBRARY_PATH=$LDP AQ_NO_KMS_REQUIREMENT=1 AQ_ANDROID_DMABUF_HEAP=0"
  HENV="$HENV AQ_ANDROID_DMABUF_GLFINISH=0 AQ_ANDROID_DMABUF_VULKAN=0 AQ_WAYLAND_REFRESH_MHZ=60000 AQ_NO_MODIFIERS=1"
  HENV="$HENV HYPRLAND_ANDROID_DMABUF=0 HYPRLAND_GL_DMABUF_EXPORT=0 HYPRLAND_SHM_DAMAGE=1 HYPRLAND_EGL_NO_MODIFIERS=1"
  HENV="$HENV HYPRLAND_EGL_SURFACELESS=1 HYPRLAND_NO_SD_NOTIFY=1 HYPRLAND_NO_SD_VARS=1 HYPRLAND_NO_RT=1"
  HENV="$HENV PATH=$HS/hyprland/bin:/usr/local/bin:/usr/bin:/bin XDG_DATA_DIRS=$HS/hyprland/share:/usr/local/share:/usr/share"
  CFG=$H/.config/hypr/hyprland.lua
  [ -f "$CFG" ] || { kill $WPID 2>/dev/null; PIDS=""; nohypr "$CFG missing (setup stage 7?)"; return 1; }
  stage Hyprland
  # shellcheck disable=SC2086  # HENV is a list of VAR=value words, intended
  as_user env -u DISPLAY $HENV dbus-run-session --config-file=$H/.config/omarchy/proot-session-bus.conf \
    "$HS/hyprland/bin/Hyprland" --config "$CFG" >/tmp/clean-hyprland.log 2>&1 &
  HPID=$!; PIDS="$PIDS $HPID"
  # Up = alive and hyprctl answering (max 15 s). hyprctl finds the instance through $XDG_RUNTIME_DIR/hypr.
  i=0; up=0
  while [ $i -lt 60 ]; do
    kill -0 $HPID 2>/dev/null || break
    if XDG_RUNTIME_DIR=$RT LD_LIBRARY_PATH=$LDP "$HS/hyprland/bin/hyprctl" version >/dev/null 2>&1; then up=1; break; fi
    sleep 0.25; i=$((i + 1))
  done
  if [ $up -ne 1 ]; then
    say "clean: Hyprland did not come up; last lines of /tmp/clean-hyprland.log:"
    tail -n 12 /tmp/clean-hyprland.log | sed 's/^/[omarchy]   /'
    kill $HPID $WPID 2>/dev/null; PIDS=""
    nohypr "Hyprland did not come up: $(last_line /tmp/clean-hyprland.log)"; return 1
  fi
  say "clean: Hyprland up (pid $HPID); the Omarchy shell starts from autostart.lua"
  echo "LINUXDESK_DESKTOP Hyprland"
  stage "Omarchy shell"
  # hyprctl / Wayland env for the rest of this script (the watcher and Chrome).
  export WAYLAND_DISPLAY=$(ls "$RT" | grep -E '^wayland-[0-9]+$' | head -n 1)
  hyprctl() { LD_LIBRARY_PATH=$LDP "$HS/hyprland/bin/hyprctl" "$@"; }
  # Tami 11:02 ("not launch Chrome automatically ... I want the actual OS to be fully active"): without
  # LINUXDESK_OPEN_BROWSER=1 the desktop is the session: RUNNING now, hold while Hyprland lives; the app's
  # "Restart browser" touches $TRIGGER and Chrome opens here with the same flags (start-hyprland.sh's contract).
  if [ "${LINUXDESK_OPEN_BROWSER:-0}" != 1 ]; then
    TRIGGER=${LINUXDESK_BROWSER_TRIGGER:-/tmp/linuxdesk-open-browser}
    rm -f "$TRIGGER"
    echo "LINUXDESK_WINDOW_UP ${W}x${Hh}"
    say "clean: desktop up without a browser (LINUXDESK_OPEN_BROWSER=0)"
    while kill -0 $HPID 2>/dev/null; do
      if [ -e "$TRIGGER" ]; then
        rm -f "$TRIGGER"
        if [ -n "$BIN" ]; then
          stage Chrome
          "$BIN" --ozone-platform=wayland "$@" > /tmp/linuxdesk-browser.log 2>&1 < /dev/null &
          say "opened $BIN on request ($TRIGGER)"
        else
          echo "LINUXDESK_ERROR Chrome is not installed in this rootfs"
        fi
      fi
      sleep 2
    done
    cleanup; say "Hyprland exited"
    # Not the app's Stop (that kills this script first): the desktop ended by itself, so the app says so (a pop-up).
    echo "LINUXDESK_ERROR Hyprland exited: $(last_line /tmp/clean-hyprland.log)"
    exit 0
  fi
  stage Chrome
  echo "[omarchy] exec $BIN --ozone-platform=wayland (Hyprland)"
  watch_window hyprctl
  # Chrome runs as root like on the other two distros (deskd's CDP pipe and flags unchanged), as a Wayland client.
  T0=$(date +%s)
  "$BIN" --ozone-platform=wayland "$@"
  CODE=$?
  # Died at once without ever showing a window (the watcher still runs): this compositor is no good for Chrome.
  # Tear it down (the caller reports it, or drops to sway with LINUXDESK_ALLOW_FALLBACK=1), never an empty Hyprland.
  if [ $CODE -ne 0 ] && [ $(( $(date +%s) - T0 )) -lt 10 ] && kill -0 "$WATCH" 2>/dev/null; then
    cleanup; PIDS=""; unset WAYLAND_DISPLAY
    nohypr "$BIN exited with code $CODE on Hyprland before any window"; return 1
  fi
  cleanup
  say "$BIN exited with code $CODE"
  exit $CODE
}

# ---------------------------------------------------------------------------------------------------------------- B
try_sway() {
  command -v sway >/dev/null 2>&1 || { FAIL="sway is not installed"; say "clean: sway missing"; return 1; }
  [ -f /opt/linuxdesk/start-sway.sh ] || { FAIL="/opt/linuxdesk/start-sway.sh is missing"; return 1; }
  # start-sway.sh is the proven sway path (pixman on lorie, waybar, fit + rotation, XWayland retry). Here it runs
  # with the real omarchy-* tools on PATH (they live in /usr/bin already) and our shims ahead of them. If sway does
  # not come up, start-sway.sh execs start-omarchy.sh; i3 is not installed in this rootfs, so that script starts
  # the browser alone on X11 (its "i3 ... not installed" branch) = rung C. Never a black screen.
  say "clean: starting the sway desktop (real omarchy tools on PATH)"
  exec /opt/linuxdesk/start-sway.sh "$@"
}

# ---------------------------------------------------------------------------------------------------------------- C
chrome_alone() {
  say "clean: Chrome alone on X11, no desktop (Stop and Start again to retry the desktop)"
  [ -n "$BIN" ] || { say "no browser installed either"; echo "LINUXDESK_ERROR Chrome is not installed in this rootfs"; exit 127; }
  echo "LINUXDESK_DESKTOP Chrome"
  stage Chrome
  watch_window xdotool
  "$BIN" --ozone-platform=x11 --start-maximized "$@"
  CODE=$?
  cleanup
  say "$BIN exited with code $CODE"
  exit $CODE
}

# fail <what>: the one line the app pops up, then exit nonzero (no other desktop is started).
fail() {
  say "$1: ${FAIL:-unknown reason}; no other desktop is started (LINUXDESK_ALLOW_FALLBACK=1 = the old fallback)"
  echo "LINUXDESK_ERROR $1: ${FAIL:-unknown reason}"
  exit 1
}
case "${LINUXDESK_CLEAN_DESKTOP:-}" in
  sway) try_sway "$@"; [ "${LINUXDESK_ALLOW_FALLBACK:-0}" = 1 ] && chrome_alone "$@"; fail "Sway did not start" ;;
  chrome) chrome_alone "$@" ;;
esac
try_hyprland "$@"
[ "${LINUXDESK_ALLOW_FALLBACK:-0}" = 1 ] || fail "Hyprland did not start"
say "clean: LINUXDESK_ALLOW_FALLBACK=1: the old ladder (sway, then Chrome alone)"
try_sway "$@"
chrome_alone "$@"
