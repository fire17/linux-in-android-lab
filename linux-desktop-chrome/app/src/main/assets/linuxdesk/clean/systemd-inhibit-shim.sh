#!/bin/sh
# LinuxDesk "Omarchy (clean)": systemd-inhibit under PRoot. There is no logind, so nothing to inhibit (Android owns
# sleep): run the wrapped command as is. --list prints an empty table.
while [ $# -gt 0 ]; do
  case "$1" in
    --) shift; break ;;
    --list) exit 0 ;;
    --what|--who|--why|--mode) shift; [ $# -gt 0 ] && shift ;;
    -*) shift ;;
    *) break ;;
  esac
done
[ $# -gt 0 ] || exec sleep infinity
exec "$@"
