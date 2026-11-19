#!/usr/bin/env bash
# Clone termux/termux-x11 at a pinned commit, with its submodules, into third_party/termux-x11.
# settings.gradle.kts then includes its `lorie` directory as the Gradle library module `:lorie`.
#
# PIN (checked 2026-11-19 through the GitHub API, HEAD of master):
#   a00942e81d949070e63fac268f37226838a13839  (2026-11-16, "bump gradle-wrapper from 9.9.0 to 9.9.1")
# lorie has been a pure Android library since upstream commit 37ad5a7 (2026-07-24). Its build.gradle
# finds the host application module through that module's `implementation project(':lorie')`, and bakes
# the host's applicationId into BuildConfig.APPLICATION_ID.
#
# Integrity: a git commit id is a content hash, so this is pinned without a separate sha256.
# Submodules (xserver, pixman, libx11, xkbcomp, ... 16 in total) are pinned by the gitlinks inside that
# commit. Upstream's own CI fetches them the same way: `git submodule update --init --recursive --depth 1`.
#
# Size: about 150-250 MB of sources (mostly xserver + libx11). That is fine on a CI runner. Do NOT run
# this on a nearly full disk.
#
# License: termux-x11 is GPL-3.0. Linking it makes LinuxDesk GPL-3.0 too (see LICENSE and README).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party/termux-x11"
REPO="${TERMUX_X11_REPO:-https://github.com/termux/termux-x11.git}"
COMMIT="${TERMUX_X11_COMMIT:-a00942e81d949070e63fac268f37226838a13839}"

if [[ -d "$DEST/.git" ]] && [[ "$(git -C "$DEST" rev-parse HEAD 2>/dev/null)" == "$COMMIT" ]] \
   && [[ -f "$DEST/lorie/src/main/cpp/xserver/dix/main.c" ]]; then
  echo "[fetch-termux-x11] ok (cached) $COMMIT"
  exit 0
fi

rm -rf "$DEST"
mkdir -p "$DEST"
git -C "$DEST" init -q
git -C "$DEST" remote add origin "$REPO"
git -C "$DEST" fetch -q --depth 1 origin "$COMMIT"
git -C "$DEST" checkout -q FETCH_HEAD
got="$(git -C "$DEST" rev-parse HEAD)"
if [[ "$got" != "$COMMIT" ]]; then
  echo "[fetch-termux-x11] checked out $got, expected $COMMIT" >&2
  exit 1
fi

echo "[fetch-termux-x11] fetching submodules (depth 1) ..."
git -C "$DEST" submodule update --init --recursive --jobs 8 --depth 1

# Sanity checks: what settings.gradle.kts and app/build.gradle.kts rely on.
for f in lorie/build.gradle lorie/version.gradle shell-loader/stub/build.gradle \
         lorie/src/main/java/com/termux/x11/CmdEntryPoint.java \
         lorie/src/main/java/com/termux/x11/MainActivity.java \
         lorie/src/main/java/com/termux/x11/LorieApp.java \
         lorie/src/main/cpp/xserver/dix/main.c; do
  [[ -f "$DEST/$f" ]] || { echo "[fetch-termux-x11] missing $f after checkout" >&2; exit 1; }
done

# app/build.gradle.kts hard-codes this NDK version; fail early if upstream ever changes it.
ndk="$(sed -n 's/.*termuxX11NdkVersion *= *"\([^"]*\)".*/\1/p' "$DEST/lorie/version.gradle")"
if [[ "$ndk" != "29.0.14206865" ]]; then
  echo "[fetch-termux-x11] upstream NDK is '$ndk' but app/build.gradle.kts pins 29.0.14206865; update it" >&2
  exit 1
fi

echo "[fetch-termux-x11] ok $COMMIT ($(du -sh "$DEST" | cut -f1))"
