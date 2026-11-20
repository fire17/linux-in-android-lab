#!/bin/sh
# LinuxDesk "Omarchy (Hyprland)": commands Omarchy's tools call that this guest does not have. start-hyprland.sh
# installs this file under each name below into a shim dir on Hyprland's PATH (ahead of /usr/bin), so the name ($0)
# picks the behaviour. Found by the sweep lane 2026-11-20 (notes/gap/SWEEP.md): the Omarchy tree's launchers go
# through `uwsm-app -- xdg-terminal-exec …`, `chromium …` and `nautilus …`, none of which exist here, so the
# terminal-in-a-window tools (Setup > Default Agent, installers, TUIs), web apps and the file manager did nothing.
#
#   xdg-terminal-exec [--app-id=X] [--title=T] [--dir=D] [-e] cmd …  ->  foot (alacritty when foot is missing)
#   xdg-terminal-exec --print-id                                    ->  foot.desktop
#   chromium <args>                                                 ->  google-chrome-stable --ozone-platform=wayland
#   nautilus [--new-window] [path]                                  ->  thunar [path]
case "${0##*/}" in
  xdg-terminal-exec)
    app=""; title=""; dir=""
    while [ $# -gt 0 ]; do
      case "$1" in
        --print-id) if command -v foot >/dev/null 2>&1; then echo foot.desktop; else echo Alacritty.desktop; fi; exit 0 ;;
        --app-id=*) app=${1#*=}; shift ;;
        --title=*) title=${1#*=}; shift ;;
        --dir=*) dir=${1#*=}; shift ;;
        -e|--) shift; break ;;
        -*) shift ;;
        *) break ;;
      esac
    done
    [ -n "$dir" ] && [ -d "$dir" ] && cd "$dir" 2>/dev/null
    if command -v foot >/dev/null 2>&1; then
      set -- foot ${app:+--app-id="$app"} ${title:+--title="$title"} "$@"
    elif command -v alacritty >/dev/null 2>&1; then
      [ $# -gt 0 ] && set -- -e "$@"
      set -- alacritty ${app:+--class="$app"} ${title:+--title="$title"} "$@"
    else
      echo "xdg-terminal-exec (LinuxDesk): no terminal (foot/alacritty) installed" >&2; exit 1
    fi
    exec "$@" ;;
  chromium)
    for b in google-chrome-stable google-chrome; do
      command -v "$b" >/dev/null 2>&1 || continue
      case " $* " in *" --ozone-platform="*) exec "$b" "$@" ;; esac
      exec "$b" --ozone-platform=wayland "$@"
    done
    echo "chromium (LinuxDesk): no browser installed" >&2; exit 127 ;;
  nautilus)
    while [ $# -gt 0 ]; do case "$1" in -*) shift ;; *) break ;; esac; done
    command -v thunar >/dev/null 2>&1 || { echo "nautilus (LinuxDesk): thunar is not installed" >&2; exit 1; }
    exec thunar "$@" ;;
  *) echo "hypr-shim: unknown name ${0##*/}" >&2; exit 1 ;;
esac
