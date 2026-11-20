#!/bin/sh
# LinuxDesk "Omarchy" first-run setup. Runs INSIDE the Arch Linux ARM aarch64 guest (files/rootfs-arch) under
# PRoot (fake root). Idempotent; on success writes the marker /etc/linuxdesk/omarchy-v1, which the app checks.
# Resumable: each finished stage writes /etc/linuxdesk/omarchy-stage-N (content = SETUP_REV), and a re-run (the
# setup screen's Retry) skips those stages. The package list is still refreshed (-Syy) before the first install
# stage that runs, so a Retry never installs from a stale list (the 404s of 2026-11-19).
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
# LINUXDESK_STAGE lines drive the app's full-screen setup progress bar (OmarchyProgress.kt). A skipped stage still
# prints its line (the bar jumps past it). On a failure, LINUXDESK_STAGE_FAILED names the stage that stopped, which
# the setup screen shows on its Retry button.
SETUP_REV=1
MARK=/etc/linuxdesk
CUR=0
CUR_TITLE=""
stage() { CUR=$1; CUR_TITLE=$2; echo "LINUXDESK_STAGE $1/7 $2"; }
on_exit() {
  rc=$?
  if [ "$rc" -ne 0 ] && [ "$CUR" -gt 0 ]; then echo "LINUXDESK_STAGE_FAILED $CUR/7 $CUR_TITLE (exit $rc)"; fi
}
trap on_exit EXIT
# True (and logs) when the current stage finished in an earlier run of this setup revision.
stage_done() {
  if [ "$(cat "$MARK/omarchy-stage-$CUR" 2>/dev/null || true)" = "$SETUP_REV" ]; then log "stage $CUR already done; skipped"; return 0; fi
  return 1
}
mark_done() { mkdir -p "$MARK"; echo "$SETUP_REV" > "$MARK/omarchy-stage-$CUR"; }
# -Syy, not -Sy: force a fresh package list. Tami's phone (2026-11-19 21:12) kept a stale one and every mirror
# answered 404 for chromium 151 / gtk3 3.24.52-1 / mesa / llvm-libs, which had already been replaced by newer builds.
# Once per run, before the first install stage that runs; never skipped (stage 4 forces another after adding a repo).
SYNCED=0
sync_db() {
  [ "$SYNCED" = 1 ] && return 0
  attempt=1
  until pacman -Syy --noconfirm; do
    if [ "$attempt" -ge 3 ]; then log "pacman -Sy failed 3 times (no network?)"; exit 2; fi
    attempt=$((attempt + 1)); log "retrying pacman -Sy in 5s"; sleep 5
  done
  SYNCED=1
}

# 8. Omarchy's apps (optional) ------------------------------------------------------------------------------------
# `setup-omarchy.sh apps`: Omarchy's end-user apps that can run on X11 + i3 under PRoot (notes/omarchy-apps.md has the
# full add / skip / substitute list with reasons). start-omarchy.sh starts it in the BACKGROUND while its marker
# (/etc/linuxdesk/omarchy-stage-8 = APPS_REV) is missing, so the first-run setup and the 7-stage bar stay as they were,
# and rootfs that finished setup earlier get the apps at their next Start. Never fatal: one package per pacman call
# (one failure does not block the rest), every failure goes to $APPS_FAILED. After APPS_TRIES passes that still had
# failures the marker is written anyway (a package gone from the repo must not cost a -Syy at every Start).
# Small apps first, LibreOffice (467 MiB) and Obsidian (363 MiB) last.
APPS_REV=1
APPS_TRIES=3
APPS_PKGS="omacalc omawrite omacut hype signed monologue imv mpv thunar flameshot xcolor xclip pulseaudio-alsa"
APPS_PKGS="$APPS_PKGS lazygit dust fastfetch herdr tobi-try cliamp papers localsend pinta mise-bin obsidian libreoffice-fresh"
# Web apps: Omarchy's (omarchy-settings 4.5 skel + install/packaging/webapps.sh), opened as Chrome app windows.
# name|url|icon (Omarchy's hicolor icon name; google-chrome when Omarchy ships none)
APPS_WEB="WhatsApp|https://web.whatsapp.com/|whatsapp
YouTube|https://youtube.com/|youtube
ChatGPT|https://chatgpt.com/|chatgpt
X|https://x.com/|x
Discord|https://discord.com/channels/@me|omarchy-discord
Zoom|https://app.zoom.us/wc/home|zoom
HEY|https://app.hey.com|hey
Basecamp|https://launchpad.37signals.com|basecamp
Google Photos|https://photos.google.com/|google-photos
Google Contacts|https://contacts.google.com/|google-contacts
Google Messages|https://messages.google.com/web/conversations|google-messages
Google Maps|https://maps.google.com|google-maps
GitHub|https://github.com/|google-chrome
Figma|https://figma.com/|google-chrome
Fizzy|https://app.fizzy.do/|google-chrome
Netflix|https://www.netflix.com/|google-chrome
Spotify|https://open.spotify.com/|google-chrome"
# Terminal apps without their own launcher entry (or with Terminal=true, which rofi would open in a terminal it does
# not have): desktop-id|Name|binary|command|icon. They run in Omarchy's terminal (terminal.sh: alacritty, xterm fallback).
APPS_TUI='lazygit|Lazygit|lazygit|lazygit|utilities-terminal
disk-usage|Disk Usage|dust|sh -c "dust -r; read x"|disk-usage
fastfetch|Fastfetch|fastfetch|sh -c "fastfetch; read x"|utilities-terminal
herdr|Herdr|herdr|herdr|utilities-terminal
cliamp|cliamp|cliamp|cliamp|cliamp
btop|Activity Monitor|btop|btop|btop
nvim|Neovim|nvim|nvim|nvim
vim|Vim|vim|vim|gvim'
APPS_DESK=/usr/local/share/applications
APPS_FAILED=/etc/linuxdesk/omarchy-apps.failed
APPS_LOCK=/etc/linuxdesk/omarchy-apps.lock
PACMAN_LCK=/var/lib/pacman/db.lck
# Memory guard. 2026-11-19 22:49 Android's low-memory killer SIGKILLed Tami's session (pulse + deskd) while a 1.18 GB
# download ran with swap nearly full, and this stage runs next to the live desktop. Before each package: wait (30 s
# steps, max 20 min) until MemAvailable >= 1.5 GB and SwapFree >= 1 GB; the two big ones (LibreOffice 467 MiB,
# Obsidian 363 MiB) need MemAvailable >= 2.5 GB. Still low after 20 min: the pass stops without its marker and
# without counting as a pass, and the next Start resumes. No swap at all (SwapTotal 0) skips the swap rule; an
# unreadable meminfo never blocks. LINUXDESK_MEMINFO overrides the file (dry runs).
MEMINFO=${LINUXDESK_MEMINFO:-/proc/meminfo}
APPS_MEM_KB=1572864
APPS_MEM_BIG_KB=2621440
APPS_SWAP_KB=1048576
APPS_BIG="obsidian libreoffice-fresh"
mem_kb() { awk -v k="$1:" '$1 == k { print $2; exit }' "$MEMINFO" 2>/dev/null; }
mem_ok() {  # mem_ok <MemAvailable kB needed>; sets AVAIL_MB / SWAP_MB for the log
  avail=$(mem_kb MemAvailable); swapf=$(mem_kb SwapFree); swapt=$(mem_kb SwapTotal)
  [ -n "$avail" ] || return 0
  AVAIL_MB=$((avail / 1024)); SWAP_MB=$(( ${swapf:-0} / 1024 ))
  [ "$avail" -ge "$1" ] || return 1
  [ "${swapt:-0}" -eq 0 ] || [ "${swapf:-0}" -ge "$APPS_SWAP_KB" ] || return 1
  return 0
}
wait_for_mem() {  # wait_for_mem <MemAvailable kB needed>; 1 = still low after 20 minutes
  i=0
  while ! mem_ok "$1"; do
    if [ "$i" -ge 40 ]; then return 1; fi
    [ $((i % 10)) -eq 0 ] && log "apps: waiting for memory (avail $AVAIL_MB MB, swap $SWAP_MB MB; need $(($1 / 1024)) MB)"
    sleep 30; i=$((i + 1))
  done
  return 0
}
desk_entry() {  # desk_entry <desktop-id> <Name> <Exec> <Icon> [Categories]
  printf '[Desktop Entry]\nType=Application\nVersion=1.0\nName=%s\nExec=%s\nIcon=%s\nTerminal=false\nCategories=%s\n' \
    "$2" "$3" "$4" "${5:-Utility;}" > "$APPS_DESK/$1.desktop"
}
apps_main() {
  MARK8=$MARK/omarchy-stage-8
  [ "$(cat "$MARK8" 2>/dev/null || true)" = "$APPS_REV" ] && { log "apps: already done"; return 0; }
  mkdir -p "$MARK" "$APPS_DESK"
  # One run at a time ("Restart browser" runs start-omarchy.sh again). flock frees the lock when the run dies.
  if command -v flock >/dev/null 2>&1; then
    exec 9>"$APPS_LOCK"
    flock -n 9 || { log "apps: another run is installing; not starting a second one"; return 0; }
  fi
  # Another pacman (e.g. the genie or GPU setup) may hold the lock: wait for it, up to 10 minutes.
  w=0
  while [ -e "$PACMAN_LCK" ] && command -v pgrep >/dev/null 2>&1 && pgrep -x pacman >/dev/null 2>&1 && [ "$w" -lt 120 ]; do
    sleep 5; w=$((w + 1))
  done
  # proot --kill-on-exit ends a run that was still installing when the browser closed, leaving pacman's lock behind.
  if [ -e "$PACMAN_LCK" ] && command -v pgrep >/dev/null 2>&1 && ! pgrep -x pacman >/dev/null 2>&1; then log "apps: removing a stale pacman lock"; rm -f "$PACMAN_LCK"; fi

  # Web apps need no download; write them first.
  printf '%s\n' "$APPS_WEB" | while IFS='|' read -r name url icon; do
    [ -n "$name" ] || continue
    id=linuxdesk-webapp-$(printf '%s' "$name" | tr 'A-Z ' 'a-z-')
    desk_entry "$id" "$name" "sh /opt/linuxdesk/omarchy/browser.sh --app=$url" "$icon" "Network;WebBrowser;"
  done
  n_web=$(printf '%s\n' "$APPS_WEB" | grep -c '|')
  log "apps: $n_web web app entries in $APPS_DESK"

  attempt=1
  until pacman -Syy --noconfirm; do
    if [ "$attempt" -ge 3 ]; then log "apps: pacman -Syy failed 3 times (no network?); next Start tries again"; return 0; fi
    attempt=$((attempt + 1)); log "apps: retrying pacman -Syy in 5s"; sleep 5
  done

  # Omarchy's app icons (WhatsApp, HEY, Disk Usage, ...) live in omarchy-settings; take only its hicolor icons.
  if [ ! -f /usr/share/icons/hicolor/48x48/apps/whatsapp.png ]; then
    pacman -Sw --noconfirm --nodeps --nodeps omarchy-settings >/dev/null 2>&1 || log "apps: note: could not download omarchy-settings (icons)"
    SPKG=$(ls -t /var/cache/pacman/pkg/omarchy-settings-[0-9]*.pkg.tar.zst 2>/dev/null | head -1 || true)
    if [ -n "$SPKG" ]; then
      bsdtar -xf "$SPKG" -C / 'usr/share/icons/hicolor/*' || log "apps: note: icon extract failed"
      rm -f "$SPKG"
    fi
  fi

  : > "$APPS_FAILED"
  ok=0; bad=0; lowmem=0
  for p in $APPS_PKGS; do
    if pacman -Q "$p" >/dev/null 2>&1; then ok=$((ok + 1)); continue; fi
    need=$APPS_MEM_KB
    case " $APPS_BIG " in *" $p "*) need=$APPS_MEM_BIG_KB ;; esac
    if ! wait_for_mem "$need"; then
      lowmem=1; log "apps: memory still low after 20 min (avail $AVAIL_MB MB, swap $SWAP_MB MB); stopping before $p, the next Start resumes"
      break
    fi
    log "apps: installing $p"
    if pacman -S --needed --noconfirm "$p" || { sleep 3; pacman -S --needed --noconfirm "$p"; }; then
      ok=$((ok + 1))
    elif pacman -Q "$p" >/dev/null 2>&1; then
      ok=$((ok + 1)); log "apps: $p installed (pacman reported errors, often harmless hook failures under PRoot)"
    else
      bad=$((bad + 1)); echo "$p" >> "$APPS_FAILED"; log "apps: FAILED $p"
    fi
  done
  pacman -Scc --noconfirm >/dev/null 2>&1 || true

  # Launcher fixes. Our entries live in /usr/local/share/applications, which rofi (XDG data dirs) reads before
  # /usr/share, so a same-named entry here replaces the package's.
  printf '%s\n' "$APPS_TUI" | while IFS='|' read -r id name bin cmd icon; do
    [ -n "$id" ] && command -v "$bin" >/dev/null 2>&1 || continue
    desk_entry "$id" "$name" "sh /opt/linuxdesk/omarchy/terminal.sh $cmd" "$icon" "System;TerminalEmulator;"
  done
  # Electron cannot use its setuid sandbox under PRoot (Chrome runs with --no-sandbox for the same reason).
  if command -v obsidian >/dev/null 2>&1; then desk_entry obsidian Obsidian "obsidian --no-sandbox %U" obsidian "Office;"; fi
  # Every installed app must be in rofi: report the ones without any launcher entry.
  for d in omacalc omawrite omacut hype signed monologue imv mpv thunar org.flameshot.Flameshot xcolor org.gnome.Papers \
           localsend com.github.PintaProject.Pinta obsidian libreoffice-writer; do
    if [ ! -f "$APPS_DESK/$d.desktop" ] && [ ! -f "/usr/share/applications/$d.desktop" ]; then log "apps: note: no launcher entry $d.desktop"; fi
  done
  command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$APPS_DESK" >/dev/null 2>&1 || true
  command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -q /usr/share/icons/hicolor >/dev/null 2>&1 || true

  if [ "$lowmem" = 1 ]; then
    log "apps: $ok of $(echo $APPS_PKGS | wc -w | tr -d ' ') packages installed, $bad failed (stopped for memory; not counted as a pass)"
    return 0
  fi
  tries=$(( $(cat "$MARK/omarchy-apps.tries" 2>/dev/null || echo 0) + 1 ))
  echo "$tries" > "$MARK/omarchy-apps.tries"
  log "apps: $ok of $(echo $APPS_PKGS | wc -w | tr -d ' ') packages installed, $bad failed (pass $tries)"
  if [ "$bad" -eq 0 ] || [ "$tries" -ge "$APPS_TRIES" ]; then
    echo "$APPS_REV" > "$MARK8"
    [ "$bad" -eq 0 ] || log "apps: giving up on: $(tr '\n' ' ' < "$APPS_FAILED")(listed in $APPS_FAILED)"
  else
    log "apps: next Start retries: $(tr '\n' ' ' < "$APPS_FAILED")"
  fi
  return 0
}

# 9. Omarchy (Sway) desktop (opt-in, experimental) -----------------------------------------------------------------
# Only when /etc/linuxdesk/desktop is `sway` (the launcher's "Omarchy (Sway)" choice; notes/sway-desktop-plan.md).
# Never installed for i3 users. Stock ALARM packages, ~15 MB / 57 packages (measured 2026-11-19, Exp B0); wofi is the
# launcher (Omarchy 3's walker is not in ALARM). Never fatal: it runs after the omarchy-v1 marker, so the i3 desktop
# works whatever happens here, and a run without its marker (/etc/linuxdesk/sway-v1 = SWAY_REV) is retried by the next
# `setup-omarchy.sh sway`. Not under `set -e` (it runs in a `||` list), so every step checks for itself.
# `sway_main wait` (the `sway` subcommand) waits up to 20 min for memory like the apps stage; the first-run setup only
# checks once and skips (it must not hold the setup screen for 20 minutes).
SWAY_REV=2
SWAY_PKGS="sway waybar foot mako xorg-xwayland swaybg wofi"
# The end4 look (sway-theme.py, /etc/linuxdesk/sway-style): matugen (palette from the wallpaper), nwg-dock (Mac dock),
# swaync (notifications), inter-font. All in ALARM (notes/gallery/_alarm/names.txt, 2026-11-20). Optional: installed
# after the core set, never part of the missing-check below; the theme falls back (baked palette, waybar dock, mako).
SWAY_EXTRA_PKGS="matugen nwg-dock swaync inter-font hyprland-guiutils"   # guiutils: Hyprland's dialogs (Tami 06:15)
sway_main() {
  # hyprland too: start-hyprland.sh falls back to start-sway.sh, which needs sway installed (else it lands on i3).
  case "$(cat "$MARK/desktop" 2>/dev/null || true)" in sway|hyprland) ;; *) return 0 ;; esac
  [ "$(cat "$MARK/sway-v1" 2>/dev/null || true)" = "$SWAY_REV" ] && { log "sway: already installed"; return 0; }
  if [ "${1:-}" = wait ]; then
    wait_for_mem "$APPS_MEM_KB" || { log "sway: memory still low after 20 min (avail $AVAIL_MB MB, swap $SWAP_MB MB); next Start retries"; return 1; }
  elif ! mem_ok "$APPS_MEM_KB"; then
    log "sway: memory low (avail $AVAIL_MB MB, swap $SWAP_MB MB); skipped, the next Start installs it"; return 1
  fi
  # Another pacman (apps stage, genie, GPU setup) may hold the lock: wait for it, up to 10 minutes.
  w=0
  while [ -e "$PACMAN_LCK" ] && command -v pgrep >/dev/null 2>&1 && pgrep -x pacman >/dev/null 2>&1 && [ "$w" -lt 120 ]; do
    sleep 5; w=$((w + 1))
  done
  attempt=1
  until pacman -Syy --noconfirm; do
    if [ "$attempt" -ge 3 ]; then log "sway: pacman -Syy failed 3 times (no network?)"; return 1; fi
    attempt=$((attempt + 1)); log "sway: retrying pacman -Syy in 5s"; sleep 5
  done
  log "sway: installing $SWAY_PKGS"
  # shellcheck disable=SC2086  # one package name per word, intended
  pacman -S --needed --noconfirm $SWAY_PKGS || { sleep 3; pacman -Syy --noconfirm; pacman -S --needed --noconfirm $SWAY_PKGS; } \
    || log "sway: pacman reported errors (often harmless hook failures under PRoot); verifying ..."
  # shellcheck disable=SC2086
  pacman -S --needed --noconfirm $SWAY_EXTRA_PKGS || log "sway: optional end4 packages not all installed (fallbacks are used)"
  pacman -Scc --noconfirm >/dev/null 2>&1 || true
  missing=""
  for b in sway waybar foot mako swaybg wofi Xwayland; do command -v "$b" >/dev/null 2>&1 || missing="$missing $b"; done
  [ -z "$missing" ] || { log "sway: missing after install:$missing; next Start retries"; return 1; }
  python3 /opt/linuxdesk/omarchy/sway-theme.py "$(cat "$MARK/omarchy-theme" 2>/dev/null || echo tokyo-night)" \
    || log "sway: note: theme render failed; sway uses its defaults"
  echo "$SWAY_REV" > "$MARK/sway-v1"
  log "sway: done: $(sway --version 2>/dev/null | head -1)"
  return 0
}
# The look (optional): polybar (the top bar; i3bar is its fallback) and picom (rounded corners and fades, xrender
# backend). `setup-omarchy.sh look`, started in the background by start-omarchy.sh while /etc/linuxdesk/look-v1 (=
# LOOK_REV) is missing, before the apps stage (one pacman at a time). Both packages are small (~2 MB together). Never
# fatal: bar.sh and compositor.sh fall back by themselves. Gives up (writes the marker) after APPS_TRIES passes.
LOOK_REV=1
LOOK_PKGS="polybar picom"
look_main() {
  [ "$(cat "$MARK/look-v1" 2>/dev/null || true)" = "$LOOK_REV" ] && { log "look: already done"; return 0; }
  mkdir -p "$MARK"
  if command -v flock >/dev/null 2>&1; then
    exec 8>"$MARK/omarchy-look.lock"
    flock -n 8 || { log "look: another run is installing; not starting a second one"; return 0; }
  fi
  if [ -e "$PACMAN_LCK" ] && command -v pgrep >/dev/null 2>&1 && ! pgrep -x pacman >/dev/null 2>&1; then log "look: removing a stale pacman lock"; rm -f "$PACMAN_LCK"; fi
  bad=""
  for p in $LOOK_PKGS; do
    pacman -Q "$p" >/dev/null 2>&1 && continue
    log "look: installing $p"
    pacman -S --needed --noconfirm "$p" || { pacman -Syy --noconfirm && pacman -S --needed --noconfirm "$p"; } || true
    pacman -Q "$p" >/dev/null 2>&1 || bad="$bad $p"
  done
  tries=$(( $(cat "$MARK/look.tries" 2>/dev/null || echo 0) + 1 ))
  echo "$tries" > "$MARK/look.tries"
  if [ -z "$bad" ] || [ "$tries" -ge "$APPS_TRIES" ]; then
    echo "$LOOK_REV" > "$MARK/look-v1"
    if [ -z "$bad" ]; then log "look: done (polybar + picom)"; else log "look: giving up on:$bad (i3bar, no compositor)"; fi
  else
    log "look: not installed:$bad; next Start retries"
  fi
  return 0
}
# The Hyprland desktop's command-line tools (optional; sweep lane 2026-11-20, notes/gap/SWEEP.md #2). Omarchy's tools
# call jq (80 files), gum (42), wl-copy/wl-paste (clipboard, screenshots), grim + slurp + satty (screenshots), fd/rg/fzf
# (pickers), hyprpicker/hyprsunset; none were installed, so the menus, screenshots and clipboard failed quietly. All are
# in ALARM extra and need no systemd. `setup-omarchy.sh hypr`, started in the background by start-hyprland.sh while
# /etc/linuxdesk/hypr-tools-v1 (= HYPR_TOOLS_REV) is missing. Same rules as the look stage: one package per pacman
# call, never fatal, gives up (writes the marker) after APPS_TRIES passes.
HYPR_TOOLS_REV=1
HYPR_TOOLS_PKGS="jq gum wl-clipboard grim slurp fd ripgrep fzf eza zoxide starship bat hyprpicker hyprsunset satty"
hypr_tools_main() {
  [ "$(cat "$MARK/hypr-tools-v1" 2>/dev/null || true)" = "$HYPR_TOOLS_REV" ] && { log "hypr-tools: already done"; return 0; }
  mkdir -p "$MARK"
  if command -v flock >/dev/null 2>&1; then
    exec 7>"$MARK/omarchy-hypr-tools.lock"
    flock -n 7 || { log "hypr-tools: another run is installing; not starting a second one"; return 0; }
  fi
  wait_for_mem "$APPS_MEM_KB" || { log "hypr-tools: memory still low after 20 min; next Start retries"; return 0; }
  if [ -e "$PACMAN_LCK" ] && command -v pgrep >/dev/null 2>&1 && ! pgrep -x pacman >/dev/null 2>&1; then log "hypr-tools: removing a stale pacman lock"; rm -f "$PACMAN_LCK"; fi
  bad=""
  for p in $HYPR_TOOLS_PKGS; do
    pacman -Q "$p" >/dev/null 2>&1 && continue
    log "hypr-tools: installing $p"
    pacman -S --needed --noconfirm "$p" || { pacman -Syy --noconfirm && pacman -S --needed --noconfirm "$p"; } || true
    pacman -Q "$p" >/dev/null 2>&1 || bad="$bad $p"
  done
  pacman -Scc --noconfirm >/dev/null 2>&1 || true
  tries=$(( $(cat "$MARK/hypr-tools.tries" 2>/dev/null || echo 0) + 1 ))
  echo "$tries" > "$MARK/hypr-tools.tries"
  if [ -z "$bad" ] || [ "$tries" -ge "$APPS_TRIES" ]; then
    echo "$HYPR_TOOLS_REV" > "$MARK/hypr-tools-v1"
    if [ -z "$bad" ]; then log "hypr-tools: done"; else log "hypr-tools: giving up on:$bad"; fi
  else
    log "hypr-tools: not installed:$bad; next Start retries"
  fi
  return 0
}
# A5/A8 pacman.conf helpers (pacman-tune.sh, shipped next to this script). A missing helper never fails setup.
PT="$(dirname "$0")/pacman-tune.sh"; [ -f "$PT" ] || PT=/opt/linuxdesk/pacman-tune.sh
if [ -f "$PT" ]; then . "$PT"; else pacman_tune() { :; }; ignore_pkgs() { :; }; unignore_pkgs() { :; }; fi
# A5: ParallelDownloads = 8, every run (idempotent), so rootfs that finished stage 1 earlier get it as well.
pacman_tune /etc/pacman.conf
if [ "${1:-}" = look ]; then look_main; exit 0; fi
if [ "${1:-}" = apps ]; then apps_main; exit 0; fi
if [ "${1:-}" = sway ]; then sway_main wait || exit 1; exit 0; fi
if [ "${1:-}" = hypr ]; then hypr_tools_main; exit 0; fi

log "$(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-Arch Linux ARM}") on $(uname -m)"

# 1. pacman under proot -----------------------------------------------------------------------------------------
stage 1 "Configuring pacman for PRoot"
if ! stage_done; then
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
mark_done
fi

# 2. keyring ---------------------------------------------------------------------------------------------------
stage 2 "Initialising the pacman keyring"
if ! stage_done; then
# pacman-key needs gpg-agent; under proot that works with a plain homedir. Retry once with a fresh keyring.
if [ ! -s /etc/pacman.d/gnupg/pubring.gpg ] && [ ! -s /etc/pacman.d/gnupg/pubring.kbx ]; then
  log "pacman-key --init (generates a local key; can take a minute under proot)"
  pacman-key --init || { log "pacman-key --init failed; retrying with a fresh keyring"; rm -rf /etc/pacman.d/gnupg; pacman-key --init; }
fi
pacman-key --populate archlinuxarm >/dev/null 2>&1 || log "note: populate archlinuxarm reported errors; continuing"
gpgconf --kill gpg-agent 2>/dev/null || true
mark_done
fi

# 3. base sync + upgrade ----------------------------------------------------------------------------------------
stage 3 "Updating Arch Linux ARM (full upgrade)"
if ! stage_done; then
sync_db
# Drop the kernel and firmware packages (their files were never extracted) so -Syu does not try to rebuild an
# initramfs. Then a full upgrade: Arch does not support partial upgrades.
# shellcheck disable=SC2046  # one package name per word, intended
pacman -Rdd --noconfirm linux-aarch64 $(pacman -Qq | grep '^linux-firmware') mkinitcpio mkinitcpio-busybox 2>/dev/null \
  || log "note: kernel/firmware packages were not removed (already gone?)"
pacman -Su --noconfirm || { log "pacman -Su failed; refreshing the package list and retrying once"; pacman -Syy --noconfirm; pacman -Su --noconfirm; } \
  || log "pacman -Su reported errors (often harmless hook failures under PRoot); verifying later"
mark_done
fi

# 4. Omarchy repo ----------------------------------------------------------------------------------------------
stage 4 "Adding the Omarchy repository"
if ! stage_done; then
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
SYNCED=0   # the new [omarchy] repo needs its own list
sync_db
mark_done
fi

# 5. desktop packages ------------------------------------------------------------------------------------------
# xkeyboard-config     lorie compiles its keymap from <rootfs>/usr/share/X11/xkb (a symlink to xkeyboard-config-2)
# xorg-fonts-misc      X core fonts ("fixed", "cursor"); linked into usr/share/fonts/X11 below for lorie
# xdotool xwininfo...  fit-window.sh / deskd input + window geometry
# i3-wm i3status rofi  the tiling desktop, its bar, and the launcher (Super+Space); dunst notifications; feh wallpaper
# alacritty xterm      terminal (Omarchy's foot is Wayland-only); xterm = fallback when there is no OpenGL
# noto-fonts(-emoji)   Hebrew + emoji glyphs; ttf-dejavu Latin; ttf-jetbrains-mono-nerd-basic is Omarchy's font (repo)
# python               the genie (deskd) and the theme renderer below
# pulseaudio libpulse  sound out / mic in. The app's PulseHost (audio lane) installs with apk, which Arch lacks; it
#                      skips its install when /etc/linuxdesk/audio-v1 and /usr/bin/pulseaudio exist, so this
#                      script installs them and writes that marker (pactl/parec are in libpulse on Arch).
PKGS="xkeyboard-config xorg-fonts-misc xorg-fonts-alias-misc xorg-mkfontscale xdotool xorg-xwininfo xorg-xprop"
PKGS="$PKGS xorg-xsetroot xorg-xrandr i3-wm i3status rofi dunst feh alacritty xterm btop"
PKGS="$PKGS noto-fonts noto-fonts-emoji ttf-dejavu ttf-liberation ttf-jetbrains-mono-nerd-basic fontconfig python pulseaudio libpulse"
stage 5 "Installing Chrome, i3, fonts and tools"
if ! stage_done; then
sync_db
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

# Checks: stage 5 counts as done only with a browser, the X keymaps and i3 in place.
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
mark_done
fi

# 6. Omarchy themes, without the meta package's deps --------------------------------------------------------------
stage 6 "Installing the Omarchy themes"
if ! stage_done; then
# -Sw downloads omarchy-*.pkg.tar.zst (82 MB, signature-checked by pacman) without installing its dependency tree;
# bsdtar then extracts only usr/share/omarchy/themes.
if [ ! -f /usr/share/omarchy/themes/tokyo-night/colors.toml ]; then
  sync_db
  pacman -Sw --noconfirm --nodeps --nodeps omarchy || log "note: could not download the omarchy package (themes)"
  PKG=$(ls -t /var/cache/pacman/pkg/omarchy-[0-9]*.pkg.tar.zst 2>/dev/null | head -1 || true)
  if [ -n "$PKG" ]; then
    bsdtar -xf "$PKG" -C / 'usr/share/omarchy/themes/*' && log "Omarchy themes installed from $(basename "$PKG")"
    rm -f "$PKG"
  fi
fi
# Themes are never fatal, but a stage without them is not "done": a later run tries the download again.
if [ -f /usr/share/omarchy/themes/tokyo-night/colors.toml ]; then mark_done; else log "note: Omarchy themes missing; i3 uses its defaults"; fi
fi

# 7. the Omarchy look for i3 / alacritty / Chrome (rendered from the theme's colors.toml) -------------------------
# Never skipped: it is quick, and it writes the final marker the app checks.
stage 7 "Applying the Omarchy look"
BIN=""
for b in google-chrome-stable chromium; do command -v "$b" >/dev/null 2>&1 && { BIN=$b; break; }; done
[ -n "$BIN" ] || { log "no browser binary (google-chrome-stable / chromium)"; exit 3; }
python3 /opt/linuxdesk/omarchy/omarchy-theme.py "${LINUXDESK_OMARCHY_THEME:-tokyo-night}" || log "note: theme render failed; i3 uses its defaults"

# Keep the image lean.
pacman -Scc --noconfirm >/dev/null 2>&1 || true

mkdir -p /etc/linuxdesk
command -v pulseaudio >/dev/null 2>&1 && echo "pulseaudio libpulse" > /etc/linuxdesk/audio-v1 \
  || log "note: pulseaudio missing; the session stays silent"
echo "$PKGS $BIN" > /etc/linuxdesk/omarchy-v1
mark_done
log "done: $($BIN --version 2>/dev/null || echo "$BIN"), $(i3 --version 2>/dev/null | head -1)"
# Opt-in sway desktop (stage 9 above): after the final marker, and never fails the setup.
sway_main || log "sway: not installed this run (the i3 desktop is unaffected)"
