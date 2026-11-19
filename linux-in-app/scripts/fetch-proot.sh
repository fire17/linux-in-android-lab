#!/usr/bin/env bash
# Fetch prebuilt, 16 KB-aligned PRoot binaries (Android/Bionic) into app/src/main/jniLibs/<abi>/.
#
# SOURCE (pinned by commit, GPL-2.0-or-later for proot; LGPL-3.0+ talloc; BSD libandroid-shmem):
#   https://github.com/devwithzachary/LinuxOnAndroid  @ d1e0facd2f5bd3607d6e4bf56bc3c5851c14d116
#   (2026-11-13). That repo builds these from vendored termux/proot @ 7266fb3e8516535682f5a9c8f3a7e70f6506eddb
#   via scripts/build_native_libs.sh (see its external/PROVENANCE.md). It ships the same files in
#   a live Play/F-Droid app at targetSdk 36 with useLegacyPackaging = true.
#
# VERIFIED 2026-11-19 (downloaded in memory, hashed, ELF-parsed; nothing kept on disk):
#   - every PT_LOAD segment Align = 0x4000 (16 KB pages OK)
#   - libproot.so: PIE, interp /system/bin/linker64, DT_NEEDED libtalloc.so libandroid-shmem.so libdl libc
#   - libproot_loader.so: static ET_EXEC, no deps (this is PROOT_LOADER)
#
# GPL NOTE: proot is executed as a separate program; its corresponding source is the pinned
# commit above (external/proot). Keep this provenance in the README / About text.
#
# To build from source instead (recommended before any public release), set PROOT_FROM_SOURCE=1:
# this clones the pinned LinuxOnAndroid commit and runs its scripts/build_native_libs.sh with the
# NDK from $ANDROID_NDK_HOME. (UNPROVEN in this project's CI — the prebuilt path is the default.)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI="$ROOT/app/src/main/jniLibs"
COMMIT="d1e0facd2f5bd3607d6e4bf56bc3c5851c14d116"
BASE="https://raw.githubusercontent.com/devwithzachary/LinuxOnAndroid/${COMMIT}/app/src/main/jniLibs"

# abi  file  sha256
PINS=$(cat <<'EOF'
arm64-v8a libproot.so          9ba03118429425f91e59cbeec767f562aabe4451fa594ca46a3a3335e60664f9
arm64-v8a libproot_loader.so   fa14e69d10a1f7059e529fffac289f9735ab21c4a9e0eae9ab9e669873bc4d17
arm64-v8a libproot_loader32.so 64a92f1138b6832da95f4355f2cf84857065c5db96442b5b6c5c219060dccb80
arm64-v8a libtalloc.so         1eace1d69b44cd2bcd9d612656b4ef2085e998df851efbf967eb60f2a83ad0c1
arm64-v8a libandroid-shmem.so  f83febee2d9b047a264e64d011ab6e1f17869826f496fa325623a623be84f753
x86_64    libproot.so          182fabfdc9148172eddd860f1153030f9d75ef4395e03c4d118a9a1b902cbdc8
x86_64    libproot_loader.so   fb6697323e4f4dff2f43effacc35150c9e42e3eb4a978a6d93abb0770a673b0d
x86_64    libproot_loader32.so 1181220ddd4c8c2b62c848efc3aeb3edea2af706f24dac324683997ced1cef7d
x86_64    libtalloc.so         0fdafa2ea1c3a06365ea01bbf209092aa50b1efd6b4dced66ab622f1bab0ec3a
x86_64    libandroid-shmem.so  e70915f5daefb9e2e2f73de28d697cf4a80c44b8852633cc8f0e2bf25db30212
EOF
)

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

if [[ "${PROOT_FROM_SOURCE:-0}" == "1" ]]; then
  : "${ANDROID_NDK_HOME:?PROOT_FROM_SOURCE=1 needs ANDROID_NDK_HOME (NDK r28+)}"
  WORK="$ROOT/.fetch-cache/LinuxOnAndroid"
  rm -rf "$WORK"
  git init -q "$WORK"
  git -C "$WORK" remote add origin https://github.com/devwithzachary/LinuxOnAndroid.git
  git -C "$WORK" fetch -q --depth 1 origin "$COMMIT"
  git -C "$WORK" checkout -q FETCH_HEAD
  (cd "$WORK" && bash scripts/build_native_libs.sh "$ANDROID_NDK_HOME")
  for abi in arm64-v8a x86_64; do
    mkdir -p "$JNI/$abi"
    cp "$WORK/app/src/main/jniLibs/$abi/"lib{proot,proot_loader,proot_loader32,talloc,android-shmem}.so "$JNI/$abi/"
  done
  echo "[fetch-proot] built from source at $COMMIT (hashes not pinned for source builds)"
  exit 0
fi

echo "$PINS" | while read -r abi file sum; do
  [[ -z "$abi" ]] && continue
  dest="$JNI/$abi/$file"
  mkdir -p "$(dirname "$dest")"
  if [[ -f "$dest" ]] && [[ "$(sha256_of "$dest")" == "$sum" ]]; then
    echo "[fetch-proot] ok (cached) $abi/$file"
    continue
  fi
  curl -fsSL --retry 3 -o "$dest.part" "$BASE/$abi/$file"
  got="$(sha256_of "$dest.part")"
  if [[ "$got" != "$sum" ]]; then
    echo "[fetch-proot] SHA256 MISMATCH for $abi/$file: got $got want $sum" >&2
    rm -f "$dest.part"
    exit 1
  fi
  mv "$dest.part" "$dest"
  echo "[fetch-proot] ok $abi/$file ($sum)"
done
