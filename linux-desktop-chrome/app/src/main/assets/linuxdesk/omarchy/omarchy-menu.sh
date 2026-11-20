#!/bin/sh
# The Omarchy Menu for the LinuxDesk sway desktop (Super+Space), like Omarchy 3's: Apps / Learn / Style / Setup /
# Install / Update / System. Omarchy's own omarchy-menu, omarchy-theme-set and walker are not in ALARM, so this is
# a wofi --dmenu tree (checked on the phone, 2026-11-20: only /usr/bin/omarchy-nvim-* exist).
#   omarchy-menu.sh [apps|learn|style|theme|setup|install|update|system]   jump straight to a submenu
# Escape or an empty choice at any level exits. User input is never eval'd: it is passed as "$1" or validated first.
# Real Omarchy commands: the omarchy 4.5 package ([omarchy] repo) is built for Hyprland, so having it installed does
# not mean a script works on sway under PRoot. A leaf uses a real omarchy-* command only when its name is listed (one
# per line) in /etc/linuxdesk/omarchy-real, the list of commands verified on the phone, AND it is on PATH. If the
# real command fails, the leaf falls back to ours. No list = ours everywhere.
# LINUXDESK_OMARCHY / LINUXDESK_ETC / SWAY_CONFIG point elsewhere for tests.
D="${LINUXDESK_OMARCHY:-/opt/linuxdesk/omarchy}"
ETC="${LINUXDESK_ETC:-/etc/linuxdesk}"
HOME="${HOME:-/root}"
SWAY_CONFIG="${SWAY_CONFIG:-$HOME/.config/sway/config}"
APPS_DIR="$HOME/.local/share/applications"
MANUAL="https://learn.omacom.io/2/the-omarchy-manual"

# menu PROMPT ITEM...: one choice from wofi (empty on Escape).
menu() {
  p=$1; shift
  printf '%s\n' "$@" | wofi --dmenu --insensitive --prompt "$p" 2>/dev/null
}
# ask PROMPT: free text typed into wofi (nothing to pick from).
ask() {
  wofi --dmenu --prompt "$1" </dev/null 2>/dev/null
}
notify() {
  if command -v notify-send >/dev/null 2>&1; then
    notify-send "Omarchy" "$1"
  else
    printf '%s\n' "$1" | wofi --dmenu --prompt "Omarchy" >/dev/null 2>&1
  fi
}
# Run a command in a terminal and keep it open until Enter. The command is fixed text; arguments go in as $1...
in_terminal() {
  script=$1; shift
  foot -e sh -c "$script; echo; echo 'Press Enter to close'; read -r _" sh "$@"
}

current_theme() {
  t=$(cat "$ETC/omarchy-theme" 2>/dev/null)
  echo "${t:-tokyo-night}"
}
# Re-render the sway configs (theme, look and layout are read by sway-theme.py) and reload sway.
rerender() {
  t=${1:-$(current_theme)}
  python3 "$D/sway-theme.py" "$t" >/dev/null || { notify "Could not render theme '$t'"; return 1; }
  # sway-theme.py does not record the theme; start-sway.sh reads it from here when it re-renders.
  printf '%s\n' "$t" > "$ETC/omarchy-theme" 2>/dev/null
  swaymsg reload >/dev/null
}

# has_real NAME: NAME is verified (listed in $ETC/omarchy-real) and installed.
has_real() {
  grep -qxF -- "$1" "$ETC/omarchy-real" 2>/dev/null && command -v "$1" >/dev/null 2>&1
}
# try_real NAME ARG...: run the real Omarchy command when has_real says so; nonzero (so the caller does it ours)
# when it is not there or failed.
try_real() {
  has_real "$1" || return 1
  "$@" && return 0
  echo "[omarchy-menu] $1 failed: using LinuxDesk's own" >&2
  return 1
}

slugify() {
  printf '%s' "$1" | tr 'A-Z' 'a-z' | tr -c 'a-z0-9' '-' | tr -s '-' | sed 's/^-//; s/-$//'
}

# --- Learn -------------------------------------------------------------------------------------------------------
keybindings() {
  try_real omarchy-menu-keybindings && return
  [ -r "$SWAY_CONFIG" ] || { notify "No sway config at $SWAY_CONFIG"; return; }
  list=$(awk '$1 == "bindsym" {
      key = $2; $1 = ""; $2 = ""; sub(/^ +/, "")
      gsub(/\$mod/, "Super", key); gsub(/Mod4/, "Super", key); gsub(/Mod1/, "Alt", key); gsub(/\+/, " + ", key)
      sub(/^exec /, ""); sub(/^sh \/opt\/linuxdesk\/omarchy\//, "")
      printf "%-28s %s\n", key, $0
    }' "$SWAY_CONFIG")
  [ -n "$list" ] || { notify "No key bindings found in $SWAY_CONFIG"; return; }
  printf '%s\n' "$list" | wofi --dmenu --insensitive --prompt "Keybindings" >/dev/null 2>&1
}
learn() {
  case $(menu "Learn" "󰌌  Keybindings" "󰈙  Omarchy manual") in
    *Keybindings) keybindings ;;
    *manual) sh "$D/browser.sh" "$MANUAL" >/dev/null 2>&1 & ;;
  esac
}

# --- Style -------------------------------------------------------------------------------------------------------
theme() {
  # Theme names: Omarchy's omarchy-theme-list when verified, else omarchy-theme.py --list (sway-theme.py has none).
  themes=
  has_real omarchy-theme-list && themes=$(omarchy-theme-list 2>/dev/null)
  [ -n "$themes" ] || themes=$(python3 "$D/omarchy-theme.py" --list 2>/dev/null)
  [ -n "$themes" ] || { notify "No Omarchy themes found"; return; }
  t=$(printf '%s\n' "$themes" | wofi --dmenu --insensitive --prompt "Theme" 2>/dev/null)
  [ -n "$t" ] || return
  printf '%s\n' "$themes" | grep -qxF -- "$t" || { notify "Unknown theme: $t"; return; }
  # A real omarchy-theme-set themes Hyprland/walker/etc.; sway's configs are ours, so render them either way.
  try_real omarchy-theme-set "$t"
  rerender "$t"
}
look() {
  l=$(menu "Look" "end4" "omarchy")
  case "$l" in end4|omarchy) ;; *) return ;; esac
  printf '%s\n' "$l" > "$ETC/sway-style" || { notify "Could not write $ETC/sway-style"; return; }
  rerender
}
style() {
  if [ -e "$ETC/sway-style" ]; then
    c=$(menu "Style" "󰸌  Theme" "  Look")
  else
    c=$(menu "Style" "󰸌  Theme")
  fi
  case "$c" in
    *Theme) theme ;;
    *Look) look ;;
  esac
}

# --- Setup -------------------------------------------------------------------------------------------------------
zoom() {
  case $(menu "Zoom" "  Zoom in" "  Zoom out" "󰑓  Reset") in
    *in) sh "$D/zoom.sh" in ;;
    *out) sh "$D/zoom.sh" out ;;
    *Reset) sh "$D/zoom.sh" reset ;;
  esac
}
layout() {
  l=$(menu "Layout" "floating" "tiling")
  case "$l" in floating|tiling) ;; *) return ;; esac
  printf '%s\n' "$l" > "$ETC/sway-layout" || { notify "Could not write $ETC/sway-layout"; return; }
  rerender
}
setup() {
  case $(menu "Setup" "  Zoom" "󰕰  Layout") in
    *Zoom) zoom ;;
    *Layout) layout ;;
  esac
}

# --- Install -----------------------------------------------------------------------------------------------------
install_package() {
  pkg=$(ask "Package name")
  [ -n "$pkg" ] || return
  case "$pkg" in
    -*|*[!a-zA-Z0-9@._+-]*) notify "Not a package name: $pkg"; return ;;
  esac
  # pacman asks before it installs: never --noconfirm.
  try_real omarchy-pkg-install "$pkg" || in_terminal 'pacman -S -- "$1"' "$pkg"
}
install_webapp() {
  # Omarchy's own web-app installer asks for name, URL and icon itself.
  try_real omarchy-webapp-install && return
  name=$(ask "Web app name")
  [ -n "$name" ] || return
  slug=$(slugify "$name")
  [ -n "$slug" ] || { notify "That name has no letters or digits"; return; }
  url=$(ask "Web app URL (https://...)")
  [ -n "$url" ] || return
  case "$url" in
    http://?*|https://?*) ;;
    *) notify "The URL must start with http:// or https://"; return ;;
  esac
  # The Exec line quotes the URL ("--app=URL", so ? & ; # are fine) and doubles % (field codes). A double quote,
  # backtick, $ or backslash would need Exec escaping, and whitespace is never part of a URL: refuse those.
  case "$url" in
    *[[:space:]\"\'\\\`\$]*) notify "The URL has a space, quote, backslash or \$ in it"; return ;;
  esac
  exec_url=$(printf '%s' "$url" | sed 's/%/%%/g')
  name=$(printf '%s' "$name" | tr -d '\r\n')
  mkdir -p "$APPS_DIR" || return
  f="$APPS_DIR/$slug.desktop"
  {
    echo "[Desktop Entry]"
    echo "Type=Application"
    echo "Version=1.0"
    echo "Name=$name"
    echo "Comment=$name (web app)"
    echo "Exec=sh /opt/linuxdesk/omarchy/browser.sh \"--app=$exec_url\""
    echo "Icon=web-browser"
    echo "Terminal=false"
    echo "Categories=Network;WebBrowser;"
    echo "StartupNotify=true"
  } > "$f" || { notify "Could not write $f"; return; }
  notify "Web app '$name' added: find it under Apps"
}
install() {
  case $(menu "Install" "󰣇  Package" "  Web App" "󰣇  AUR" "  Dev Env" "  Font") in
    *Package) install_package ;;
    *"Web App") install_webapp ;;
    *AUR|*"Dev Env"|*Font) notify "Not available on the phone yet" ;;
  esac
}

# --- Update / System ---------------------------------------------------------------------------------------------
update() {
  try_real omarchy-update || in_terminal 'pacman -Syu'
}
system() {
  case $(menu "System" "  Lock" "󰜉  Restart desktop" "󰍃  Exit desktop") in
    *Lock)
      if try_real omarchy-system-lock; then :
      elif command -v swaylock >/dev/null 2>&1; then swaylock -f
      else notify "swaylock is not installed (Install > Package > swaylock)"; fi ;;
    *Restart*) swaymsg reload >/dev/null ;;
    *Exit*) swaymsg exit >/dev/null ;;
  esac
}

main_menu() {
  case $(menu "Omarchy" "󰀻  Apps" "󰧑  Learn" "  Style" "  Setup" "󰉉  Install" "  Update" "  System") in
    *Apps) exec wofi --show drun ;;
    *Learn) learn ;;
    *Style) style ;;
    *Setup) setup ;;
    *Install) install ;;
    *Update) update ;;
    *System) system ;;
  esac
}

# The real Omarchy menu replaces this whole tree once it is verified on sway (no fallback: Escape in it is a
# normal nonzero exit, and our menu must not pop up after it). `omarchy-menu.sh --ours` skips it.
if [ "${1:-}" = "--ours" ]; then
  shift
elif [ -z "${1:-}" ] && has_real omarchy-menu; then
  exec omarchy-menu
fi
case "${1:-}" in
  apps) exec wofi --show drun ;;
  learn) learn ;;
  style) style ;;
  theme) theme ;;
  setup) setup ;;
  install) install ;;
  update) update ;;
  system) system ;;
  *) main_menu ;;
esac
exit 0
