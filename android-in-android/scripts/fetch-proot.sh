#!/usr/bin/env bash
# Fetches PRoot (GPL-2.0, termux/proot fork) + its two shared-lib deps from the Termux apt repo,
# for arm64-v8a (phones) and x86_64 (emulators), and lays them out as jniLibs:
#
#   jniLibs/<abi>/libproot.so         <- usr/bin/proot            (an executable, renamed)
#   jniLibs/<abi>/libproot_loader.so  <- usr/libexec/proot/loader (static executable, renamed)
#   jniLibs/<abi>/libtalloc.so        <- usr/lib/libtalloc.so.2.5.0
#   jniLibs/<abi>/libandroid-shmem.so <- usr/lib/libandroid-shmem.so
#
# proot's DT_NEEDED says "libtalloc.so.2"; jniLibs only packages lib*.so, so the app creates a
# libtalloc.so.2 symlink at runtime (ProotRunner.libShimDir). If patchelf is available we ALSO
# rewrite NEEDED to libtalloc.so and RUNPATH to $ORIGIN, which makes the shim unnecessary.
#
# All .deb sha256 values come from the Termux "stable main" Packages index (2026-11-19) and were
# re-hashed locally for the aarch64 proot .deb. proot's PT_LOAD segments are 0x4000 (16 KB) aligned.
#
# GPL-2.0 / GPL-3.0 notice: proot (GPL-2.0) and talloc (LGPL-3.0+, Termux marks GPL-3.0) are
# shipped as separate programs/libraries. Corresponding source: github.com/termux/proot and
# github.com/termux/termux-packages (packages/proot, packages/libtalloc). See README §Licenses.
source "$(dirname "$0")/_lib.sh"

REPO="https://packages.termux.dev/apt/termux-main/pool/main"
WORK="$CACHE/proot-work"
rm -rf "$WORK"; mkdir -p "$WORK"

# sha_for DEB — pinned sha256 per .deb (plain function: macOS ships bash 3.2, no assoc arrays).
sha_for() {
  case "$1" in
    proot_5.1.107.102_aarch64.deb)      echo 10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030 ;;
    libtalloc_2.5.0_aarch64.deb)        echo 556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7 ;;
    libandroid-shmem_0.7_aarch64.deb)   echo 0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6 ;;
    proot_5.1.107.102_x86_64.deb)       echo 2497064226c15971be8131c7facead94a06c225347796326a383c832dca57758 ;;
    libtalloc_2.5.0_x86_64.deb)         echo b8c6d95f20075dc1f9ec6573575b2444e8d526e48e0d8d6d5cf4e071e6e06530 ;;
    libandroid-shmem_0.7_x86_64.deb)    echo ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797 ;;
    *) echo TODO ;;
  esac
}
path_for() {
  case "$1" in
    proot) echo p/proot ;; libtalloc) echo libt/libtalloc ;; libandroid-shmem) echo liba/libandroid-shmem ;;
  esac
}

# extract_deb DEB OUTDIR — a .deb is an ar archive; bsdtar (macOS) and GNU ar both handle it.
extract_deb() {
  local deb="$1" out="$2"
  mkdir -p "$out"
  ( cd "$out"
    # GNU ar (CI) extracts members; macOS ar mis-parses GNU-style names ("data.tar.xz/"), so
    # fall back to bsdtar, which reads ar archives natively.
    ar x "$deb" 2>/dev/null || true
    ls data.tar.* >/dev/null 2>&1 || tar xf "$deb"
    data="$(ls data.tar.* | head -1)"
    tar xf "$data" )
}

for pair in "aarch64:arm64-v8a" "x86_64:x86_64"; do
  arch="${pair%%:*}"; abi="${pair##*:}"
  dst="$ROOT/app/src/main/jniLibs/$abi"
  mkdir -p "$dst"
  for pkg in proot libtalloc libandroid-shmem; do
    case "$pkg" in
      proot) ver=5.1.107.102 ;; libtalloc) ver=2.5.0 ;; libandroid-shmem) ver=0.7 ;;
    esac
    deb="${pkg}_${ver}_${arch}.deb"
    fetch "$REPO/$(path_for "$pkg")/$deb" "$(sha_for "$deb")" "$WORK/$deb"
    extract_deb "$WORK/$deb" "$WORK/x-$arch-$pkg"
  done
  P="data/data/com.termux/files/usr"
  cp "$WORK/x-$arch-proot/$P/bin/proot"                    "$dst/libproot.so"
  cp "$WORK/x-$arch-proot/$P/libexec/proot/loader"         "$dst/libproot_loader.so"
  cp "$WORK/x-$arch-libtalloc/$P/lib/libtalloc.so.2.5.0"   "$dst/libtalloc.so"
  cp "$WORK/x-$arch-libandroid-shmem/$P/lib/libandroid-shmem.so" "$dst/libandroid-shmem.so"
  chmod 755 "$dst"/*.so

  # Optional NEEDED/RUNPATH rewrite. patchelf can append a new PT_LOAD when .dynstr grows, so we
  # force 16 KB pages and VERIFY alignment afterwards; on any doubt we keep the pristine Termux
  # binaries and rely on the runtime libtalloc.so.2 symlink shim (ProotRunner.libShimDir).
  if command -v patchelf >/dev/null 2>&1 && command -v python3 >/dev/null 2>&1; then
    cp "$dst/libproot.so" "$WORK/libproot.orig"; cp "$dst/libtalloc.so" "$WORK/libtalloc.orig"
    if patchelf --page-size 16384 --replace-needed libtalloc.so.2 libtalloc.so "$dst/libproot.so" \
       && patchelf --page-size 16384 --set-rpath '$ORIGIN' "$dst/libproot.so" \
       && patchelf --page-size 16384 --set-soname libtalloc.so "$dst/libtalloc.so" \
       && python3 "$ROOT/scripts/elf_align_check.py" 16384 "$dst/libproot.so" "$dst/libtalloc.so"; then
      echo "✓ patchelf: NEEDED/RUNPATH rewritten for $abi, still 16 KB aligned"
    else
      echo "! patchelf result rejected for $abi — restoring pristine binaries (shim will be used)" >&2
      cp "$WORK/libproot.orig" "$dst/libproot.so"; cp "$WORK/libtalloc.orig" "$dst/libtalloc.so"
    fi
  else
    echo "! patchelf/python3 not found: relying on the runtime libtalloc.so.2 symlink shim" >&2
  fi
  echo "✓ jniLibs/$abi: $(ls "$dst" | tr '\n' ' ')"
done
rm -rf "$WORK"
