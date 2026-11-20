#!/bin/sh
# LinuxDesk "Omarchy (clean)": put the PRoot shims in /usr/local/bin (ahead of /usr/bin on PATH). The real packages
# (systemd, uwsm, sudo, bubblewrap) stay installed and untouched; pacman never owns /usr/local/bin, so an upgrade
# never removes a shim. Idempotent: a shim is rewritten only when its content changed. Called by
# setup-omarchy-clean.sh (stage 5) and by start-omarchy-clean.sh at every Start (the app refreshes /opt/linuxdesk).
C=/opt/linuxdesk/clean
D=/usr/local/bin
mkdir -p "$D"
put() {  # put <shim file> <name in /usr/local/bin>
  [ -f "$C/$1" ] || { echo "[omarchy] clean: shim $1 missing in $C"; return 1; }
  cmp -s "$C/$1" "$D/$2" 2>/dev/null || install -m 755 "$C/$1" "$D/$2"
}
rc=0
put systemctl-shim.sh systemctl || rc=1
put uwsm-shim.sh uwsm || rc=1
put uwsm-shim.sh uwsm-app || rc=1
put systemd-cat-shim.sh systemd-cat || rc=1
put sudo-shim.sh sudo || rc=1
put sudo-shim.sh pkexec || rc=1
# bubblewrap (glycin image loaders, xdg-desktop-portal): the existing LinuxDesk shim, shared with the other distro.
[ -f /opt/linuxdesk/omarchy/bwrap-shim.sh ] && { cmp -s /opt/linuxdesk/omarchy/bwrap-shim.sh "$D/bwrap" 2>/dev/null \
  || install -m 755 /opt/linuxdesk/omarchy/bwrap-shim.sh "$D/bwrap"; }
exit $rc
