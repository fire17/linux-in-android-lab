#!/bin/sh
# LinuxDesk "Omarchy (clean)": uwsm / uwsm-app under PRoot. uwsm runs every app as a transient systemd user unit,
# and there is no systemd here. Omarchy's tools call `uwsm-app -- <cmd>` 72 times (omarchy 4.5.0); uwsm itself only
# for `uwsm stop`, `uwsm check` and `uwsm app`. Installed twice, at /usr/local/bin/uwsm and /usr/local/bin/uwsm-app
# (ahead of /usr/bin on PATH), so the real uwsm package stays installed and untouched.
#
#   uwsm-app [-s a|b|s|...] [-t scope|service] [-a name] [-u unit] [-T] -- cmd args   ->  exec cmd args
#   uwsm app <same>                                                                ->  exec cmd args
#   uwsm stop                                                                      ->  hyprctl dispatch exit
#   uwsm check is-active|may-start                                                 ->  1 (we are not uwsm)
#   uwsm start ...                                                                 ->  refused: start-omarchy-clean.sh
#                                                                                      starts Hyprland directly
#   anything else                                                                  ->  exit 0
run_app() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --) shift; break ;;
      -s|-t|-a|-u|-d|-S) shift 2 ;;
      -T|-F|-g) shift ;;
      -*) shift ;;
      *) break ;;
    esac
  done
  [ $# -gt 0 ] || exit 0
  # A desktop entry id (foo.desktop) instead of a command: run its Exec line without field codes.
  case "$1" in
    *.desktop)
      for d in "$HOME/.local/share/applications" /usr/local/share/applications /usr/share/applications; do
        if [ -f "$d/$1" ]; then
          ex=$(sed -n 's/^Exec=//p' "$d/$1" | head -n 1 | sed 's/ %[fFuUdDnNickvm]//g')
          shift
          # shellcheck disable=SC2086  # Exec= is word-split as the desktop spec says
          exec sh -c "exec $ex \"\$@\"" sh "$@"
        fi
      done
      exit 1 ;;
  esac
  exec "$@"
}

case "${0##*/}" in
  uwsm-app) run_app "$@" ;;
esac
case "${1:-}" in
  app) shift; run_app "$@" ;;
  stop) exec hyprctl dispatch exit ;;
  check) exit 1 ;;
  start) echo "uwsm start: not used in LinuxDesk; /opt/linuxdesk/start-omarchy-clean.sh starts Hyprland directly" >&2; exit 1 ;;
  *) exit 0 ;;
esac
