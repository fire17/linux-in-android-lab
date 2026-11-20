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
# systemd-run / systemd-inhibit / loginctl (notes/systemd/REPORT.md R1: 8 Omarchy tools use systemd-run). ON by
# default (Tami 06:56 AC5559B3: "no approval needed from me"); /etc/linuxdesk/shim-logind containing 0 turns it off.
if [ "${LD_SHIM_LOGIND:-$(cat /etc/linuxdesk/shim-logind 2>/dev/null || echo 1)}" = 1 ]; then
  put systemd-run-shim.sh systemd-run || rc=1
  put systemd-inhibit-shim.sh systemd-inhibit || rc=1
  put loginctl-shim.sh loginctl || rc=1
else
  for n in systemd-run systemd-inhibit loginctl; do
    cmp -s "$C/$n-shim.sh" "$D/$n" 2>/dev/null && rm -f "$D/$n"   # switched off: remove only our own copy
  done
fi
# bubblewrap (glycin image loaders, xdg-desktop-portal): the existing LinuxDesk shim, shared with the other distro.
[ -f /opt/linuxdesk/omarchy/bwrap-shim.sh ] && { cmp -s /opt/linuxdesk/omarchy/bwrap-shim.sh "$D/bwrap" 2>/dev/null \
  || install -m 755 /opt/linuxdesk/omarchy/bwrap-shim.sh "$D/bwrap"; }
# omarchy-android (this phone's Android apps as windows; does nothing until the user pairs): a helper, not a shim.
[ -f /opt/linuxdesk/omarchy/omarchy-android ] && { cmp -s /opt/linuxdesk/omarchy/omarchy-android "$D/omarchy-android" \
  2>/dev/null || install -m 755 /opt/linuxdesk/omarchy/omarchy-android "$D/omarchy-android"; }
# Its launcher entries (Omarchy's Quickshell launcher reads .desktop files; /usr/local/share is on XDG_DATA_DIRS).
mkdir -p /usr/local/share/applications
for f in /opt/linuxdesk/omarchy/android-desktop/linuxdesk-android-*.desktop; do
  [ -f "$f" ] || continue
  cmp -s "$f" "/usr/local/share/applications/${f##*/}" 2>/dev/null || install -m 644 "$f" "/usr/local/share/applications/${f##*/}"
done
exit $rc
