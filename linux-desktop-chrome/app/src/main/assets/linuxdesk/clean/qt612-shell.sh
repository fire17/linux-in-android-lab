#!/bin/sh
# LinuxDesk "Omarchy (clean)": make the REAL Omarchy shell work on Qt 6.12, without touching the package's files.
#
# Why (notes/hyprland/bar-fix.md, root cause 1, proven on the phone 2026-11-20): Qt 6.12 added a QtQuick singleton
# named `Color`. Every Omarchy QML file does `import QtQuick` + `import qs.Commons`, so the bare name `Color` resolves
# to Qt's object and every `Color.bar.*` binding is undefined: the bar renders black. omarchy 4.5.0-3 still ships
# `singleton Color 1.0 Color.qml` (checked 2026-11-20 on the Mac), and ALARM ships qt6-declarative 6.12.0.
#
# What it does: builds a private OMARCHY_PATH at $DST, a symlink farm onto /usr/share/omarchy (bin, themes,
# default, install, ... stay the package's own files) plus a real, patched copy of shell/ with Color renamed to
# ShellColor (the same rename upstream did, Omarchy #14553). Prints the OMARCHY_PATH to use on stdout.
# When no patch is needed (no Qt 6.12 QtQuick Color, or the shell already has ShellColor) it prints the stock path.
# Idempotent: rebuilt only when the omarchy version or this script's REV changes.
#
# usage: qt612-shell.sh [SRC] [DST]     (defaults /usr/share/omarchy /var/lib/linuxdesk/omarchy-qt612)
REV=1
SRC=${1:-/usr/share/omarchy}
DST=${2:-/var/lib/linuxdesk/omarchy-qt612}
QMLTYPES=${LINUXDESK_QMLTYPES:-/usr/lib/qt6/qml/QtQuick/plugins.qmltypes}
say() { echo "[omarchy] clean: $*" >&2; }

[ -f "$SRC/shell/Commons/qmldir" ] || { say "no Omarchy shell at $SRC"; echo "$SRC"; exit 1; }
if ! grep -q '^singleton Color ' "$SRC/shell/Commons/qmldir"; then
  say "the Omarchy shell has no bare Color singleton (already fixed upstream); using $SRC"
  echo "$SRC"; exit 0
fi
# omarchy 4.5.0 fixed it the other way: `import qs.Commons as Commons` + `Commons.Color.<x>` everywhere (336 refs,
# 0 bare ones; checked 2026-11-20). A qualified name is never shadowed, so renaming would BREAK that shell. Patch only
# when bare (unqualified) `Color.<x>` refs exist in code (the 4.0.0.alpha tree of notes/hyprland/bar-fix.md).
bare_refs() { grep -rnE --include='*.qml' --include='*.js' '(^|[^A-Za-z0-9_.])Color\.[A-Za-z_]' "$1" 2>/dev/null \
  | grep -vcE '^[^:]+:[0-9]+:[[:space:]]*//'; }
if [ "$(bare_refs "$SRC/shell")" = 0 ]; then
  say "the Omarchy shell uses only qualified Commons.Color (Qt 6.12 safe); using $SRC"
  echo "$SRC"; exit 0
fi
if [ -z "${LINUXDESK_FORCE_QT612:-}" ] && ! grep -q '"QtQuick/Color 6\.' "$QMLTYPES" 2>/dev/null; then
  say "Qt has no QtQuick Color singleton (older than 6.12); using $SRC"
  echo "$SRC"; exit 0
fi

STAMP="rev=$REV omarchy=$(cat "$SRC/version" 2>/dev/null || echo unknown)"
if [ "$(cat "$DST/.linuxdesk-stamp" 2>/dev/null)" = "$STAMP" ] && [ -f "$DST/shell/Commons/ShellColor.qml" ]; then
  echo "$DST"; exit 0
fi

TMP=$DST.tmp
rm -rf "$TMP"
mkdir -p "$TMP" || { echo "$SRC"; exit 1; }
for e in "$SRC"/* "$SRC"/.[!.]*; do
  [ -e "$e" ] || continue
  [ "${e##*/}" = shell ] && continue
  ln -s "$e" "$TMP/${e##*/}"
done
cp -R "$SRC/shell" "$TMP/shell" || { say "copying the shell failed"; rm -rf "$TMP"; echo "$SRC"; exit 1; }
C=$TMP/shell/Commons
mv "$C/Color.qml" "$C/ShellColor.qml"
sed 's/^singleton Color /singleton ShellColor /; s/ Color\.qml$/ ShellColor.qml/' "$C/qmldir" > "$C/qmldir.new" && mv "$C/qmldir.new" "$C/qmldir"
# Every `Color.<name>` that is not part of a longer identifier or a qualified name (Qt.Color, Commons.Color stay as
# they are), outside // comments. perl is in the closure of `omarchy` (it depends on perl).
n=0
for f in $(grep -rl --include='*.qml' --include='*.js' 'Color\.' "$TMP/shell"); do
  perl -pi -e 'my ($code, $cmt) = /^(.*?)((?:\s\/\/.*)?)$/; $code =~ s/(?<![\w.])Color\.(?=[A-Za-z_])/ShellColor./g; $_ = $code . $cmt . "\n"' "$f"
  n=$((n + 1))
done
left=$(bare_refs "$TMP/shell")
# A qualified `Commons.Color.` left in a mixed tree would now point at a missing type: fail closed.
if grep -rqE --include='*.qml' '[A-Za-z_]\.Color\.[a-z]' "$TMP/shell" 2>/dev/null && ! grep -rqE --include='*.qml' 'Qt\.Color\.' "$TMP/shell"; then
  say "mixed qualified/bare Color refs; not patching (using the stock shell)"; rm -rf "$TMP"; echo "$SRC"; exit 1
fi
if [ ! -f "$C/ShellColor.qml" ] || ! grep -q '^singleton ShellColor ' "$C/qmldir"; then
  say "patch check failed; using the stock shell"; rm -rf "$TMP"; echo "$SRC"; exit 1
fi
echo "$STAMP" > "$TMP/.linuxdesk-stamp"
rm -rf "$DST"
mv "$TMP" "$DST"
say "Qt 6.12 shell patch: Color -> ShellColor in $n files ($left bare refs left), OMARCHY_PATH=$DST"
echo "$DST"
