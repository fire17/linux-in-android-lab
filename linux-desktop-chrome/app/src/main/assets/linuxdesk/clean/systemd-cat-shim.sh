#!/bin/sh
# LinuxDesk "Omarchy (clean)": systemd-cat under PRoot. There is no journald, and the real systemd-cat then fails, so
# omarchy-launch-shell (`systemd-cat -t omarchy-shell -- quickshell -n -p $OMARCHY_PATH/shell`) never starts the
# Omarchy shell (no bar, no menus, no wallpaper). This drops systemd-cat's options and execs the command, its
# output appended to $XDG_RUNTIME_DIR/<tag>.log instead of the journal. Installed at /usr/local/bin/systemd-cat.
TAG=journal
while [ $# -gt 0 ]; do
  case "$1" in
    -t|--identifier) TAG=$2; shift 2 ;;
    --identifier=*) TAG=${1#*=}; shift ;;
    -p|--priority|--stderr-priority|--level-prefix) shift 2 ;;
    --) shift; break ;;
    -*) shift ;;
    *) break ;;
  esac
done
LOG=${XDG_RUNTIME_DIR:-/tmp}/$TAG.log
if [ $# -eq 0 ]; then cat >> "$LOG"; exit 0; fi
exec "$@" >> "$LOG" 2>&1
