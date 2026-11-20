#!/bin/sh
# LinuxDesk "Omarchy Mobile" first-run setup: the FOURTH distro option (notes/omamobile/REPORT.md section 3). Runs INSIDE
# a fresh Arch Linux ARM aarch64 guest (files/rootfs-omamobile) under PRoot (fake root) on the phone, or as real root in
# a chroot in CI (.github/workflows/omamobile-rootfs.yml builds the prebuilt rootfs with this very script).
#
# Normally the phone never runs this: the app downloads the PREBUILT rootfs (OmamobileRootfs.kt, 8 parallel ranges) whose
# tree already holds the marker /etc/linuxdesk/omamobile-v1. This is the fallback when that release asset is missing.
#
# Omarchy Mobile (github.com/derekross/omarchy-mobile, MIT; a community port) = Omarchy 4.5 + its signed [omamobile]
# repo: a phone shell (omamobile-shell: bar, quick settings, launcher, home screens, switcher, back edge, keyboard
# plugins), squeekboard, Hyprland phone rules (hypr/phone.lua) and 31 touch patches to Omarchy. Here it runs as a
# PHONE-SHELL PROFILE over our patched Hyprland 0.56.1 (its own Hyprland 0.57 / Mesa / kernel / device packages are
# never installed: no DRM, no modem, no sensors in an app).
#
# Stages (LINUXDESK_STAGE n/7 lines drive the app's full-screen progress bar, OmarchyProgress.kt):
#   1 pacman for PRoot        DisableSandbox, CheckSpace off, kernel/firmware/docs never extracted, HTTPS mirrors
#   2 keyring                 pacman-key --init + archlinuxarm
#   3 full upgrade            -Rdd the kernel/firmware packages, then -Su
#   4 repositories            pinned omarchy-keyring + omamobile-keyring (sha256 + fingerprints); [omarchy] after ALARM,
#                             [omamobile] LAST; the patched-Hyprland bundle starts downloading IN PARALLEL here
#   5 packages                one pacman transaction: omarchy + omamobile-shell/-omarchy/-owl-widgets + squeekboard +
#                             weston + Chrome + fonts/X bits; then the patched Hyprland stack from the prefetched bundle
#   6 the phone user          shims, user desk (uid 1000), Omarchy's skel, omamobile-omarchy-patches apply,
#                             omamobile-setup-user, LinuxDesk overrides (portrait scale 2, no idle lock, no fingerprint)
#   7 checks + marker         qs, squeekboard, /usr/share/omamobile, Hyprland stack, browser -> omamobile-v1
# Resumable: each finished stage writes /etc/linuxdesk/omamobile-stage-N (= SETUP_REV); Retry skips those.
# Memory gate (Tami's rule: ONE heavy job, MemAvailable >= 2.5 GB, SwapFree >= 1.2 GB) before the pacman stages.
set -eu

log() { echo "[omamobile] $*"; }
SETUP_REV=1
TOTAL=7
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
  if [ "$(cat "$MARK/omamobile-stage-$CUR" 2>/dev/null || true)" = "$SETUP_REV" ]; then log "stage $CUR already done; skipped"; return 0; fi
  return 1
}
mark_done() { mkdir -p "$MARK"; echo "$SETUP_REV" > "$MARK/omamobile-stage-$CUR"; }
LD=${LINUXDESK_DIR:-/opt/linuxdesk}

# --- memory gate (same as setup-omarchy-clean.sh) --------------------------------------------------------------------
MEMINFO=${LINUXDESK_MEMINFO:-/proc/meminfo}
mem_kb() { awk -v k="$1:" '$1 == k { print $2; exit }' "$MEMINFO" 2>/dev/null; }
mem_gate() {
  i=0
  while :; do
    avail=$(mem_kb MemAvailable); swapf=$(mem_kb SwapFree); swapt=$(mem_kb SwapTotal)
    [ -n "$avail" ] || return 0
    if [ "$avail" -ge 2621440 ] && { [ "${swapt:-0}" -eq 0 ] || [ "${swapf:-0}" -ge 1258291 ]; }; then return 0; fi
    if [ "$i" -ge 40 ]; then
      log "memory still low after 20 min (MemAvailable $((avail / 1024)) MB, SwapFree $(( ${swapf:-0} / 1024 )) MB; need 2560 / 1228). Close other apps, then Retry: finished stages are kept."
      exit 9
    fi
    [ $((i % 4)) -eq 0 ] && log "waiting for memory (MemAvailable $((avail / 1024)) MB, SwapFree $(( ${swapf:-0} / 1024 )) MB)"
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
# pacman -S that tolerates PRoot hook failures (systemd-tmpfiles, sysusers, udev); callers verify with pacman -Q.
pac() {
  pacman -S --needed --noconfirm "$@" && return 0
  log "pacman reported errors; refreshing the list and retrying once"
  pacman -Syy --noconfirm >/dev/null 2>&1 || true
  pacman -S --needed --noconfirm "$@" || log "pacman reported errors again (often harmless hook failures under PRoot); verifying"
}
need_pkgs() {
  code=$1; shift; missing=""
  for p in "$@"; do pacman -Q "$p" >/dev/null 2>&1 || missing="$missing $p"; done
  [ -z "$missing" ] || { log "not installed:$missing"; exit "$code"; }
}

CONF=/etc/pacman.conf
PT="$(dirname "$0")/pacman-tune.sh"; [ -f "$PT" ] || PT=$LD/pacman-tune.sh
if [ -f "$PT" ]; then . "$PT"; else pacman_tune() { :; }; ignore_pkgs() { :; }; fi
pacman_tune "$CONF"

# The patched Hyprland stack (omarchy-android v0.1.1 bundle: Hyprland 0.56.1 + Aquamarine with an SHM allocator, the only
# Hyprland proven to draw inside LinuxDesk). setup-omarchy-clean.sh's `hypr-stack` installs it from the bundle; we only
# PRE-FETCH that bundle in parallel with pacman (same file name, size and sha256, so hypr-stack skips its own download).
HS_URL=https://github.com/BlackFireAlex/omarchy-android/releases/download/v0.1.1/omarchy-android-aarch64-0.1.1.bundle.tar
HS_SIZE=1181941760
HS_FILE=/var/cache/linuxdesk/omarchy-android-aarch64-0.1.1.bundle.tar
HS_PID=""
prefetch_hypr_bundle() {
  [ -f "$MARK/clean-hypr-stack-v1" ] && [ -x /opt/omarchy-android/hyprland/bin/Hyprland ] && return 0
  [ "$(stat -c %s "$HS_FILE" 2>/dev/null || echo 0)" = "$HS_SIZE" ] && return 0
  mkdir -p /var/cache/linuxdesk
  log "downloading the patched Hyprland bundle (1.18 GB) in the background, in parallel with pacman"
  ( curl -fsSL --retry 3 -C - -o "$HS_FILE" "$HS_URL" > /var/cache/linuxdesk/hypr-prefetch.log 2>&1 \
      || echo "prefetch failed" >> /var/cache/linuxdesk/hypr-prefetch.log ) &
  HS_PID=$!
}

log "$(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-Arch Linux ARM}") on $(uname -m); setup rev $SETUP_REV"

# 1. pacman under PRoot ------------------------------------------------------------------------------------------------
stage 1 "Configuring pacman for PRoot"
if ! stage_done; then
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

# 2. keyring -----------------------------------------------------------------------------------------------------------
stage 2 "Initialising the pacman keyring"
if ! stage_done; then
  if [ ! -s /etc/pacman.d/gnupg/pubring.gpg ] && [ ! -s /etc/pacman.d/gnupg/pubring.kbx ]; then
    log "pacman-key --init (can take a minute under PRoot)"
    pacman-key --init || { rm -rf /etc/pacman.d/gnupg; pacman-key --init; }
  fi
  pacman-key --populate archlinuxarm >/dev/null 2>&1 || log "note: populate archlinuxarm reported errors; continuing"
  gpgconf --kill gpg-agent 2>/dev/null || true
  mark_done
fi

# 3. full upgrade ------------------------------------------------------------------------------------------------------
stage 3 "Updating Arch Linux ARM (full upgrade)"
if ! stage_done; then
  mem_gate
  sync_db
  # shellcheck disable=SC2046  # one package name per word, intended
  pacman -Rdd --noconfirm linux-aarch64 $(pacman -Qq | grep '^linux-firmware') mkinitcpio mkinitcpio-busybox 2>/dev/null \
    || log "note: kernel/firmware packages were not removed (already gone?)"
  pacman -Su --noconfirm || { pacman -Syy --noconfirm; pacman -Su --noconfirm; } \
    || log "pacman -Su reported errors (often harmless hook failures under PRoot); verifying later"
  mark_done
fi

# 4. repositories --------------------------------------------------------------------------------------------------------
stage 4 "Adding the Omarchy and Omarchy Mobile repositories"
prefetch_hypr_bundle
if ! stage_done; then
  # omarchy-keyring: the same pin as setup-omarchy-clean.sh (sha256 from omarchy.db, 2026-11-19) + its fingerprint.
  OMARCHY_FPR=40DFB630FF42BCFFB047046CF0134EE680CAC571
  if ! pacman-key --list-keys "$OMARCHY_FPR" >/dev/null 2>&1; then
    KR=omarchy-keyring-20251027-1-any.pkg.tar.zst
    curl -fsSL --retry 3 -o "/tmp/$KR" "https://pkgs.omarchy.org/stable/aarch64/$KR"
    echo "cce48c07cfc2b97e198df1bc81705db2921685032fe39f18b4ddfd84264b5967  /tmp/$KR" | sha256sum -c - || { log "omarchy-keyring sha256 mismatch"; exit 6; }
    pacman -U --noconfirm "/tmp/$KR"; rm -f "/tmp/$KR"
    pacman-key --populate omarchy >/dev/null 2>&1 || true
    pacman-key --list-keys "$OMARCHY_FPR" >/dev/null 2>&1 || { log "Omarchy signing key $OMARCHY_FPR not in the keyring"; exit 6; }
  fi
  # omamobile-keyring: pinned by sha256 (REPORT.md section 3, checked against the live repo 2026-11-20) + both
  # fingerprints, so [omamobile] can stay `SigLevel = Required` (its .sig files are served).
  for fpr in 6C84FE3D240709E70E27CC94B301F5EA62B249FE 20567593566EEEE2DD8864608632C8075EB94673; do
    pacman-key --list-keys "$fpr" >/dev/null 2>&1 && continue
    KR=omamobile-keyring-20261026-1-any.pkg.tar.zst
    curl -fsSL --retry 3 -o "/tmp/$KR" "https://pkgs.omamobile.org/stable/aarch64/$KR"
    echo "83684eeda2ae7e1929109b0a65ede42bc174e7f00fa093dc69243714a2b125a0  /tmp/$KR" | sha256sum -c - || { log "omamobile-keyring sha256 mismatch"; exit 6; }
    pacman -U --noconfirm "/tmp/$KR"; rm -f "/tmp/$KR"
    pacman-key --populate omamobile >/dev/null 2>&1 || true
    break
  done
  for fpr in 6C84FE3D240709E70E27CC94B301F5EA62B249FE 20567593566EEEE2DD8864608632C8075EB94673; do
    pacman-key --list-keys "$fpr" >/dev/null 2>&1 || { log "Omarchy Mobile signing key $fpr not in the keyring"; exit 6; }
  done
  gpgconf --kill gpg-agent 2>/dev/null || true
  # Order: ALARM first (its hyprland/aquamarine/hyprgraphics sonames resolve; setup-omarchy-clean.sh stage 4), then
  # [omarchy] (omarchy, walker, google-chrome), then [omamobile] LAST, so only packages nobody else has come from it.
  grep -q '^\[omarchy\]' "$CONF" || printf '\n[omarchy]\nServer = https://pkgs.omarchy.org/stable/$arch\n' >> "$CONF"
  grep -q '^\[omamobile\]' "$CONF" || printf '\n[omamobile]\nSigLevel = Required\nServer = https://pkgs.omamobile.org/stable/$arch\n' >> "$CONF"
  # Their phone-hardware packages never belong in an app (kernel, boot, Mali Mesa, device/modem/sensor stacks).
  ignore_pkgs "$CONF" linux-omamobile omamobile-boot-update omamobile-firstboot vulkan-panfrost omamobile-modem \
    omamobile-modem-sit omamobile-mmd omamobile-sensors omamobile-gnss omamobile-fingerprint omamobile-waydroid
  SYNCED=0
  sync_db
  mark_done
fi

# 5. packages ------------------------------------------------------------------------------------------------------------
# --assume-installed: their Hyprland 0.57 (we run the patched 0.56.1 from /opt/omarchy-android), sddm (no login screen),
# omamobile-apps (camera/phone/maps/NFC need hardware; omamobile-shell depends on it), pipewire-pulse (LinuxDesk's
# sound is PulseAudio to the Android host; pipewire-pulse conflicts with pulseaudio).
OMA_PKGS="omarchy omarchy-settings omamobile-omarchy omamobile-owl-widgets omamobile-shell squeekboard weston dbus"
OMA_PKGS="$OMA_PKGS python python-gobject python-dbus jq wtype foot xdg-desktop-portal-gnome inotify-tools"
OMA_PKGS="$OMA_PKGS google-chrome xkeyboard-config xorg-fonts-misc xorg-fonts-alias-misc xorg-mkfontscale xdotool"
OMA_PKGS="$OMA_PKGS xorg-xwininfo xorg-xprop xorg-xrandr xorg-xwayland noto-fonts noto-fonts-emoji ttf-dejavu fontconfig"
OMA_PKGS="$OMA_PKGS pulseaudio libpulse"
# The patched Hyprland's link set that ALARM provides (setup-omarchy-clean.sh hypr_stack), in the same transaction.
OMA_PKGS="$OMA_PKGS lua muparser re2 tomlplusplus libzip hyprwire pugixml hyprutils hyprlang hyprcursor"
stage 5 "Installing Omarchy Mobile (one pacman transaction) + the patched Hyprland"
if ! stage_done; then
  mem_gate
  sync_db
  sh "$LD/clean/install-shims.sh" || { log "installing the PRoot shims failed"; exit 7; }
  # shellcheck disable=SC2086  # one package name per word, intended
  pac --assume-installed hyprland=0.57.0 --assume-installed sddm --assume-installed omamobile-apps \
    --assume-installed pipewire-pulse $OMA_PKGS
  if ! command -v google-chrome-stable >/dev/null 2>&1 && ! command -v chromium >/dev/null 2>&1; then pac chromium; fi
  need_pkgs 7 omarchy omarchy-settings omamobile-shell omamobile-omarchy squeekboard weston quickshell
  [ -d /usr/share/omamobile/shell ] || { log "/usr/share/omamobile/shell missing after install"; exit 7; }
  mkdir -p /usr/share/fonts/X11
  for d in misc TTF OTF 75dpi 100dpi Type1; do
    if [ -d "/usr/share/fonts/$d" ] && [ ! -e "/usr/share/fonts/X11/$d" ]; then ln -s "../$d" "/usr/share/fonts/X11/$d"; fi
  done
  [ -f /usr/share/fonts/misc/fonts.dir ] || mkfontdir /usr/share/fonts/misc 2>/dev/null || true
  fc-cache -f >/dev/null 2>&1 || true
  # The patched Hyprland stack: wait for the parallel prefetch, then setup-omarchy-clean.sh's hypr-stack extracts it
  # (sha256-checked, only opt/omarchy-android/{hyprland,aquamarine,mesa} + libhypr*; the bundle is deleted after).
  if [ -n "$HS_PID" ]; then log "waiting for the Hyprland bundle download"; wait "$HS_PID" || true; fi
  mem_gate
  sh "$LD/setup-omarchy-clean.sh" hypr-stack || { log "the patched Hyprland stack failed (Retry resumes the download)"; exit 8; }
  pacman -Scc --noconfirm >/dev/null 2>&1 || true
  log "installed: $(pacman -Q omarchy omamobile-shell squeekboard 2>/dev/null | tr '\n' ' ')"
  mark_done
fi

# 6. the phone user ------------------------------------------------------------------------------------------------------
stage 6 "Setting up the Omarchy Mobile user"
if ! stage_done; then
  sh "$LD/clean/install-shims.sh" || log "note: shims not refreshed"
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
  if [ -d /etc/skel ]; then
    (cd /etc/skel && find . -type d) | while read -r d; do mkdir -p "$H/$d"; done
    (cd /etc/skel && find . ! -type d) | while read -r f; do [ -e "$H/$f" ] || cp -P "/etc/skel/$f" "$H/$f"; done
  fi
  ST=$H/.local/state/omarchy/current
  mkdir -p "$ST"
  if [ ! -e "$ST/theme" ] && [ -d /usr/share/omarchy/themes/tokyo-night ]; then
    ln -s /usr/share/omarchy/themes/tokyo-night "$ST/theme"; echo tokyo-night > "$ST/theme.name"
  fi
  if [ ! -e "$ST/background" ]; then
    bg=$(ls /usr/share/omarchy/themes/tokyo-night/backgrounds/* 2>/dev/null | head -n 1 || true)
    [ -n "$bg" ] && ln -s "$bg" "$ST/background"
  fi
  mkdir -p "$H/.config/omarchy" "$H/.config/hypr"
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
  # Omarchy Mobile's 31 touch patches over Omarchy (tolerated: an Omarchy newer than their series refuses cleanly and
  # leaves Omarchy's own files; REPORT.md risk 4), and the lock's PAM file.
  omamobile-omarchy-patches apply || log "note: omamobile-omarchy-patches apply failed (see /var/log/omamobile/omarchy-patches.log); stock Omarchy files kept"
  [ -x /usr/lib/omamobile-omarchy/lock-pam ] && { /usr/lib/omamobile-omarchy/lock-pam || true; }
  # Their per-user setup (plugins, shell.json profile, phone.lua in looknfeel.lua, squeekboard layouts + autostart,
  # Chromium phone flags, menu trims), as desk, on a private session bus. Under PRoot setpriv may not switch: then as root
  # with HOME=desk (files chowned after).
  RTS=/tmp/omamobile-setup-rt; mkdir -p "$RTS"; chmod 700 "$RTS"; chown 1000:1000 "$RTS" 2>/dev/null || true
  AS=""
  command -v setpriv >/dev/null 2>&1 && setpriv --reuid=1000 --regid=1000 --clear-groups true 2>/dev/null \
    && AS="setpriv --reuid=1000 --regid=1000 --clear-groups"
  # shellcheck disable=SC2086  # AS is a command prefix
  $AS env HOME="$H" USER=desk LOGNAME=desk XDG_RUNTIME_DIR="$RTS" OMARCHY_PATH=/usr/share/omarchy \
    PATH=/usr/local/bin:/usr/bin:/bin dbus-run-session --config-file="$H/.config/omarchy/proot-session-bus.conf" -- \
    bash /usr/bin/omamobile-setup-user || log "note: omamobile-setup-user reported errors; continuing with what it set up"
  rm -rf "$RTS"
  # LinuxDesk overrides. shell.json: no idle lock (Omarchy's lock needs PAM/logind; Android's lock protects the phone),
  # and no fingerprint plugin (no sensor in an app).
  python3 - "$H/.config/omarchy/shell.json" <<'PY' || log "note: shell.json not adjusted"
import json, os, sys
p = sys.argv[1]
try:
    c = json.load(open(p))
except FileNotFoundError:
    c = json.load(open("/usr/share/omamobile/shell/profile/shell.json"))
c.setdefault("idle", {})["lock"] = 0
c["plugins"] = [x for x in c.get("plugins", []) if not (isinstance(x, dict) and x.get("id") == "omamobile.fingerprint")]
os.makedirs(os.path.dirname(p), exist_ok=True)
json.dump(c, open(p + ".new", "w"), indent=2); os.replace(p + ".new", p)
PY
  # Portrait output at the phone scale (start-omamobile.sh sets OMARCHY_SCALE, default 2: 968x2176 -> 484x1088 logical,
  # about upstream's 480x1040). Replaces Omarchy's monitors.lua, as setup-omarchy-clean.sh does.
  cat > "$H/.config/hypr/monitors.lua" <<'EOF'
-- LinuxDesk (Omarchy Mobile): one nested output, the portrait Weston window, at the phone scale.
hl.monitor({ output = "", mode = "preferred", position = "auto", scale = tonumber(os.getenv("OMARCHY_SCALE") or "2") or 2 })
EOF
  cat > "$H/.config/hypr/linuxdesk-omamobile.lua" <<'EOF'
-- LinuxDesk (Omarchy Mobile) overrides, loaded after Omarchy Mobile's phone.lua.
-- We start Hyprland ourselves (no start-hyprland watchdog, no systemd) and hyprland-guiutils may be missing.
hl.config({ misc = { disable_hyprland_guiutils_check = true, disable_watchdog_warning = true } })
-- Hyprland 0.56.1 places a `move` rule with the window's old width (fixed upstream in 0.57, #16150), so the
-- picture-in-picture window would land too far left: use the 300 px width phone.lua sets.
hl.window_rule({ match = { tag = "pip" }, move = { "(monitor_w-308)", "(monitor_h*0.08)" } })
EOF
  LNF=$H/.config/hypr/looknfeel.lua
  [ -f "$LNF" ] || : > "$LNF"
  grep -qs 'hypr/phone\.lua' "$H"/.config/hypr/*.lua \
    || printf '\n-- Omarchy Mobile: phone window rules\ndofile("/usr/share/omamobile/shell/hypr/phone.lua")\n' >> "$LNF"
  grep -qs 'linuxdesk-omamobile\.lua' "$LNF" \
    || printf '\n-- LinuxDesk overrides\ndofile(os.getenv("HOME") .. "/.config/hypr/linuxdesk-omamobile.lua")\n' >> "$LNF"
  chown -R 1000:1000 "$H" 2>/dev/null || true
  # Omarchy 4.5 is Qt 6.12 safe (qualified Commons.Color); the check decides and logs, as for the clean distro.
  OP=$(sh "$LD/clean/qt612-shell.sh" /usr/share/omarchy /var/lib/linuxdesk/omarchy-qt612 2>/dev/null || echo /usr/share/omarchy)
  echo "$OP" > "$MARK/omamobile-omarchy-path"
  log "OMARCHY_PATH = $OP"
  mark_done
fi

# 7. checks + marker -------------------------------------------------------------------------------------------------------
stage 7 "Checking the Omarchy Mobile install"
BIN=""
for b in google-chrome-stable chromium; do command -v "$b" >/dev/null 2>&1 && { BIN=$b; break; }; done
[ -n "$BIN" ] || { log "no browser binary (google-chrome-stable / chromium)"; exit 3; }
for b in quickshell squeekboard weston dbus-run-session omamobile-setup-user; do command -v "$b" >/dev/null 2>&1 || { log "$b missing"; exit 5; }; done
[ -f /usr/share/omamobile/shell/hypr/phone.lua ] || { log "phone.lua missing"; exit 5; }
[ -x /opt/omarchy-android/hyprland/bin/Hyprland ] || { log "the patched Hyprland stack is missing"; exit 5; }
sh "$LD/clean/install-shims.sh" || { log "the PRoot shims are not in place"; exit 7; }
mkdir -p "$MARK"
command -v pulseaudio >/dev/null 2>&1 && echo "pulseaudio libpulse" > "$MARK/audio-v1"
echo "omarchy=$(pacman -Q omarchy 2>/dev/null | cut -d' ' -f2) omamobile-shell=$(pacman -Q omamobile-shell 2>/dev/null | cut -d' ' -f2) $BIN" > "$MARK/omamobile-v1"
mark_done
log "done: $(cat "$MARK/omamobile-v1")"
