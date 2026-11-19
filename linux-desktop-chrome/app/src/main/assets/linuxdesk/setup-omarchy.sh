#!/bin/sh
# LinuxDesk "Omarchy" first-run setup. Runs INSIDE the Arch Linux ARM aarch64 guest (files/rootfs-arch) under
# PRoot (fake root). Idempotent; on success writes the marker /etc/linuxdesk/omarchy-v1, which the app checks.
# Alpine never runs this file (Alpine runs setup-packages.sh, unchanged).
#
# What it builds: an Omarchy-FLAVORED desktop, not literal Omarchy (see notes/omarchy-research.json):
#   Arch Linux ARM + Omarchy's live aarch64 pacman repo (pkgs.omarchy.org/stable/aarch64, signed by
#   omarchy-keyring, key 40DFB630FF42BCFFB047046CF0134EE680CAC571) → Google Chrome 158 (chromium fallback),
#   Omarchy's fonts and themes, i3 on the app's lorie X11 server (no Hyprland: no /dev/dri in an app).
# It does NOT install the `omarchy` meta package's dependencies (hyprland, sddm, uwsm, networkmanager; their
# scriptlets assume a booted systemd). It downloads that package file only, to take its themes.
#
# Package names were checked 2026-11-19 against the live ALARM core/extra/alarm dbs and omarchy.db.
# Measured 2026-11-19 from the live repo dbs (dependency closure minus the base rootfs): 193 packages, ~733 MiB
# download, ~2.4 GB installed (Chrome 205 MB, gtk3, mesa, noto-fonts, ...). Plus the 0.73 GB extracted rootfs.
# Free space needed: ~4 GB during setup (the 0.8 GB tarball is deleted after extraction).
set -eu

log() { echo "[omarchy] $*"; }
# LINUXDESK_STAGE lines drive the app's full-screen setup progress bar (OmarchyProgress.kt).
stage() { echo "LINUXDESK_STAGE $1/7 $2"; }
log "$(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-Arch Linux ARM}") on $(uname -m)"

# 1. pacman under proot -----------------------------------------------------------------------------------------
stage 1 "Configuring pacman for PRoot"
#  - DisableSandbox: pacman 7 sandboxes its downloader with Landlock + seccomp and a dropped-privilege user
#    (DownloadUser = alpm). Under proot's fake root setuid to alpm does not stick and Landlock may be missing,
#    so downloads fail. Both are turned off (the same fix Local Desktop and proot-distro use).
#  - CheckSpace: needs real mount points, which proot does not give; it makes pacman refuse with "could not
#    determine filesystem mount points". Turned off.
CONF=/etc/pacman.conf
# One awk pass (POSIX awk; the base rootfs has gawk, no python yet). Idempotent: running it twice changes nothing.
awk '
  /^\[options\]/ { print; if (!seen++) { print "DisableSandbox"; print "NoExtract = usr/lib/firmware/* usr/lib/modules/* boot/* usr/share/doc/* usr/share/gtk-doc/*" }; next }
  /^DisableSandbox/ || /^NoExtract = usr\/lib\/firmware/ { next }
  /^DownloadUser/ || /^CheckSpace/ { print "#" $0; next }
  /^\[aur\]/ { aur = 1; print "#" $0; next }
  /^\[/ { aur = 0 }
  aur && /^Include/ { print "#" $0; next }
  { print }
' "$CONF" > "$CONF.new" && mv "$CONF.new" "$CONF"
# Mirror: the geo-redirector host, plus HTTPS mirrors that verified on 2026-11-19.
cat > /etc/pacman.d/mirrorlist <<'EOF'
Server = https://fl.us.mirror.archlinuxarm.org/$arch/$repo
Server = https://ca.us.mirror.archlinuxarm.org/$arch/$repo
Server = https://de3.mirror.archlinuxarm.org/$arch/$repo
Server = http://mirror.archlinuxarm.org/$arch/$repo
EOF

# 2. keyring ---------------------------------------------------------------------------------------------------
stage 2 "Initialising the pacman keyring"
# pacman-key needs gpg-agent; under proot that works with a plain homedir. Retry once with a fresh keyring.
if [ ! -s /etc/pacman.d/gnupg/pubring.gpg ] && [ ! -s /etc/pacman.d/gnupg/pubring.kbx ]; then
  log "pacman-key --init (generates a local key; can take a minute under proot)"
  pacman-key --init || { log "pacman-key --init failed; retrying with a fresh keyring"; rm -rf /etc/pacman.d/gnupg; pacman-key --init; }
fi
pacman-key --populate archlinuxarm >/dev/null 2>&1 || log "note: populate archlinuxarm reported errors; continuing"
gpgconf --kill gpg-agent 2>/dev/null || true

# 3. base sync + upgrade ----------------------------------------------------------------------------------------
stage 3 "Updating Arch Linux ARM (full upgrade)"
attempt=1
# -Syy, not -Sy: force a fresh package list. Tami's phone (2026-11-19 21:12) kept a stale one and every mirror
# answered 404 for chromium 151 / gtk3 3.24.52-1 / mesa / llvm-libs, which had already been replaced by newer builds.
until pacman -Syy --noconfirm; do
  if [ "$attempt" -ge 3 ]; then log "pacman -Sy failed 3 times (no network?)"; exit 2; fi
  attempt=$((attempt + 1)); log "retrying pacman -Sy in 5s"; sleep 5
done
# Drop the kernel and firmware packages (their files were never extracted) so -Syu does not try to rebuild an
# initramfs. Then a full upgrade: Arch does not support partial upgrades.
pacman -Rdd --noconfirm linux-aarch64 $(pacman -Qq | grep '^linux-firmware') mkinitcpio mkinitcpio-busybox 2>/dev/null \
  || log "note: kernel/firmware packages were not removed (already gone?)"
pacman -Su --noconfirm || { log "pacman -Su failed; refreshing the package list and retrying once"; pacman -Syy --noconfirm; pacman -Su --noconfirm; } \
  || log "pacman -Su reported errors (often harmless hook failures under PRoot); verifying later"

# 4. Omarchy repo ----------------------------------------------------------------------------------------------
stage 4 "Adding the Omarchy repository"
# Trust bootstrap without a keyserver: fetch omarchy-keyring (6.9 KB) by its PINNED sha256 (from omarchy.db,
# checked 2026-11-19), install it as a local file (its scriptlet runs `pacman-key --populate omarchy`), and check the
# expected fingerprint. From then on the [omarchy] repo uses pacman's default SigLevel (signed packages required).
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
if ! grep -q '^\[omarchy\]' "$CONF"; then
  printf '\n[omarchy]\nServer = https://pkgs.omarchy.org/stable/$arch\n' >> "$CONF"
fi
pacman -Syy --noconfirm

# 5. desktop packages ------------------------------------------------------------------------------------------
stage 5 "Installing Chrome, i3, fonts and tools"
# xkeyboard-config     lorie compiles its keymap from <rootfs>/usr/share/X11/xkb (a symlink to xkeyboard-config-2)
# xorg-fonts-misc      X core fonts ("fixed", "cursor"); linked into usr/share/fonts/X11 below for lorie
# xdotool xwininfo...  fit-window.sh / deskd input + window geometry
# i3-wm i3status rofi  the tiling desktop, its bar, and the launcher (Super+Space); dunst notifications; feh wallpaper
# alacritty            terminal (Omarchy's foot is Wayland-only)
# noto-fonts(-emoji)   Hebrew + emoji glyphs; ttf-dejavu Latin; ttf-jetbrains-mono-nerd-basic is Omarchy's font (repo)
# python               the genie (deskd) and the theme renderer below
# pulseaudio libpulse  sound out / mic in. The app's PulseHost (audio lane) installs with apk, which Arch lacks; it
#                      skips its install when /etc/linuxdesk/audio-v1 and /usr/bin/pulseaudio exist, so this
#                      script installs them and writes that marker (pactl/parec are in libpulse on Arch).
PKGS="xkeyboard-config xorg-fonts-misc xorg-fonts-alias-misc xorg-mkfontscale xdotool xorg-xwininfo xorg-xprop"
PKGS="$PKGS xorg-xsetroot xorg-xrandr i3-wm i3status rofi dunst feh alacritty btop"
PKGS="$PKGS noto-fonts noto-fonts-emoji ttf-dejavu ttf-liberation ttf-jetbrains-mono-nerd-basic fontconfig python pulseaudio libpulse"
pacman -S --needed --noconfirm $PKGS || { pacman -Syy --noconfirm; pacman -S --needed --noconfirm $PKGS; } || log "pacman reported errors (often harmless hook failures under PRoot); verifying ..."

# Browser: Omarchy's default, Google Chrome 158 (glibc; deps gtk3 nss alsa-lib ..., no systemd). Fallback: ALARM
# chromium 157.
if ! command -v google-chrome-stable >/dev/null 2>&1; then
  pacman -S --needed --noconfirm google-chrome || log "google-chrome failed; trying chromium"
fi
if ! command -v google-chrome-stable >/dev/null 2>&1 && ! command -v chromium >/dev/null 2>&1; then
  pacman -S --needed --noconfirm chromium || { log "chromium failed; refreshing the package list and retrying once"; pacman -Syy --noconfirm; pacman -S --needed --noconfirm chromium; } || true
fi
# omarchy-nvim is nice to have (Omarchy's neovim setup); never fatal.
pacman -S --needed --noconfirm omarchy-nvim >/dev/null 2>&1 || log "note: omarchy-nvim not installed"

# 6. Omarchy themes, without the meta package's deps --------------------------------------------------------------
stage 6 "Installing the Omarchy themes"
# -Sw downloads omarchy-*.pkg.tar.zst (82 MB, signature-checked by pacman) without installing its dependency tree;
# bsdtar then extracts only usr/share/omarchy/themes.
if [ ! -f /usr/share/omarchy/themes/tokyo-night/colors.toml ]; then
  pacman -Sw --noconfirm --nodeps --nodeps omarchy || log "note: could not download the omarchy package (themes)"
  PKG=$(ls -t /var/cache/pacman/pkg/omarchy-[0-9]*.pkg.tar.zst 2>/dev/null | head -1 || true)
  if [ -n "$PKG" ]; then
    bsdtar -xf "$PKG" -C / 'usr/share/omarchy/themes/*' && log "Omarchy themes installed from $(basename "$PKG")"
    rm -f "$PKG"
  fi
fi

# 7. checks ------------------------------------------------------------------------------------------------------
BIN=""
for b in google-chrome-stable chromium; do command -v "$b" >/dev/null 2>&1 && { BIN=$b; break; }; done
[ -n "$BIN" ] || { log "no browser binary after install (google-chrome-stable / chromium)"; exit 3; }
[ -d /usr/share/X11/xkb/ ] || { log "/usr/share/X11/xkb missing after install"; exit 4; }
command -v i3 >/dev/null 2>&1 || { log "i3 missing after install"; exit 5; }

# lorie looks for core fonts at <rootfs>/usr/share/fonts/X11/{misc,TTF,...} (Alpine's layout). Arch keeps them in
# /usr/share/fonts/{misc,TTF}. Link them; X aborts without "fixed" and "cursor".
mkdir -p /usr/share/fonts/X11
for d in misc TTF OTF 75dpi 100dpi Type1; do
  if [ -d "/usr/share/fonts/$d" ] && [ ! -e "/usr/share/fonts/X11/$d" ]; then ln -s "../$d" "/usr/share/fonts/X11/$d"; fi
done
[ -f /usr/share/fonts/misc/fonts.dir ] || mkfontdir /usr/share/fonts/misc 2>/dev/null || true
fc-cache -f >/dev/null 2>&1 || true

# 8. the Omarchy look for i3 / alacritty / Chrome (rendered from the theme's colors.toml) -------------------------
stage 7 "Applying the Omarchy look"
python3 /opt/linuxdesk/omarchy/omarchy-theme.py "${LINUXDESK_OMARCHY_THEME:-tokyo-night}" || log "note: theme render failed; i3 uses its defaults"

# Keep the image lean.
pacman -Scc --noconfirm >/dev/null 2>&1 || true

mkdir -p /etc/linuxdesk
command -v pulseaudio >/dev/null 2>&1 && echo "pulseaudio libpulse" > /etc/linuxdesk/audio-v1 \
  || log "note: pulseaudio missing; the session stays silent"
echo "$PKGS $BIN" > /etc/linuxdesk/omarchy-v1
log "done: $($BIN --version 2>/dev/null || echo "$BIN"), $(i3 --version 2>/dev/null | head -1)"
