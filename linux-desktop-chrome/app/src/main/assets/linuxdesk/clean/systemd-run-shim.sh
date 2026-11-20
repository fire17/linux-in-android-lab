#!/bin/sh
# LinuxDesk "Omarchy (clean)": systemd-run under PRoot (no systemd user manager). 8 Omarchy tools launch through
# `systemd-run --user …` (browser, reminders, share, lid inhibit), so without this they fail. Options are dropped;
# the command runs directly:
#   --on-active=N[s|m|h] / --on-calendar=<anything>   run after N (a calendar spec runs now: no timers here)
#   --wait / --scope / --pipe / -t                   run in the foreground, our exit code is the command's
#   anything else                                     run detached (setsid), print a unit-like line like systemd-run
WAIT=0; DELAY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --) shift; break ;;
    --wait|-W|--scope|--pipe|-P|--pty|-t) WAIT=1; shift ;;
    --on-active=*) v=${1#*=}; shift
      case "$v" in *h) DELAY=$(( ${v%h} * 3600 )) ;; *min) DELAY=$(( ${v%min} * 60 )) ;; *m) DELAY=$(( ${v%m} * 60 )) ;;
        *s) DELAY=${v%s} ;; *[!0-9]*) DELAY=0 ;; *) DELAY=$v ;; esac ;;
    --unit|-u|--property|-p|--description|--slice|--setenv|-E|--working-directory|--uid|--gid|--on-calendar|--on-active|--on-boot|--on-startup|--on-unit-active|--on-unit-inactive|--timer-property|--path-property|--socket-property|--service-type|--nice|--machine|-M|--host|-H)
      shift; [ $# -gt 0 ] && shift ;;
    -*) shift ;;
    *) break ;;
  esac
done
[ $# -gt 0 ] || { echo "systemd-run (LinuxDesk shim): no command" >&2; exit 1; }
if [ "$WAIT" = 1 ]; then
  [ "$DELAY" -gt 0 ] 2>/dev/null && sleep "$DELAY"
  exec "$@"
fi
if [ "$DELAY" -gt 0 ] 2>/dev/null; then
  setsid sh -c 'sleep "$0"; exec "$@"' "$DELAY" "$@" >/dev/null 2>&1 < /dev/null &
else
  setsid "$@" >/dev/null 2>&1 < /dev/null &
fi
echo "Running as unit: run-linuxdesk-$!.service" >&2
exit 0
