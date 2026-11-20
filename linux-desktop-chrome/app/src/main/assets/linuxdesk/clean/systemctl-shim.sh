#!/bin/sh
# LinuxDesk "Omarchy (clean)": systemctl under PRoot. There is no systemd PID 1 (and no user manager) in an app, so
# the real systemctl only prints "System has not been booted with systemd". Installed at /usr/local/bin/systemctl,
# which is ahead of /usr/bin on PATH. Omarchy's tools call systemctl 134 times (omarchy 4.5.0): mostly
# `--user is-active / restart / stop / daemon-reload`.
#
# What it does (the "systemctl under proot" pattern):
#   start|restart|reload|try-restart|reload-or-restart <unit>  run the unit's ExecStart directly, in the background,
#                                                            once (a pidfile in $SHIM_DIR keeps it single)
#   stop|kill <unit>                                         kill the process we started for that unit
#   is-active <unit>                                         0 while our process lives, else 3 ("inactive")
#   is-enabled / is-failed / status                          plain answers, never errors that stop a script
#   enable / disable / mask / daemon-reload / reset-failed / import-environment / set-environment  no-op, exit 0
#   show-environment                                         prints the current env (what Omarchy reads from it)
#   reboot / poweroff / suspend / hibernate                  refused plainly (exit 1): a phone app cannot do that
# A unit without an ExecStart we can find (or a .target/.timer/.socket) is a no-op that reports success, so scripts
# keep going. Set LINUXDESK_SYSTEMCTL_LOG=1 to log every call to $SHIM_DIR/calls.log.
SHIM_DIR=${XDG_RUNTIME_DIR:-/tmp}/linuxdesk-units
mkdir -p "$SHIM_DIR" 2>/dev/null
[ "${LINUXDESK_SYSTEMCTL_LOG:-0}" = 1 ] && echo "$(date +%H:%M:%S) systemctl $*" >> "$SHIM_DIR/calls.log"

USER_MODE=0
QUIET=0
NOW=0
VERB=""
UNITS=""
for a in "$@"; do
  case "$a" in
    --user) USER_MODE=1 ;;
    --system|--global|--no-block|--no-pager|--no-legend|--plain|--full|--all|-a|--force|-f) ;;
    --quiet|-q) QUIET=1 ;;
    --now) NOW=1 ;;
    --property=*|-p*|--value|--type=*|--state=*|--runtime|--signal=*|-s*) ;;
    -*) ;;
    *) if [ -z "$VERB" ]; then VERB=$a; else UNITS="$UNITS $a"; fi ;;
  esac
done

# Unit file search, the same order systemd uses (user units first with --user).
unit_file() {
  u=$1
  case "$u" in *.*) ;; *) u=$u.service ;; esac
  if [ "$USER_MODE" = 1 ]; then
    dirs="$HOME/.config/systemd/user /etc/systemd/user /usr/local/lib/systemd/user /usr/lib/systemd/user"
  else
    dirs="/etc/systemd/system /usr/local/lib/systemd/system /usr/lib/systemd/system"
  fi
  for d in $dirs; do [ -f "$d/$u" ] && { echo "$d/$u"; return 0; }; done
  # Template units: name@inst.service -> name@.service
  case "$u" in *@*.*) t="${u%%@*}@.${u##*.}"; for d in $dirs; do [ -f "$d/$t" ] && { echo "$d/$t"; return 0; }; done ;; esac
  return 1
}
key() { echo "$1" | tr '/@' '__'; }
alive() { p=$(cat "$SHIM_DIR/$(key "$1").pid" 2>/dev/null) && [ -n "$p" ] && kill -0 "$p" 2>/dev/null; }

start_unit() {
  u=$1
  alive "$u" && return 0
  f=$(unit_file "$u") || return 0                      # unknown unit: success, nothing to run
  case "$f" in *.target|*.timer|*.socket|*.path|*.mount|*.slice) return 0 ;; esac
  # First ExecStart= line (the main process). Leading -, @, +, ! prefixes are systemd's; drop them. %h = $HOME.
  cmd=$(sed -n 's/^ExecStart=[-@+!:]*//p' "$f" | head -n 1 | sed "s#%h#$HOME#g")
  [ -n "$cmd" ] || return 0
  # Environment= lines, then the command, detached from this shell. Output goes to the unit's own log.
  envs=$(sed -n 's/^Environment=//p' "$f" | tr '\n' ' ')
  log="$SHIM_DIR/$(key "$u").log"
  # shellcheck disable=SC2086  # the unit's command line is word-split on purpose (as systemd does)
  (eval "export $envs" 2>/dev/null; exec setsid sh -c "exec $cmd" >>"$log" 2>&1 </dev/null) &
  echo $! > "$SHIM_DIR/$(key "$u").pid"
  return 0
}
stop_unit() {
  u=$1
  p=$(cat "$SHIM_DIR/$(key "$u").pid" 2>/dev/null) || return 0
  [ -n "$p" ] && kill "$p" 2>/dev/null
  rm -f "$SHIM_DIR/$(key "$u").pid"
  return 0
}

case "$VERB" in
  start) for u in $UNITS; do start_unit "$u"; done; exit 0 ;;
  restart|reload|try-restart|reload-or-restart|try-reload-or-restart)
    for u in $UNITS; do stop_unit "$u"; start_unit "$u"; done; exit 0 ;;
  stop|kill) for u in $UNITS; do stop_unit "$u"; done; exit 0 ;;
  is-active)
    rc=0
    for u in $UNITS; do
      if alive "$u"; then [ "$QUIET" = 1 ] || echo active; else [ "$QUIET" = 1 ] || echo inactive; rc=3; fi
    done
    exit $rc ;;
  is-failed) [ "$QUIET" = 1 ] || echo inactive; exit 1 ;;
  is-enabled)
    rc=1
    for u in $UNITS; do unit_file "$u" >/dev/null && rc=0; done
    [ "$QUIET" = 1 ] || { [ $rc = 0 ] && echo enabled || echo disabled; }
    exit $rc ;;
  enable|disable|mask|unmask|daemon-reload|daemon-reexec|reset-failed|import-environment|set-environment|unset-environment|preset|revert|link|edit)
    if [ "$NOW" = 1 ]; then
      for u in $UNITS; do case "$VERB" in enable) start_unit "$u" ;; disable|mask) stop_unit "$u" ;; esac; done
    fi
    exit 0 ;;
  show-environment) env; exit 0 ;;
  show) for u in $UNITS; do if alive "$u"; then echo "ActiveState=active"; else echo "ActiveState=inactive"; fi; done; exit 0 ;;
  status)
    for u in $UNITS; do if alive "$u"; then echo "$u: active (LinuxDesk shim)"; else echo "$u: inactive (LinuxDesk shim)"; fi; done
    exit 0 ;;
  list-units|list-unit-files|list-timers|list-sockets|list-dependencies) exit 0 ;;
  reboot|poweroff|halt|suspend|hibernate|hybrid-sleep|suspend-then-hibernate|kexec|soft-reboot)
    echo "systemctl $VERB: not possible inside LinuxDesk (an Android app, no systemd). Use Stop in the app." >&2
    exit 1 ;;
  "") echo "LinuxDesk systemctl shim (no systemd under PRoot)"; exit 0 ;;
  *) exit 0 ;;
esac
