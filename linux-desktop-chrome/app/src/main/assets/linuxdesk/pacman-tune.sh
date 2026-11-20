# pacman.conf tuning shared by setup-omarchy.sh, setup-omarchy-clean.sh and setup-gpu.sh (sourced, never run).
# POSIX sh + POSIX awk (the base Arch rootfs has gawk, no python). Every function is idempotent: running it twice
# changes nothing, and it never adds a second copy of a line.
#
#   pacman_tune [conf]            A5: `ParallelDownloads = 10` under [options] (replaces any existing or commented one);
#                                 our ALARM mirrorlist with de3 (EU, fastest from Israel, 2026-11-20) first; and a fresh
#                                 local keyring key for a prebuilt rootfs (/etc/linuxdesk/prebuilt-rekey, see below)
#   ignore_pkgs <conf> pkg...     A8: merge pkgs into the ONE `IgnorePkg =` line under [options] (keeps other names)
#   unignore_pkgs <conf> pkg...   remove pkgs from it (drops the line once it is empty)
#
# Only `IgnorePkg` lines that start the line are ours to merge; Arch's commented `#IgnorePkg =` template stays as is.

pacman_tune() {
  _c=${1:-/etc/pacman.conf}
  [ -f "$_c" ] || return 0
  _mirrors_eu_first
  _prebuilt_rekey
  grep -qx 'ParallelDownloads = 10' "$_c" && [ "$(grep -c 'ParallelDownloads' "$_c")" = 1 ] && return 0
  awk '
    /^#?[ \t]*ParallelDownloads[ \t]*=/ { next }
    { print }
    /^\[options\]/ && !seen++ { print "ParallelDownloads = 10" }
  ' "$_c" > "$_c.new" && mv "$_c.new" "$_c"
}

# Tami 07:16 (Israel): the US mirrors were first. Measured 2026-11-20 with a 1 MiB range: de3 0.61 MB/s, ca.us 0.43,
# fl.us 0.40; de4/de5/de6/eu/dk/nl/... have no valid HTTPS. Only OUR list (it names fl.us) is reordered: de3 first.
_mirrors_eu_first() {
  _m=/etc/pacman.d/mirrorlist
  [ -f "$_m" ] && grep -q '^Server = https://fl.us.mirror.archlinuxarm.org' "$_m" || return 0
  [ "$(grep -m1 '^Server' "$_m")" = 'Server = https://de3.mirror.archlinuxarm.org/$arch/$repo' ] && return 0
  { grep '^Server = https://de3\.' "$_m"; grep '^Server' "$_m" | grep -v '^Server = https://de3\.'; } > "$_m.new" && mv "$_m.new" "$_m"
}

# A prebuilt rootfs (omarchy-rootfs workflow) ships WITHOUT the keyring's local master secret key (never published).
# The first setup run on the phone makes a new one and re-signs the distro keys, then drops the marker.
_prebuilt_rekey() {
  [ -f /etc/linuxdesk/prebuilt-rekey ] || return 0
  command -v pacman-key >/dev/null 2>&1 || return 0
  echo "[pacman-tune] prebuilt rootfs: creating this phone's own pacman keyring key"
  # --init makes a master key only when none exists and keeps the public keyring (which still verifies packages
  # meanwhile); --populate then re-signs the distro keys with it.
  pacman-key --init >/dev/null 2>&1 || return 0
  pacman-key --populate archlinuxarm >/dev/null 2>&1 || true
  [ -f /usr/share/pacman/keyrings/omarchy.gpg ] && { pacman-key --populate omarchy >/dev/null 2>&1 || true; }
  gpgconf --kill gpg-agent 2>/dev/null || true
  rm -f /etc/linuxdesk/prebuilt-rekey
}

# _pkg_edit <add|del> <conf> pkg...
_pkg_edit() {
  _m=$1; _c=$2; shift 2
  [ -f "$_c" ] || return 0
  awk -v mode="$_m" -v list="$*" '
    /^[ \t]*IgnorePkg[ \t]*=/ { v = $0; sub(/^[^=]*=/, "", v); cur = cur " " v; next }
    { line[++n] = $0 }
    END {
      k = split(list, want, /[ \t]+/)
      for (i = 1; i <= k; i++) if (want[i] != "") w[want[i]] = 1
      all = (mode == "add") ? cur " " list : cur
      m = split(all, p, /[ \t]+/); out = ""
      for (i = 1; i <= m; i++) {
        x = p[i]
        if (x == "" || (x in seen) || (mode == "del" && (x in w))) continue
        seen[x] = 1; out = out (out == "" ? "" : " ") x
      }
      for (i = 1; i <= n; i++) {
        print line[i]
        if (line[i] ~ /^\[options\]/ && !done++ && out != "") print "IgnorePkg = " out
      }
    }
  ' "$_c" > "$_c.new" && mv "$_c.new" "$_c"
}
ignore_pkgs() { _pkg_edit add "$@"; }
unignore_pkgs() { _pkg_edit del "$@"; }
