#!/bin/sh
# LinuxDesk: desktop icons under Hyprland (Tami 06:19: "no icons on the desktop"). The data side of
# omarchy/desktop-icons/shell.qml, a separate small Quickshell instance on the Bottom layer (the same toolkit and layer
# family as Omarchy's own wallpaper). NOT pcmanfm-qt --desktop: that took the whole session down at 06:47.
#   desktop-icons.sh seed        put a few launchers in ~/Desktop once (never overwrites, never re-adds a deleted one)
#   desktop-icons.sh list        one line per entry: <file>\t<name>\t<icon>
#   desktop-icons.sh open <file> run a .desktop entry (Exec without field codes; Type=Link opens its URL in thunar);
#                                any other file or folder opens with xdg-open (thunar for folders)
D="${XDG_DESKTOP_DIR:-$HOME/Desktop}"
# key <Key> <file>: the value of Key= inside the [Desktop Entry] group (not an action's, not a translation).
key() { awk -v k="$1=" '/^\[/ { g = ($0 == "[Desktop Entry]") } g && index($0, k) == 1 { print substr($0, length(k) + 1); exit }' "$2"; }
case "${1:-}" in
  seed)
    mkdir -p "$D" || exit 1
    [ -e "$D/.linuxdesk-seeded" ] && exit 0
    for f in thunar.desktop google-chrome.desktop foot.desktop Alacritty.desktop btop.desktop; do
      [ -f "/usr/share/applications/$f" ] && [ ! -e "$D/$f" ] && cp "/usr/share/applications/$f" "$D/$f" && chmod +x "$D/$f"
    done
    [ -e "$D/Home.desktop" ] || printf '[Desktop Entry]\nType=Link\nName=Home\nIcon=user-home\nURL=file://%s\n' "$HOME" > "$D/Home.desktop"
    : > "$D/.linuxdesk-seeded" ;;
  list)
    for f in "$D"/*; do
      [ -e "$f" ] || continue
      case "$f" in
        *.desktop)
          [ "$(key NoDisplay "$f")" = true ] && continue
          n=$(key Name "$f"); i=$(key Icon "$f")
          printf '%s\t%s\t%s\n' "$f" "${n:-$(basename "$f" .desktop)}" "${i:-application-x-executable}" ;;
        *) if [ -d "$f" ]; then i=folder; else i=text-x-generic; fi
          printf '%s\t%s\t%s\n' "$f" "$(basename "$f")" "$i" ;;
      esac
    done ;;
  open)
    f=${2:-}; [ -e "$f" ] || exit 1
    case "$f" in
      *.desktop)
        if [ "$(key Type "$f")" = Link ]; then
          u=$(key URL "$f"); u=${u#file://}
          exec setsid thunar "$u" >/dev/null 2>&1 < /dev/null
        fi
        ex=$(key Exec "$f" | sed 's/ %[fFuUdDnNickvm]//g; s/%%/%/g')
        [ -n "$ex" ] || exit 1
        # shellcheck disable=SC2086  # Exec= is word-split as the desktop spec says
        if [ "$(key Terminal "$f")" = true ]; then exec setsid xdg-terminal-exec sh -c "$ex" >/dev/null 2>&1 < /dev/null; fi
        exec setsid sh -c "exec $ex" >/dev/null 2>&1 < /dev/null ;;
      *) if [ -d "$f" ]; then exec setsid thunar "$f" >/dev/null 2>&1 < /dev/null; fi
        exec setsid xdg-open "$f" >/dev/null 2>&1 < /dev/null ;;
    esac ;;
  *) echo "usage: desktop-icons.sh seed|list|open <file>" >&2; exit 2 ;;
esac
