#!/bin/sh
# LinuxDesk "Omarchy (clean)": sudo / pkexec under PRoot. The guest already runs as (fake) root, and sudo's setuid
# bit does not survive the app's rootfs extraction, so the real sudo refuses ("must be owned by uid 0 and have the
# setuid bit set"). Omarchy's tools call sudo in 131 files and pkexec in 10 (omarchy 4.5.0). This drops the options
# and runs the command as we are. Installed at /usr/local/bin/sudo and /usr/local/bin/pkexec (ahead of /usr/bin).
# `sudo -u <user>` runs as root anyway (there is one user here) and says so on stderr once.
while [ $# -gt 0 ]; do
  case "$1" in
    -u|--user|-g|--group|-p|--prompt|-C|--close-from|-D|--chdir|-h|--host|-r|--role|-t|--type|-U|--other-user)
      [ "$1" = -u ] || [ "$1" = --user ] && [ "$2" != root ] && echo "sudo shim: running as root, not $2 (LinuxDesk)" >&2
      shift 2 ;;
    -v|--validate|-k|--reset-timestamp|-K|--remove-timestamp) [ $# -eq 1 ] && exit 0; shift ;;
    -l|--list) echo "(LinuxDesk) you may run any command"; exit 0 ;;
    --) shift; break ;;
    -*) shift ;;
    *) break ;;
  esac
done
[ $# -gt 0 ] || exit 0
# `sudo VAR=value cmd`: sudo's env assignments.
while [ $# -gt 0 ]; do
  case "$1" in [A-Za-z_]*=*) export "${1?}"; shift ;; *) break ;; esac
done
exec "$@"
