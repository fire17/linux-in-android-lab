#!/bin/sh
# LinuxDesk "Omarchy (clean)" first-run setup: the THIRD distro option (notes/fresh/PLAN.md). Runs INSIDE a fresh
# Arch Linux ARM aarch64 guest (files/rootfs-omarchy-clean) under PRoot (fake root). Never touches rootfs (Alpine) or
# rootfs-arch (the layered Omarchy).
#
# "Clean" means: install the REAL `omarchy` package (4.5.0 on 2026-11-20) and its own dependency tree from Omarchy's
# pacman repo, instead of our i3/sway approximations. Then shim the few things that cannot exist in an Android app
# (systemd, uwsm's systemd units, sudo's setuid, journald) in /usr/local/bin, which pacman never owns.
#
# Stages (LINUXDESK_STAGE n/8 lines drive the app's full-screen progress bar, OmarchyProgress.kt):
#   1 pacman for PRoot        DisableSandbox, CheckSpace off, kernel/firmware/docs never extracted, HTTPS mirrors
#   2 keyring                 pacman-key --init + archlinuxarm
#   3 full upgrade            -Rdd the kernel/firmware packages, then -Syu (Arch has no partial upgrades)
#   4 Omarchy repo            pinned omarchy-keyring, [omarchy] after the ALARM repos (see the stage for why)
#   5 the real Omarchy        pacman -S omarchy (+ the shims installed BEFORE it, so post-install scriptlets that call
#                             systemctl/sudo see our shims instead of failing)
#   6 the desktop around it   Chrome, walker, foot/alacritty, fonts, X keymaps for lorie, Weston (the Wayland host
#                             Hyprland nests in), sway+waybar (the never-black fallback), pulseaudio, dbus
#   7 Omarchy's user setup    the desk user (uid 1000) with /etc/skel (Omarchy's configs), the current theme,
#                             a session bus config, the Qt 6.12 shell check (clean/qt612-shell.sh)
#   8 checks + marker         hyprctl, Hyprland, quickshell, a browser, the X keymaps; writes omarchy-clean-v1
# Resumable: each finished stage writes /etc/linuxdesk/clean-stage-N (= SETUP_REV); Retry skips those.
#
# Optional, separate: `setup-omarchy-clean.sh hypr-stack` fetches the PATCHED Hyprland + Aquamarine (SHM allocator,
# no DRM needed) from the omarchy-android v0.1.1 bundle (pinned sha256, 1.18 GB) into /opt/omarchy-android. Stock
# Hyprland cannot allocate buffers without a DRM device, so without this stack start-omarchy-clean.sh uses the
# Sway fallback. Never run by the first-run setup (it is the heaviest job there is); see PLAN.md section 4.
# Memory gate (Tami's phone rule: ONE heavy job, MemAvailable >= 2.5 GB, SwapFree >= 1.2 GB): checked before each
# pacman stage; waits up to 20 min, then stops this run cleanly (exit 9) without losing finished stages.
set -eu

log() { echo "[omarchy] clean: $*"; }
SETUP_REV=1
TOTAL=8
MARK=/etc/linuxdesk
CUR=0
CUR_TITLE=""
stage() { CUR=$1; CUR_TITLE=$2; echo "LINUXDESK_STAGE $1/$TOTAL $2"; }
on_exit() {
  rc=$?
  if [ "$rc" -ne 0 ] && [ "$CUR" -gt 0 ]; then echo "LINUXDESK_STAGE_FAILED $CUR/$TOTAL $CUR_TITLE (exit $rc)"; fi
}
trap on_exit EXIT
stage_done() {
  if [ "$(cat "$MARK/clean-stage-$CUR" 2>/dev/null || true)" = "$SETUP_REV" ]; then log "stage $CUR already done; skipped"; return 0; fi
  return 1
}
mark_done() { mkdir -p "$MARK"; echo "$SETUP_REV" > "$MARK/clean-stage-$CUR"; }

# --- memory gate ---------------------------------------------------------------------------------------------------
MEMINFO=${LINUXDESK_MEMINFO:-/proc/meminfo}
GATE_MEM_KB=2621440    # 2.5 GiB MemAvailable
GATE_SWAP_KB=1258291   # 1.2 GiB SwapFree (skipped when the phone has no swap)
mem_kb() { awk -v k="$1:" '$1 == k { print $2; exit }' "$MEMINFO" 2>/dev/null; }
mem_gate() {
  i=0
  while :; do
    avail=$(mem_kb MemAvailable); swapf=$(mem_kb SwapFree); swapt=$(mem_kb SwapTotal)
    [ -n "$avail" ] || return 0                                    # unreadable meminfo never blocks
    if [ "$avail" -ge "$GATE_MEM_KB" ] && { [ "${swapt:-0}" -eq 0 ] || [ "${swapf:-0}" -ge "$GATE_SWAP_KB" ]; }; then
      return 0
    fi
    if [ "$i" -ge 40 ]; then
      log "memory still low after 20 min (MemAvailable $((avail / 1024)) MB, SwapFree $(( ${swapf:-0} / 1024 )) MB; need 2560 / 1228). Close other apps, then Retry: finished stages are kept."
      exit 9
    fi
    [ $((i % 4)) -eq 0 ] && log "waiting for memory (MemAvailable $((avail / 1024)) MB, SwapFree $(( ${swapf:-0} / 1024 )) MB; need 2560 / 1228)"
    sleep 30; i=$((i + 1))
  done
}

SYNCED=0
sync_db() {
  [ "$SYNCED" = 1 ] && return 0
  attempt=1
  until pacman -Syy --noconfirm; do
    if [ "$attempt" -ge 3 ]; then log "pacman -Syy failed 3 times (no network?)"; exit 2; fi
    attempt=$((attempt + 1)); log "retrying pacman -Syy in 5s"; sleep 5
  done
  SYNCED=1
}
# pacman -S that tolerates the PRoot hook failures (systemd-tmpfiles, sysusers, udev, dbus reload): the caller
# verifies with pacman -Q / command -v afterwards. One retry with a fresh list (stale-list 404s, 2026-11-19).
pac() {
  pacman -S --needed --noconfirm "$@" && return 0
  log "pacman reported errors; refreshing the list and retrying once"
  pacman -Syy --noconfirm >/dev/null 2>&1 || true
  pacman -S --needed --noconfirm "$@" || log "pacman reported errors again (often harmless hook failures under PRoot); verifying"
}
need_pkgs() {  # need_pkgs <exit code> pkg...: fail the stage if any package is not installed
  code=$1; shift; missing=""
  for p in "$@"; do pacman -Q "$p" >/dev/null 2>&1 || missing="$missing $p"; done
  [ -z "$missing" ] || { log "not installed:$missing"; exit "$code"; }
}

CONF=/etc/pacman.conf

# --- optional: the patched Hyprland stack (`setup-omarchy-clean.sh hypr-stack`) ----------------------------------------
# Stock Hyprland (0.57.2 from ALARM) allocates every buffer through GBM on a DRM device; an app has no /dev/dri, so it
# exits with "no allocator available" (notes/hyprland-research.md section 1). BlackFireAlex/omarchy-android (MIT) ships
# Hyprland 0.56 + Aquamarine 0.14 patched with an SHM allocator; that build composited real windows on Tami's Fold6
# inside LinuxDesk on 2026-11-19 (notes/progress/hypr-a2.log 23:33, notes/hypr-a2-foot.png).
# This fetches ONLY that stack from the v0.1.1 bundle (sha256 pinned below, verified on the phone 2026-11-19) into
# /opt/omarchy-android, never over /usr: opt/omarchy-android/{hyprland,aquamarine,mesa} plus the bundle-exact libhypr*
# into /opt/omarchy-android/compat/lib. Heavy (1.18 GB download, ~1.4 GB free during the extract): memory-gated,
# nice 19, single-thread xz, resumable download (curl -C -), and the bundle is deleted afterwards.
HS_DIR=/opt/omarchy-android
HS_MARK=$MARK/clean-hypr-stack-v1
HS_URL=https://github.com/BlackFireAlex/omarchy-android/releases/download/v0.1.1/omarchy-android-aarch64-0.1.1.bundle.tar
HS_SHA=7e9f1cd67533bc0d3988b5cb3831aef52f1527dd90391d9d868ed9345021cdb2
HS_SIZE=1181941760
hypr_stack() {
  if [ -f "$HS_MARK" ] && [ -x "$HS_DIR/hyprland/bin/Hyprland" ]; then log "hypr-stack: already installed"; return 0; fi
  mem_gate
  dl=/var/cache/linuxdesk; mkdir -p "$dl" "$HS_DIR/compat/lib"
  B=$dl/omarchy-android-aarch64-0.1.1.bundle.tar
  if [ "$(stat -c %s "$B" 2>/dev/null || echo 0)" != "$HS_SIZE" ]; then
    log "hypr-stack: downloading the omarchy-android v0.1.1 bundle (1.18 GB, resumable)"
    curl -fL --retry 3 -C - -o "$B" "$HS_URL" || { log "hypr-stack: download failed (Retry resumes it)"; return 1; }
  fi
  echo "$HS_SHA  $B" | sha256sum -c - >/dev/null || { log "hypr-stack: bundle sha256 mismatch; deleting it"; rm -f "$B"; return 1; }
  log "hypr-stack: bundle sha256 OK; extracting the Hyprland stack only (nice 19, single-thread xz)"
  mem_gate
  T=$dl/hs.tmp; rm -rf "$T"; mkdir -p "$T"
  # The bundle is a plain tar holding rootfs.tar.xz (the full guest). Stream it; extract only what we need.
  if ! tar -xOf "$B" --wildcards '*rootfs.tar.xz' | nice -n 19 xz -dc -T1 \
      | tar -x -C "$T" --wildcards './opt/omarchy-android/hyprland/*' './opt/omarchy-android/aquamarine/*' \
          './opt/omarchy-android/mesa/*' './usr/lib/libhypr*' 2>/dev/null; then
    [ -x "$T/opt/omarchy-android/hyprland/bin/Hyprland" ] || { log "hypr-stack: extract failed"; rm -rf "$T"; return 1; }
  fi
  [ -x "$T/opt/omarchy-android/hyprland/bin/Hyprland" ] || { log "hypr-stack: Hyprland not in the bundle"; rm -rf "$T"; return 1; }
  rm -rf "$HS_DIR/hyprland" "$HS_DIR/aquamarine" "$HS_DIR/mesa"
  mv "$T/opt/omarchy-android/hyprland" "$T/opt/omarchy-android/aquamarine" "$HS_DIR/"
  [ -d "$T/opt/omarchy-android/mesa" ] && mv "$T/opt/omarchy-android/mesa" "$HS_DIR/"
  cp -a "$T"/usr/lib/libhypr* "$HS_DIR/compat/lib/" 2>/dev/null || true
  rm -rf "$T" "$B"
  # The rest of its link set (lua5.5, muparser, re2, tomlplusplus, libzip, hyprwire, pugixml) is in ALARM; installed
  # normally. ldd decides; any "not found" is logged, not fatal (start-omarchy-clean.sh then falls back to sway).
  pac lua muparser re2 tomlplusplus libzip hyprwire pugixml hyprutils hyprlang hyprcursor >/dev/null 2>&1 || true
  nf=$(LD_LIBRARY_PATH=$HS_DIR/aquamarine/lib:$HS_DIR/compat/lib ldd "$HS_DIR/hyprland/bin/Hyprland" 2>/dev/null | grep -c 'not found' || true)
  [ "${nf:-0}" = 0 ] || log "hypr-stack: note: $nf libraries not found by ldd (the session falls back to sway)"
  echo "omarchy-android v0.1.1 sha256 $HS_SHA ldd_not_found=${nf:-0}" > "$HS_MARK"
  log "hypr-stack: installed in $HS_DIR ($(du -sh "$HS_DIR" 2>/dev/null | cut -f1))"
}
# A5/A8 pacman.conf helpers (pacman-tune.sh, shipped next to this script). A missing helper never fails setup.
PT="$(dirname "$0")/pacman-tune.sh"; [ -f "$PT" ] || PT=/opt/linuxdesk/pacman-tune.sh
if [ -f "$PT" ]; then . "$PT"; else pacman_tune() { :; }; ignore_pkgs() { :; }; unignore_pkgs() { :; }; fi
# A5: ParallelDownloads = 8, every run (idempotent), so rootfs that finished stage 1 earlier get it as well.
pacman_tune "$CONF"
if [ "${1:-}" = hypr-stack ]; then hypr_stack; exit $?; fi

log "$(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-Arch Linux ARM}") on $(uname -m); setup rev $SETUP_REV"

# 1. pacman under PRoot ------------------------------------------------------------------------------------------
stage 1 "Configuring pacman for PRoot"
if ! stage_done; then
  # Same edits as setup-omarchy.sh stage 1 (proven on the phone 2026-11-19): no download sandbox (setuid to alpm and
  # Landlock do not work under PRoot), no CheckSpace (no real mount points), never extract firmware/modules/docs.
  awk '
    /^\[options\]/ { print; if (!seen++) { print "DisableSandbox"; print "NoExtract = usr/lib/firmware/* usr/lib/modules/* boot/* usr/share/doc/* usr/share/gtk-doc/* usr/share/man/*" }; next }
    /^DisableSandbox/ || /^NoExtract = usr\/lib\/firmware/ { next }
    /^DownloadUser/ || /^CheckSpace/ { print "#" $0; next }
    { print }
  ' "$CONF" > "$CONF.new" && mv "$CONF.new" "$CONF"
  cat > /etc/pacman.d/mirrorlist <<'EOF'
Server = https://fl.us.mirror.archlinuxarm.org/$arch/$repo
Server = https://ca.us.mirror.archlinuxarm.org/$arch/$repo
Server = https://de3.mirror.archlinuxarm.org/$arch/$repo
Server = http://mirror.archlinuxarm.org/$arch/$repo
EOF
  mark_done
fi

# 2. keyring -----------------------------------------------------------------------------------------------------
stage 2 "Initialising the pacman keyring"
if ! stage_done; then
  if [ ! -s /etc/pacman.d/gnupg/pubring.gpg ] && [ ! -s /etc/pacman.d/gnupg/pubring.kbx ]; then
    log "pacman-key --init (can take a minute under PRoot)"
    pacman-key --init || { log "pacman-key --init failed; retrying with a fresh keyring"; rm -rf /etc/pacman.d/gnupg; pacman-key --init; }
  fi
  pacman-key --populate archlinuxarm >/dev/null 2>&1 || log "note: populate archlinuxarm reported errors; continuing"
  gpgconf --kill gpg-agent 2>/dev/null || true
  mark_done
fi

# 3. full upgrade ------------------------------------------------------------------------------------------------
stage 3 "Updating Arch Linux ARM (full upgrade)"
if ! stage_done; then
  mem_gate
  sync_db
  # shellcheck disable=SC2046  # one package name per word, intended
  pacman -Rdd --noconfirm linux-aarch64 $(pacman -Qq | grep '^linux-firmware') mkinitcpio mkinitcpio-busybox 2>/dev/null \
    || log "note: kernel/firmware packages were not removed (already gone?)"
  pacman -Su --noconfirm || { log "pacman -Su failed; refreshing and retrying once"; pacman -Syy --noconfirm; pacman -Su --noconfirm; } \
    || log "pacman -Su reported errors (often harmless hook failures under PRoot); verifying later"
  mark_done
fi

# 4. Omarchy repo, FIRST in the order --------------------------------------------------------------------------------
stage 4 "Adding the Omarchy repository"
if ! stage_done; then
  # Trust without a keyserver: omarchy-keyring by its PINNED sha256 (from omarchy.db, checked 2026-11-19; still the
  # current keyring on 2026-11-20), then the expected fingerprint. The [omarchy] repo then needs signed packages.
  OMARCHY_FPR=40DFB630FF42BCFFB047046CF0134EE680CAC571
  if ! pacman-key --list-keys "$OMARCHY_FPR" >/dev/null 2>&1; then
    KR=omarchy-keyring-20251027-1-any.pkg.tar.zst
    KR_SHA=cce48c07cfc2b97e198df1bc81705db2921685032fe39f18b4ddfd84264b5967
    curl -fsSL --retry 3 -o "/tmp/$KR" "https://pkgs.omarchy.org/stable/aarch64/$KR"
    echo "$KR_SHA  /tmp/$KR" | sha256sum -c - || { log "omarchy-keyring sha256 mismatch"; exit 6; }
    pacman -U --noconfirm "/tmp/$KR"
    rm -f "/tmp/$KR"
    pacman-key --populate omarchy >/dev/null 2>&1 || true
    pacman-key --list-keys "$OMARCHY_FPR" >/dev/null 2>&1 || { log "Omarchy signing key $OMARCHY_FPR not in the keyring"; exit 6; }
  fi
  gpgconf --kill gpg-agent 2>/dev/null || true
  # [omarchy] goes AFTER the ALARM repos. Checked 2026-11-20 against the live dbs: Omarchy's own hyprland 0.56.2-7
  # needs libhyprgraphics.so=4, which no aarch64 repo provides any more (ALARM moved to hyprgraphics 0.6 = .so.5), so
  # "[omarchy] first" cannot resolve. ALARM first gives hyprland 0.57.2 + aquamarine 0.16 + quickshell 0.3.2 (0
  # unresolved deps); every omarchy-* package, walker and google-chrome still come from [omarchy].
  if ! grep -q '^\[omarchy\]' "$CONF"; then
    printf '\n[omarchy]\nServer = https://pkgs.omarchy.org/stable/$arch\n' >> "$CONF"
  fi
  grep -q '^\[omarchy\]' "$CONF" || { log "could not add [omarchy] to $CONF"; exit 6; }
  SYNCED=0
  sync_db
  mark_done
fi

# 5. the REAL omarchy package ----------------------------------------------------------------------------------------
# Measured 2026-11-20 from the live dbs (ALARM-first order, minus the base rootfs): omarchy pulls 235 new packages,
# 448 MiB download, 1.43 GiB installed (hyprland, quickshell, qt6, llvm-libs, uwsm, sddm, networkmanager, plymouth...).
stage 5 "Installing Omarchy (the real package)"
if ! stage_done; then
  mem_gate
  sync_db
  # Shims first, so scriptlets that call systemctl / sudo / systemd-cat during the install see them.
  sh /opt/linuxdesk/clean/install-shims.sh || { log "installing the PRoot shims failed"; exit 7; }
  pac omarchy
  need_pkgs 7 omarchy omarchy-settings hyprland quickshell
  [ -d /usr/share/omarchy/shell ] || { log "/usr/share/omarchy/shell missing after install"; exit 7; }
  # Some systemd units shipped by the dependency tree are enabled by scriptlets; under PRoot none can run and the
  # shim answers for them, so nothing else to do here. Never start sddm / NetworkManager / plymouth (see PLAN.md).
  pacman -Scc --noconfirm >/dev/null 2>&1 || true
  log "installed: $(pacman -Q omarchy hyprland quickshell 2>/dev/null | tr '\n' ' ')"
  mark_done
fi

# 6. the desktop around it --------------------------------------------------------------------------------------------
# google-chrome         Omarchy's browser on aarch64 (its omarchy-base list has chromium; Chrome is in [omarchy])
# walker                Omarchy's launcher (elephant, its data service, is NOT in any aarch64 repo: degrade, PLAN.md)
# foot alacritty        terminals (foot = Omarchy's Wayland terminal; alacritty for the X11 fallback)
# weston                the Wayland host that Hyprland nests in on lorie (x11 backend, the A2 path)
# sway waybar mako ...  the never-black fallback desktop, with the real omarchy-* tools on PATH
# xkeyboard-config ...  lorie compiles its keymap and core fonts from the rootfs (same as setup-omarchy.sh)
# xdotool xorg-*        window geometry + the LINUXDESK_WINDOW_UP watcher; deskd input
# python                deskd (genie) and sway-theme.py; dbus for the session bus; pulseaudio for sound
DESK_PKGS="google-chrome walker foot alacritty weston sway waybar mako swaybg wofi xorg-xwayland"
DESK_PKGS="$DESK_PKGS xkeyboard-config xorg-fonts-misc xorg-fonts-alias-misc xorg-mkfontscale xdotool xorg-xwininfo xorg-xprop"
DESK_PKGS="$DESK_PKGS xorg-xrandr noto-fonts noto-fonts-emoji ttf-dejavu fontconfig python dbus pulseaudio libpulse"
stage 6 "Installing Chrome, Weston and the fallback desktop"
if ! stage_done; then
  mem_gate
  sync_db
  # shellcheck disable=SC2086  # one package name per word, intended
  pac $DESK_PKGS
  if ! command -v google-chrome-stable >/dev/null 2>&1 && ! command -v chromium >/dev/null 2>&1; then
    log "google-chrome not installed; trying chromium"
    pac chromium
  fi
  need_pkgs 8 weston xkeyboard-config xdotool
  [ -d /usr/share/X11/xkb ] || { log "/usr/share/X11/xkb missing after install"; exit 8; }
  # lorie wants core fonts at <rootfs>/usr/share/fonts/X11/{misc,TTF,...} (X aborts without "fixed" and "cursor").
  mkdir -p /usr/share/fonts/X11
  for d in misc TTF OTF 75dpi 100dpi Type1; do
    if [ -d "/usr/share/fonts/$d" ] && [ ! -e "/usr/share/fonts/X11/$d" ]; then ln -s "../$d" "/usr/share/fonts/X11/$d"; fi
  done
  [ -f /usr/share/fonts/misc/fonts.dir ] || mkfontdir /usr/share/fonts/misc 2>/dev/null || true
  fc-cache -f >/dev/null 2>&1 || true
  pacman -Scc --noconfirm >/dev/null 2>&1 || true
  mark_done
fi

# 7. Omarchy's user setup ---------------------------------------------------------------------------------------------
# Omarchy is a per-user desktop: its configs come from /etc/skel (omarchy-settings) and its state lives in
# ~/.local/state/omarchy. The session runs as `desk` (uid 1000; the same user deskd's as:"user" uses).
stage 7 "Setting up the Omarchy user"
if ! stage_done; then
  if ! id -u desk >/dev/null 2>&1; then
    if id -u alarm >/dev/null 2>&1; then
      usermod -l desk -d /home/desk -m alarm 2>/dev/null || usermod -l desk -d /home/desk alarm
      groupmod -n desk alarm 2>/dev/null || true
    else
      useradd -m -u 1000 -U -d /home/desk -s /bin/bash desk
    fi
  fi
  H=/home/desk
  mkdir -p "$H"
  # Omarchy's configs (hypr/*.lua, foot, alacritty, waybar-less: the shell draws the bar), never overwriting a file
  # that is already there (a Retry, or the user's own edits).
  if [ -d /etc/skel ]; then
    (cd /etc/skel && find . -type d) | while read -r d; do mkdir -p "$H/$d"; done
    (cd /etc/skel && find . ! -type d) | while read -r f; do [ -e "$H/$f" ] || cp -P "/etc/skel/$f" "$H/$f"; done
  fi
  # The theme symlinks omarchy-theme-set would make (it needs a running shell and hyprctl, so not at setup time).
  ST=$H/.local/state/omarchy/current
  mkdir -p "$ST"
  if [ ! -e "$ST/theme" ] && [ -d /usr/share/omarchy/themes/tokyo-night ]; then
    ln -s /usr/share/omarchy/themes/tokyo-night "$ST/theme"
    echo tokyo-night > "$ST/theme.name"
  fi
  if [ ! -e "$ST/background" ]; then
    bg=$(ls /usr/share/omarchy/themes/tokyo-night/backgrounds/* 2>/dev/null | head -n 1 || true)
    [ -n "$bg" ] && ln -s "$bg" "$ST/background"
  fi
  # LinuxDesk's own Hyprland overrides, loaded by the user config (hypr/hyprland.lua requires hypr.monitors etc.):
  # one output at scale 1 (2179 px is prime, so only scale 1 is clean; notes/hyprland A2), no hardware cursor.
  mkdir -p "$H/.config/hypr"
  cat > "$H/.config/hypr/monitors.lua" <<'EOF'
-- LinuxDesk (Omarchy clean): one nested output, the size of the Weston window, at scale 1.
-- 2179 px (the Fold6 X height) is prime, so any other scale is rejected by Hyprland (notes/hyprland, A2 23:31).
hl.monitor({ output = "", mode = "preferred", position = "auto", scale = tonumber(os.getenv("OMARCHY_SCALE") or "1") or 1 })
EOF
  # A session bus config that works under PRoot (no setuid helper, no systemd activation).
  mkdir -p "$H/.config/omarchy"
  cat > "$H/.config/omarchy/proot-session-bus.conf" <<'EOF'
<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
<busconfig>
  <type>session</type>
  <keep_umask/>
  <listen>unix:tmpdir=/tmp</listen>
  <standard_session_servicedirs/>
  <policy context="default">
    <allow send_destination="*" eavesdrop="true"/>
    <allow eavesdrop="true"/>
    <allow own="*"/>
  </policy>
</busconfig>
EOF
  chown -R 1000:1000 "$H" 2>/dev/null || true
  # The Qt 6.12 Color check (bar-fix.md cause 1). omarchy 4.5.0 is already safe; the script decides and logs.
  OP=$(sh /opt/linuxdesk/clean/qt612-shell.sh /usr/share/omarchy /var/lib/linuxdesk/omarchy-qt612 || echo /usr/share/omarchy)
  echo "$OP" > "$MARK/clean-omarchy-path"
  log "OMARCHY_PATH = $OP"
  mark_done
fi

# 8. checks + marker --------------------------------------------------------------------------------------------------
# Never skipped: quick, and it writes the marker the app checks (Distro.OMARCHY_CLEAN.packagesMarker).
stage 8 "Checking the Omarchy install"
BIN=""
for b in google-chrome-stable chromium; do command -v "$b" >/dev/null 2>&1 && { BIN=$b; break; }; done
[ -n "$BIN" ] || { log "no browser binary (google-chrome-stable / chromium)"; exit 3; }
for b in Hyprland hyprctl quickshell weston; do
  command -v "$b" >/dev/null 2>&1 || { log "$b missing"; exit 5; }
done
command -v sway >/dev/null 2>&1 || log "note: sway missing; the fallback is Chrome alone on Weston/X"
sh /opt/linuxdesk/clean/install-shims.sh || { log "the PRoot shims are not in place"; exit 7; }
command -v elephant >/dev/null 2>&1 || log "note: elephant (walker's data service) is not packaged for aarch64; the launcher falls back to the Omarchy menu"
mkdir -p "$MARK"
# The sway fallback is start-sway.sh (shared with the layered Omarchy). It starts setup-omarchy.sh's `sway` / `apps`
# installs in the background while their markers are missing; here sway came from stage 6 and Omarchy's apps are the
# real package's business, so mark both as handled (delete omarchy-stage-8 to get the layered app set too).
echo 1 > "$MARK/sway-v1"
[ -f "$MARK/omarchy-stage-8" ] || echo 1 > "$MARK/omarchy-stage-8"
command -v pulseaudio >/dev/null 2>&1 && echo "pulseaudio libpulse" > "$MARK/audio-v1" || log "note: pulseaudio missing; the session stays silent"
echo "omarchy=$(pacman -Q omarchy 2>/dev/null | cut -d' ' -f2) hyprland=$(pacman -Q hyprland 2>/dev/null | cut -d' ' -f2) $BIN" > "$MARK/omarchy-clean-v1"
mark_done
log "done: $(cat "$MARK/omarchy-clean-v1")"
