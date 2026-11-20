#!/bin/sh
# LinuxDesk "Omarchy (Hyprland)": the REAL Omarchy desktop on lorie. Weston (x11 backend, windowed) is the Wayland host,
# the patched Hyprland from /opt/omarchy-android runs nested in it with the Omarchy shell, then Chrome is a Wayland
# client of Hyprland. Runs INSIDE the Omarchy (Arch Linux ARM) guest under PRoot. Same argv contract as start-sway.sh /
# start-omarchy.sh: all arguments are browser flags plus the URL (ChromiumFlags.kt). Exits with the browser's code;
# proot --kill-on-exit then ends Weston, Hyprland and the shell at Stop.
#
# The recipe is the one Tami saw working at 02:54 (notes/hyprland/bar-fix/PASS-2026-11-20-0254.jpg):
# notes/hyprland/bar-fix/a2-launch.sh (gate/stage/weston/hypr) + bar-live-test.sh (`up`), and bar-fix.md for why:
#   - memory gate: MemAvailable >= 2.5 GiB and SwapFree >= 1.2 GiB, else no attempt at all;
#   - the shell needs a tree with Qt 6.12's `Color` clash fixed (Color -> ShellColor). An Omarchy tree that already has
#     the upstream fix (#14553) is used as it is; otherwise the bundled shell-shellcolor.tar.gz is staged once as
#     /opt/omarchy-android/omarchy-qt612 (a symlink farm onto the stock tree plus a patched shell/, ~2 MB);
#   - the plugin index ($XDG_RUNTIME_DIR/omarchy-plugin-scan.txt) is written before Hyprland starts (OMARCHY_PROOT=1);
#   - Weston WINDOWED (--fullscreen hangs before making its socket; windowed it fills lorie's screen in <= 1 s);
#   - Hyprland: SHM present path, scale 1 (2179 px is prime), the omarchy-android env.
# Never a black screen: if any step fails, only the processes this script started are stopped and start-sway.sh runs
# with the same argv (it falls back to i3 itself when sway is missing).
# Test hooks (Mac dry run, notes/hyprland/hyprdesk-test.sh): LINUXDESK_DIR, LINUXDESK_HYPR_ROOT, LINUXDESK_HYPR_RT,
# LINUXDESK_MEMINFO, LINUXDESK_HYPR_WAIT (seconds per wait, default 30 / 20).
export DISPLAY="${DISPLAY:-:0}"
export LANG="${LANG:-C.UTF-8}"
LD=${LINUXDESK_DIR:-/opt/linuxdesk}
OA=${LINUXDESK_HYPR_ROOT:-/opt/omarchy-android}
RT=${LINUXDESK_HYPR_RT:-/tmp/runtime-hyprland}
MEMINFO=${LINUXDESK_MEMINFO:-/proc/meminfo}
FALLBACK=$LD/start-sway.sh
NH=$OA/home
HB=$OA/hyprland/bin
LDP=$OA/aquamarine/lib:$OA/compat/lib
WWAIT=${LINUXDESK_HYPR_WAIT:-30}   # Weston socket, seconds
HWAIT=${LINUXDESK_HYPR_WAIT:-20}   # hyprctl answering, seconds
say() { echo "[hyprland] $*"; }

PIDS=""
WATCH=""
# Stop only what this script started. Each one runs under setsid (its own process group), so the group goes too
# (dbus-run-session -> Hyprland -> omarchy-launch-shell -> quickshell). A hung Weston ignored TERM once (bar-fix 02:49).
stop_ours() {
  [ -n "$WATCH" ] && kill "$WATCH" 2>/dev/null
  for p in $PIDS; do kill -TERM -- "-$p" 2>/dev/null || kill -TERM "$p" 2>/dev/null; done
  [ -n "$PIDS" ] && sleep 1
  for p in $PIDS; do
    kill -0 "$p" 2>/dev/null && { kill -KILL -- "-$p" 2>/dev/null || kill -KILL "$p" 2>/dev/null; }
  done
  PIDS=""; WATCH=""
}
fallback() {  # fallback <why> "$@"
  why=$1; shift
  say "$why; starting the sway desktop instead"
  stop_ours
  exec "$FALLBACK" "$@"
}

# ---- 1. memory gate (a2-launch.sh gate) ------------------------------------------------------------------------------
ma=$(awk '$1 == "MemAvailable:" { print $2 }' "$MEMINFO" 2>/dev/null)
sf=$(awk '$1 == "SwapFree:" { print $2 }' "$MEMINFO" 2>/dev/null)
case "$ma$sf" in ''|*[!0-9]*) fallback "memory unknown (MemAvailable='$ma' SwapFree='$sf')" "$@" ;; esac
if [ "$ma" -lt 2621440 ] || [ "$sf" -lt 1258291 ]; then
  fallback "memory gate: MemAvailable $((ma / 1024)) MB (need 2560), SwapFree $((sf / 1024)) MB (need 1229)" "$@"
fi
say "memory gate OK (MemAvailable $((ma / 1024)) MB, SwapFree $((sf / 1024)) MB)"

# ---- 2. what must exist -----------------------------------------------------------------------------------------------
BIN=""
for b in google-chrome-stable chromium; do command -v "$b" >/dev/null 2>&1 && { BIN=$b; break; }; done
if [ -z "$BIN" ]; then
  say "no browser installed (setup-omarchy.sh did not finish?)"
  exit 127
fi
[ -x "$HB/Hyprland" ] || fallback "no patched Hyprland at $HB/Hyprland" "$@"
[ -x "$HB/hyprctl" ] || fallback "no hyprctl at $HB/hyprctl" "$@"
command -v weston >/dev/null 2>&1 || fallback "weston is not installed" "$@"
command -v dbus-run-session >/dev/null 2>&1 || fallback "dbus-run-session is not installed" "$@"
HCFG=$NH/.config/hypr/hyprland.lua
[ -f "$HCFG" ] || fallback "no Hyprland config $HCFG" "$@"

# ---- 3. the shell tree (a2-launch.sh stage) --------------------------------------------------------------------------
# Qt 6.12 safe = the ShellColor rename (#14553, our tarball) or no bare `Color.<x>` refs in code (omarchy 4.5.0 uses
# qualified `Commons.Color.<x>`, which Qt's singleton cannot shadow; clean/qt612-shell.sh's check, 2026-11-20).
# The Omarchy distro's dev checkout ($OA/omarchy, 4.0.0.alpha) has 250 bare refs, so it gets the tarball.
bare_refs() { grep -rnE --include='*.qml' --include='*.js' '(^|[^A-Za-z0-9_.])Color\.[A-Za-z_]' "$1" 2>/dev/null \
  | grep -vcE '^[^:]+:[0-9]+:[[:space:]]*//'; }
qt612_safe() {
  [ -f "$1/shell/shell.qml" ] || return 1
  grep -qs '^singleton ShellColor ' "$1/shell/Commons/qmldir" && return 0
  [ "$(bare_refs "$1/shell")" = 0 ]
}
OMSRC=""
for t in "$OA/omarchy-qt612" "$OA/omarchy"; do
  qt612_safe "$t" && { OMSRC=$t; break; }
done
if [ -z "$OMSRC" ]; then
  # The APK build (aapt) strips ".gz" from asset names and stores them decompressed, so the copy in /opt/linuxdesk is
  # shell-shellcolor.tar (0.2.33 looked only for .tar.gz). Accept either; the sha256 pins the .tar.gz bytes only.
  TGZ=$LD/hyprland/shell-shellcolor.tar.gz
  SUM=99c4f432d651961d87109ce1b4891b57b364c0f3155594154700d2702b8de9a9
  TARX=-xzf
  if [ ! -f "$TGZ" ] && [ -f "$LD/hyprland/shell-shellcolor.tar" ]; then TGZ=$LD/hyprland/shell-shellcolor.tar; TARX=-xf; SUM=""; fi
  if [ ! -d "$OA/omarchy" ]; then
    fallback "no Omarchy tree at $OA/omarchy" "$@"
  elif [ ! -f "$TGZ" ]; then
    fallback "no $TGZ to fix Qt 6.12's Color clash" "$@"
  elif [ -n "$SUM" ] && command -v sha256sum >/dev/null 2>&1 && [ "$(sha256sum "$TGZ" | cut -d' ' -f1)" != "$SUM" ]; then
    fallback "$TGZ has the wrong sha256" "$@"
  fi
  T=$OA/omarchy-qt612.tmp
  rm -rf "$T"; mkdir -p "$T" || fallback "cannot create $T" "$@"
  for e in "$OA"/omarchy/* "$OA"/omarchy/.[!.]*; do
    [ -e "$e" ] || continue; [ "${e##*/}" = shell ] && continue; ln -s "$e" "$T/${e##*/}"
  done
  if tar $TARX "$TGZ" -C "$T" && [ -f "$T/shell/Commons/ShellColor.qml" ] && [ ! -e "$T/shell/Commons/Color.qml" ] \
     && grep -q '^singleton ShellColor ' "$T/shell/Commons/qmldir" && mv "$T" "$OA/omarchy-qt612"; then
    OMSRC=$OA/omarchy-qt612
    say "staged the Qt 6.12 shell fix: $OMSRC ($(find "$OMSRC/shell" -type f | wc -l | tr -d ' ') files)"
  else
    rm -rf "$T"
    fallback "staging the Qt 6.12 shell fix failed" "$@"
  fi
fi
say "Omarchy tree: $OMSRC"

# ---- 3b. bar indicators (notes/indicators/REPORT.md, option B) -------------------------------------------------------
# The guest has no system D-Bus (no UPower/NetworkManager/BlueZ), so the app writes /tmp/linuxdesk-status/status.json
# (hw/StatusFeed.kt) and four patched QML files read it. They are copied over $OMSRC/shell/plugins once per REV, and only
# onto the exact files they were made from (BASE.sha256; the original is kept as <file>.ld-orig), so a different Omarchy
# tree is never overwritten. Also installs omarchy-proot-audio (the PRoot audio panel polls it every 2 s) when absent.
# LINUXDESK_INDICATORS=0 turns this off and puts the original QML back.
mkdir -p /tmp/linuxdesk-status 2>/dev/null && chmod 755 /tmp/linuxdesk-status 2>/dev/null
IND=$LD/hyprland/indicators
STAMP=$OMSRC/shell/.linuxdesk-indicators
if [ "${LINUXDESK_INDICATORS:-1}" = 1 ] && [ -f "$IND/REV" ] && [ -f "$IND/BASE.sha256" ]; then
  REV=$(cat "$IND/REV")
  if [ "$(cat "$STAMP" 2>/dev/null)" != "$REV" ]; then
    n=0; k=0
    while read -r sum rel; do
      [ -n "$rel" ] && [ -f "$IND/$rel" ] || continue
      dst=$OMSRC/shell/$rel; orig=$dst.ld-orig
      [ -f "$orig" ] || orig=$dst
      if [ "$(sha256sum "$orig" 2>/dev/null | cut -d' ' -f1)" = "$sum" ]; then
        [ "$orig" = "$dst" ] && cp -f "$dst" "$dst.ld-orig"
        cp -f "$IND/$rel" "$dst.ld-new" && mv -f "$dst.ld-new" "$dst" && n=$((n + 1))
      else k=$((k + 1)); fi
    done < "$IND/BASE.sha256"
    [ "$k" = 0 ] && printf '%s\n' "$REV" > "$STAMP"
    say "indicators: $n QML file(s) patched (REV ${REV%% *}), $k skipped (not the base tree)"
  fi
elif [ -f "$STAMP" ]; then
  for o in $(find "$OMSRC/shell/plugins" -name '*.ld-orig' 2>/dev/null); do mv -f "$o" "${o%.ld-orig}"; done
  rm -f "$STAMP"; say "indicators: off (LINUXDESK_INDICATORS=0), original QML restored"
fi
if [ "${LINUXDESK_INDICATORS:-1}" = 1 ]; then
  if [ -f "$IND/bin/omarchy-proot-audio" ] && [ ! -e "$OMSRC/bin/omarchy-proot-audio" ] && [ -d "$OMSRC/bin" ]; then
    cp -f "$IND/bin/omarchy-proot-audio" "$OMSRC/bin/omarchy-proot-audio" && chmod 755 "$OMSRC/bin/omarchy-proot-audio" \
      && say "installed omarchy-proot-audio"
  fi
fi

# ---- 4. runtime dir + the plugin index (a2-launch.sh plugin_scan) ---------------------------------------------------
mkdir -p "$RT" && chmod 700 "$RT" || fallback "cannot create $RT" "$@"
# proot --kill-on-exit ended the last session's compositors; their sockets are stale now.
rm -rf "$RT"/wayland-* "$RT"/hypr "$RT"/bus 2>/dev/null
# Run Weston and Hyprland as desk (uid 1000) like a2-launch.sh did; root when setpriv cannot switch (Chrome stays root
# like on every other desktop: deskd's CDP pipe is unchanged).
AS=""
if command -v setpriv >/dev/null 2>&1 && setpriv --reuid=1000 --regid=1000 --clear-groups true 2>/dev/null; then
  AS="setpriv --reuid=1000 --regid=1000 --clear-groups"
  chown -R 1000:1000 "$RT" 2>/dev/null
fi
out=$RT/omarchy-plugin-scan.txt; tmp=$out.new; root=$OMSRC/shell/plugins
: > "$tmp"
for m in "$root"/*/manifest.json "$root"/*/*/manifest.json "$root"/*/*/*.manifest.json; do
  [ -f "$m" ] || continue
  case "${m##*/}" in manifest.json) sub=${m%/manifest.json} ;; *) sub=${m%/*} ;; esac
  printf '===firstparty::%s===\n' "$sub" >> "$tmp"; cat "$m" >> "$tmp"; printf '\n=== EOM ===\n' >> "$tmp"
done
for sub in "$NH/.config/omarchy/plugins"/*/; do
  [ -f "$sub/manifest.json" ] || continue
  printf '===thirdparty::%s===\n' "${sub%/}" >> "$tmp"; cat "$sub/manifest.json" >> "$tmp"; printf '\n=== EOM ===\n' >> "$tmp"
done
mv -f "$tmp" "$out"
say "plugin index: $(grep -c '^===firstparty::' "$out") manifests"

# ---- 4b. missing session commands (sweep lane, notes/gap/SWEEP.md #1) ------------------------------------------------
# Omarchy's launchers run `uwsm-app -- xdg-terminal-exec …`, `chromium …`, `nautilus …` and `systemctl/systemd-run
# --user …`; none of those work in this guest, so Terminal-in-a-window tools (Setup > Default Agent, installers, TUIs),
# web apps and the file manager did nothing. A shim dir goes on Hyprland's PATH after Omarchy's bin, ahead of /usr/bin
# (the real systemd/uwsm packages stay untouched): omarchy/hypr-shim.sh (terminal/browser/file manager) + the clean/
# PRoot shims for uwsm, systemd-run/-inhibit/-cat and loginctl. LINUXDESK_HYPR_SHIMS=0 turns it off.
HSHIM=$LD/hypr-bin
if [ "${LINUXDESK_HYPR_SHIMS:-1}" = 1 ]; then
  mkdir -p "$HSHIM" 2>/dev/null
  shim() {  # shim <source file> <name...>
    s=$1; shift
    [ -f "$s" ] || return 0
    for n in "$@"; do cmp -s "$s" "$HSHIM/$n" 2>/dev/null || install -m 755 "$s" "$HSHIM/$n" 2>/dev/null; done
  }
  shim "$LD/omarchy/hypr-shim.sh" xdg-terminal-exec
  command -v chromium >/dev/null 2>&1 || shim "$LD/omarchy/hypr-shim.sh" chromium
  command -v nautilus >/dev/null 2>&1 || shim "$LD/omarchy/hypr-shim.sh" nautilus
  shim "$LD/clean/uwsm-shim.sh" uwsm uwsm-app
  # Not systemctl: its shim would run units' ExecStart, and `systemctl --user restart pipewire` would start PipeWire
  # next to the proven PulseAudio bridge. The real systemctl just fails here, which Omarchy's tools tolerate.
  shim "$LD/clean/systemd-run-shim.sh" systemd-run
  shim "$LD/clean/systemd-inhibit-shim.sh" systemd-inhibit
  shim "$LD/clean/loginctl-shim.sh" loginctl
  shim "$LD/clean/systemd-cat-shim.sh" systemd-cat
  say "session shims: $(ls "$HSHIM" 2>/dev/null | tr '\n' ' ')"
else
  rm -rf "$HSHIM"; HSHIM=/nonexistent
fi

# ---- 5. Weston, windowed, on lorie (a2-launch.sh weston; bar-live-test.sh up) ---------------------------------------
i=0
while [ ! -e /tmp/.X11-unix/X0 ] && [ $i -lt 40 ] && [ -z "${LINUXDESK_DIR:-}" ]; do sleep 0.5; i=$((i + 1)); done
GEO=$(xdotool getdisplaygeometry 2>/dev/null)
W=${GEO% *}; H=${GEO#* }
case "$W$H" in ''|*[!0-9]*) W=968; H=2179 ;; esac
ICD=$(ls /usr/share/vulkan/icd.d/freedreno_icd*.json 2>/dev/null | head -n 1)
# shellcheck disable=SC2086  # AS is a command prefix, intended to split
setsid $AS env XDG_RUNTIME_DIR="$RT" DISPLAY="$DISPLAY" MESA_LOADER_DRIVER_OVERRIDE=zink GALLIUM_DRIVER=zink \
  VK_ICD_FILENAMES="$ICD" weston -B x11 --renderer=gl --shell=kiosk --socket=wayland-omarchy \
  --width="$W" --height="$H" --scale=1 --idle-time=0 > /tmp/hyprland-weston.log 2>&1 < /dev/null &
WPID=$!; PIDS="$PIDS $WPID"
i=0
while [ ! -S "$RT/wayland-omarchy" ] && [ $i -lt $((WWAIT * 2)) ]; do
  kill -0 "$WPID" 2>/dev/null || break
  sleep 0.5; i=$((i + 1))
done
if [ ! -S "$RT/wayland-omarchy" ]; then
  say "weston: no socket after $((i / 2)) s; last lines of /tmp/hyprland-weston.log:"
  tail -n 10 /tmp/hyprland-weston.log 2>/dev/null | sed 's/^/[hyprland]   /'
  fallback "weston did not come up" "$@"
fi
say "weston up (pid $WPID, ${W}x${H}, after $((i / 2)) s)"

# ---- 6. Hyprland nested in Weston (a2-launch.sh hypr, v2 defaults) --------------------------------------------------
start_hyprland() {  # own positional params: "$@" of the script (the browser argv) stays untouched
  BUS=$NH/.config/omarchy/proot-session-bus.conf
  set -- dbus-run-session
  [ -f "$BUS" ] && set -- "$@" --config-file="$BUS"
  mkdir -p "$NH/.cache" "$NH/.local/share" "$NH/.local/state" 2>/dev/null
  VICD=$(ls /usr/share/vulkan/icd.d/freedreno_icd*.json 2>/dev/null | head -n 1)
  # shellcheck disable=SC2086
  ( cd "$NH" 2>/dev/null || cd /
    exec setsid $AS env -u DISPLAY -u WAYLAND_DISPLAY \
    HOME="$NH" XDG_CONFIG_HOME="$NH/.config" USER=omarchy XDG_RUNTIME_DIR="$RT" WAYLAND_DISPLAY=wayland-omarchy \
    XDG_SESSION_TYPE=wayland XDG_CURRENT_DESKTOP=Hyprland XDG_SESSION_DESKTOP=Hyprland LANG=C.UTF-8 LC_ALL=C.UTF-8 \
    OMARCHY_PROOT=1 OMARCHY_GPU_MODE=kgsl OMARCHY_UNLIMITED_PROCS=1 \
    OMARCHY_OUTPUT_WIDTH="$W" OMARCHY_OUTPUT_HEIGHT="$H" OMARCHY_REFRESH_MHZ=60000 OMARCHY_SCALE=1 \
    OMARCHY_KEYBOARD_LAYOUT=us NO_AT_BRIDGE=1 QT_ACCESSIBILITY=0 GTK_A11Y=none XWAYLAND_NO_GLAMOR=1 \
    MESA_LOADER_DRIVER_OVERRIDE=kgsl FD_KGSL_ENABLE_DMABUF=1 VK_ICD_FILENAMES="$VICD" \
    LD_LIBRARY_PATH="$LDP" \
    AQ_NO_KMS_REQUIREMENT=1 AQ_ANDROID_DMABUF_HEAP=0 AQ_ANDROID_DMABUF_GLFINISH=0 AQ_ANDROID_DMABUF_VULKAN=0 \
    AQ_WAYLAND_REFRESH_MHZ=60000 HYPRLAND_ANDROID_DMABUF=0 HYPRLAND_SHM_GLFINISH=0 HYPRLAND_SHM_DAMAGE=1 \
    HYPRLAND_SHM_PROFILE=0 HYPRLAND_GL_DMABUF_EXPORT=0 AQ_NO_MODIFIERS=1 HYPRLAND_EGL_NO_MODIFIERS=1 \
    HYPRLAND_EGL_SURFACELESS=1 HYPRLAND_NO_SD_NOTIFY=1 HYPRLAND_NO_SD_VARS=1 HYPRLAND_NO_RT=1 \
    QT_QPA_PLATFORM=wayland GDK_BACKEND=wayland SDL_VIDEODRIVER=wayland QT_QUICK_BACKEND= \
    OMARCHY_PATH="$OMSRC" \
    PATH="$HB:$OMSRC/bin:$HSHIM:/usr/local/bin:/usr/bin:/bin" \
    XDG_DATA_DIRS="$OA/hyprland/share:/usr/local/share:/usr/share" \
    "$@" "$HB/Hyprland" --config "$HCFG" ) > /tmp/hyprland.log 2>&1 < /dev/null &
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
  if kill -0 "$HPID" 2>/dev/null; then why="not answering hyprctl after $HWAIT s"; else why="exited"; fi
  say "Hyprland $why; last lines of /tmp/hyprland.log:"
  tail -n 12 /tmp/hyprland.log 2>/dev/null | sed 's/^/[hyprland]   /'
  fallback "Hyprland did not come up" "$@"
fi
# Hide the two warning bars Tami saw (06:13): we start Hyprland ourselves on purpose (no systemd, so no
# start-hyprland watchdog), and hyprland-guiutils may still be installing. The Lua config refuses `keyword`; `eval` works
# (verified live 06:18: both options read true and the bars were gone).
hyprctl eval 'hl.config({ misc = { disable_hyprland_guiutils_check = true, disable_watchdog_warning = true } })' >/dev/null 2>&1 \
  || say "note: could not hide the start-hyprland/guiutils warnings"
# The dock (Tami 06:19: "no dock"): nwg-dock in ALARM is the sway build ($SWAYSOCK fatal), so a second waybar with
# launchers + wlr/taskbar. Through the compositor (`dispatch exec` fails under the Lua config) so it gets Hyprland's env;
# verified live 06:44 (layer "hyprdock", 968x72 at the bottom). LINUXDESK_HYPR_DOCK=0 turns it off.
if [ "${LINUXDESK_HYPR_DOCK:-1}" = 1 ] && command -v waybar >/dev/null 2>&1 && [ -f /opt/linuxdesk/omarchy/hypr-dock.sh ]; then
  hyprctl eval 'hl.exec_cmd([[sh /opt/linuxdesk/omarchy/hypr-dock.sh > /tmp/hypr-dock.log 2>&1]])' >/dev/null 2>&1 \
    && say "dock started (waybar, /tmp/hypr-dock.log)" || say "note: could not start the dock"
fi
# Desktop icons (Tami 06:19: "no icons on the desktop"; sweep lane, notes/gap/SWEEP.md #4): a separate small Quickshell
# config (omarchy/desktop-icons/shell.qml) on the Bottom layer, ~/Desktop entries, one tap opens. Its own qs process,
# so a fault there cannot take Omarchy's bar down. NOT pcmanfm-qt --desktop (06:47 crash). LINUXDESK_HYPR_ICONS=0 = off.
if [ "${LINUXDESK_HYPR_ICONS:-1}" = 1 ] && command -v qs >/dev/null 2>&1 && [ -f "$LD/omarchy/desktop-icons/shell.qml" ]; then
  hyprctl eval "hl.exec_cmd([[qs -p $LD/omarchy/desktop-icons > /tmp/hypr-desktop-icons.log 2>&1]])" >/dev/null 2>&1 \
    && say "desktop icons started (/tmp/hypr-desktop-icons.log)" || say "note: could not start the desktop icons"
fi
# Background installs (sweep lane, notes/gap/SWEEP.md #2/#3): the Hyprland tools (jq, gum, wl-clipboard, grim, slurp,
# satty, ...; `setup-omarchy.sh hypr`), then Omarchy's apps (stage 8: thunar, mise-bin, ...) like start-sway.sh does.
# One job, one pacman at a time, nice'd, memory-gated inside setup-omarchy.sh; never blocks the desktop.
# LINUXDESK_HYPR_TOOLS=0 turns it off.
if [ "${LINUXDESK_HYPR_TOOLS:-1}" = 1 ] && [ -f "$LD/setup-omarchy.sh" ] && [ -z "${LINUXDESK_DIR:-}" ]; then
  HT=0; AP=0
  [ "$(cat /etc/linuxdesk/hypr-tools-v1 2>/dev/null)" = 1 ] || HT=1
  [ "$(cat /etc/linuxdesk/omarchy-stage-8 2>/dev/null)" = 1 ] || AP=1
  if [ "$HT" = 1 ] || [ "$AP" = 1 ]; then
    say "installing the Hyprland tools / Omarchy apps in the background (log: /etc/linuxdesk/omarchy-hypr.log)"
    (
      [ "$HT" = 1 ] && nice -n 10 sh "$LD/setup-omarchy.sh" hypr >> /etc/linuxdesk/omarchy-hypr.log 2>&1
      [ "$AP" = 1 ] && nice -n 10 sh "$LD/setup-omarchy.sh" apps >> /etc/linuxdesk/omarchy-apps.log 2>&1
    ) < /dev/null &
  fi
fi
WL=$(ls "$RT" 2>/dev/null | grep -E '^wayland-[0-9]+$' | head -n 1)
WL=${WL:-wayland-1}
say "Hyprland up (pid $HPID, $WL); the Omarchy shell starts from hyprland.lua"

# ---- 7. Chrome, a Wayland client of Hyprland (start-sway.sh's flags, GPU guard and XWayland retry) -------------------
# In a function, so the script's own "$@" stays the original argv for a late fallback to start-sway.sh.
run_chrome() {
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

# LINUXDESK_WINDOW_UP: the app shows RUNNING when the first browser window is one of Hyprland's clients.
(
  j=0
  while [ $j -lt 240 ]; do
    if hyprctl -j clients 2>/dev/null | grep -Eqi '"(class|initialClass)": *"[^"]*chrom'; then
      echo "LINUXDESK_WINDOW_UP ${W}x${H}"
      exit 0
    fi
    sleep 0.5; j=$((j + 1))
  done
) &
WATCH=$!

XBEFORE=$(ls /tmp/.X11-unix 2>/dev/null)
say "exec $BIN --ozone-platform=wayland $*"
T0=$(date +%s)
XDG_RUNTIME_DIR=$RT WAYLAND_DISPLAY=$WL "$BIN" --ozone-platform=wayland "$@"
CODE=$?
# Died at once without a window (the watcher still runs): try Hyprland's XWayland, if it made a display.
if [ $CODE -ne 0 ] && [ $(( $(date +%s) - T0 )) -lt 10 ] && kill -0 "$WATCH" 2>/dev/null; then
  xd=""
  for s in $(ls /tmp/.X11-unix 2>/dev/null); do
    echo "$XBEFORE" | grep -qx "$s" || { xd=":${s#X}"; break; }
  done
  if [ -n "$xd" ]; then
    say "$BIN exited with code $CODE on Wayland; retrying through XWayland ($xd)"
    T0=$(date +%s)
    DISPLAY=$xd XDG_RUNTIME_DIR=$RT "$BIN" --ozone-platform=x11 "$@"
    CODE=$?
  else
    say "$BIN exited with code $CODE on Wayland; no XWayland display"
  fi
  # Still never a window: this compositor is no good for Chrome. Tear it down rather than leave an empty desktop.
  if [ $CODE -ne 0 ] && [ $(( $(date +%s) - T0 )) -lt 10 ] && kill -0 "$WATCH" 2>/dev/null; then
    NOWINDOW=1
  fi
fi
}
NOWINDOW=0
run_chrome "$@"
[ "$NOWINDOW" = 1 ] && fallback "$BIN never showed a window on Hyprland (code $CODE)" "$@"
say "$BIN exited with code $CODE"
# Tami 06:49: "every time we closed Chrome it didn't hold the desktop by itself and tried to open the browser again".
# Closing Chrome used to end this script, so the app tore the session down and restarted it (browser first). Hyprland
# is the session now: it stays up until it exits itself (Super+Shift+E / logout) or the app's Stop kills us; the dock
# and the app launcher reopen Chrome. LINUXDESK_HYPR_HOLD=0 keeps the old Chrome-is-the-session behaviour.
if [ "${LINUXDESK_HYPR_HOLD:-1}" = 1 ] && kill -0 "$HPID" 2>/dev/null; then
  say "the desktop stays up without $BIN (Hyprland pid $HPID); reopen it from the dock"
  while kill -0 "$HPID" 2>/dev/null; do sleep 2; done
  say "Hyprland exited"
  CODE=0
fi
stop_ours
exit $CODE
