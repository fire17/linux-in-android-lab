# pacman.conf tuning shared by setup-omarchy.sh, setup-omarchy-clean.sh and setup-gpu.sh (sourced, never run).
# POSIX sh + POSIX awk (the base Arch rootfs has gawk, no python). Every function is idempotent: running it twice
# changes nothing, and it never adds a second copy of a line.
#
#   pacman_tune [conf]            A5: `ParallelDownloads = 8` under [options] (replaces any existing or commented one)
#   ignore_pkgs <conf> pkg...     A8: merge pkgs into the ONE `IgnorePkg =` line under [options] (keeps other names)
#   unignore_pkgs <conf> pkg...   remove pkgs from it (drops the line once it is empty)
#
# Only `IgnorePkg` lines that start the line are ours to merge; Arch's commented `#IgnorePkg =` template stays as is.

pacman_tune() {
  _c=${1:-/etc/pacman.conf}
  [ -f "$_c" ] || return 0
  grep -qx 'ParallelDownloads = 8' "$_c" && [ "$(grep -c 'ParallelDownloads' "$_c")" = 1 ] && return 0
  awk '
    /^#?[ \t]*ParallelDownloads[ \t]*=/ { next }
    { print }
    /^\[options\]/ && !seen++ { print "ParallelDownloads = 8" }
  ' "$_c" > "$_c.new" && mv "$_c.new" "$_c"
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
