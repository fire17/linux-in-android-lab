#!/bin/sh
# LinuxDesk GPU setup (experimental, OFF by default). Runs INSIDE the guest under PRoot (fake root), once,
# when "GPU acceleration (experimental)" is ON and the phone has /dev/kgsl-3d0 (Adreno). Idempotent.
#
# Installs lfdevs' kgsl-enabled Mesa (Turnip Vulkan + Zink GL + kgsl gallium) so guest X apps and Chromium
# can render on the phone's Adreno GPU through /dev/kgsl-3d0 (ProotRuntime binds host /dev). Stock Alpine/Arch
# Mesa is built with freedreno-kmds=msm,virtio (no kgsl), so it cannot use the phone GPU.
#
# usage: setup-gpu.sh alpine|arch
# On success writes /etc/linuxdesk/gpu-v1 (GpuMode.MARKER) holding the pinned build id. Exit codes:
#   0 ok · 2 bad args/arch · 3 download failed · 4 sha256 mismatch · 5 install failed · 6 post-check failed
#
# PINNED release (checked 2026-11-19; sha256 computed from a fresh download here AND equal to GitHub's
# published asset digest): https://github.com/lfdevs/mesa-for-android-container/releases/tag/mesa-27.0.0-devel-20261025-r2
#   alpine_3.24_arm64.tar.gz  12711053 B  452bc884459dc74e8ba7ac72232628be03b0870a5904bafb8a0a14ee154d2fd4
#   archlinux_arm64.tar       14325760 B  62c58f5ba71aa8a010adc750870c3ff555f2d58cf7d39723e340a6f5a8d83da9
#     inside: mesa-1:27.0.0-92-aarch64.pkg.tar.xz              c622d5f2298bdc87aacc9bd8e3d2700e3ad77c5159546953d541e672a37c3592
#             vulkan-freedreno-1:27.0.0-92-aarch64.pkg.tar.xz  99d0bdcc21ef02a76f3e864d888c548bb5a3bf8a0b58a7c48b26f51830d2178f
#             vulkan-mesa-implicit-layers-1:27.0.0-92-…        28f76c4ae3f2a0412a9ef34aa242d9c6e46a3a8b50475eace551a279c3428ec6
#
# Alpine caveat (lfdevs: "direct-extraction tar.gz for testing"): it is NOT an apk. It overwrites the stock mesa
# files under /usr/lib, and a later `apk upgrade`/`apk fix` of mesa* puts the stock (no-kgsl) files back. So: the
# replaced stock files are backed up once to /var/lib/linuxdesk/gpu-backup.tar, the marker records what we put
# there, and the app re-runs this script whenever kgsl_dri.so is missing. Undo: `setup-gpu.sh --restore alpine`.
# Arch: real pacman packages (epoch 1:27.0.0-92 > ALARM's 1:26.3.1, so `pacman -Syu` does not downgrade them);
# deps resolve from ALARM (llvm-libs 23.1.5 provides libLLVM.so.23.1, checked against the extra db 2026-11-19).
set -eu

BUILD="mesa-27.0.0-devel-20261025-r2"
BASE="https://github.com/lfdevs/mesa-for-android-container/releases/download/$BUILD"
ALPINE_TGZ="mesa-for-android-container_27.0.0-devel-20261025-r2_alpine_3.24_arm64.tar.gz"
ALPINE_SHA="452bc884459dc74e8ba7ac72232628be03b0870a5904bafb8a0a14ee154d2fd4"
ARCH_TAR="mesa-for-android-container_27.0.0-devel-20261025-r2_archlinux_arm64.tar"
ARCH_SHA="62c58f5ba71aa8a010adc750870c3ff555f2d58cf7d39723e340a6f5a8d83da9"
MARKER=/etc/linuxdesk/gpu-v1
WORK=/var/cache/linuxdesk-gpu
BACKUP=/var/lib/linuxdesk/gpu-backup.tar
ADDED=/var/lib/linuxdesk/gpu-added.txt   # files the tarball ADDS (not in stock mesa); --restore deletes them

say() { echo "[gpu-setup] $*"; }

RESTORE=0
if [ "${1:-}" = "--restore" ]; then RESTORE=1; shift; fi
FLAVOR="${1:-}"
case "$FLAVOR" in alpine|arch) ;; *) say "usage: setup-gpu.sh [--restore] alpine|arch"; exit 2 ;; esac

ARCHM="$(uname -m)"
if [ "$ARCHM" != "aarch64" ]; then say "this build is aarch64 only (got $ARCHM); GPU stays off"; exit 2; fi

if [ "$RESTORE" = 1 ]; then
  if [ "$FLAVOR" = alpine ]; then
    if [ -f "$ADDED" ]; then while read -r f; do [ -n "$f" ] && rm -f "/$f"; done < "$ADDED"; say "removed $(wc -l < "$ADDED") added files"; fi
    if [ -f "$BACKUP" ]; then tar -xf "$BACKUP" -C / && say "restored stock mesa files from $BACKUP"; fi
    apk fix --no-progress mesa mesa-gl mesa-egl mesa-gbm mesa-dri-gallium 2>/dev/null || true
  else
    # No --needed: the lfdevs build is NEWER than ALARM's, and -S then reinstalls (downgrades to) the repo version.
    pacman -S --noconfirm mesa vulkan-freedreno || say "pacman could not reinstall stock mesa (restore by hand)"
  fi
  rm -f "$MARKER"; say "restored; GPU mesa removed"; exit 0
fi

if [ -f "$MARKER" ] && grep -q "$BUILD" "$MARKER" 2>/dev/null && [ -e /usr/lib/dri/kgsl_dri.so ] && \
   [ -e /usr/lib/libvulkan_freedreno.so ]; then
  say "already installed: $(cat "$MARKER")"; exit 0
fi

[ -e /dev/kgsl-3d0 ] || say "warning: /dev/kgsl-3d0 is not visible in the guest (no Adreno, or /dev not bound); installing anyway"

mkdir -p "$WORK" /etc/linuxdesk /var/lib/linuxdesk
fetch() {  # url dest sha
  if [ -f "$2" ] && [ "$(sha256sum "$2" | cut -d' ' -f1)" = "$3" ]; then say "cached $(basename "$2")"; return 0; fi
  rm -f "$2"
  n=1
  while :; do
    if command -v curl >/dev/null 2>&1; then curl -fL --retry 2 -o "$2.part" "$1" && break
    else wget -q -O "$2.part" "$1" && break; fi
    [ "$n" -ge 3 ] && { say "download failed 3 times: $1"; return 3; }
    n=$((n + 1)); say "retrying download in 5s"; sleep 5
  done
  got="$(sha256sum "$2.part" | cut -d' ' -f1)"
  if [ "$got" != "$3" ]; then say "sha256 MISMATCH for $(basename "$2"): got $got want $3"; rm -f "$2.part"; return 4; fi
  mv "$2.part" "$2"; say "verified sha256 $(basename "$2")"
}

if [ "$FLAVOR" = alpine ]; then
  # Runtime deps of the lfdevs build (from its ELF NEEDED entries; all in Alpine v3.24 main/community):
  #   llvm22-libs (libLLVM.so.22.1, same major as stock mesa 26.1.6), spirv-tools, libdisplay-info, libxshmfence,
  #   libxxf86vm, wayland-libs-client, libudev-zero, vulkan-loader. mesa-utils (glxinfo) + vulkan-tools (vulkaninfo)
  #   are for the session self-check. Stock mesa packages stay installed (chromium depends on mesa-dri-gallium);
  #   we overwrite their files below.
  PKGS="curl mesa mesa-gl mesa-egl mesa-gbm mesa-gles mesa-dri-gallium llvm22-libs spirv-tools libdisplay-info libxshmfence libxxf86vm wayland-libs-client libudev-zero vulkan-loader mesa-utils vulkan-tools"
  apk add --no-progress $PKGS || say "apk reported errors (often harmless trigger failures under PRoot); verifying ..."
  fetch "$BASE/$ALPINE_TGZ" "$WORK/$ALPINE_TGZ" "$ALPINE_SHA" || exit $?
  # Back up every stock file the tarball will replace (first install only), so --restore can undo it.
  if [ ! -f "$BACKUP" ]; then
    : > "$WORK/replaced.txt"; : > "$ADDED"
    tar -tzf "$WORK/$ALPINE_TGZ" | sed 's#^\./##' | grep -v -e '/$' -e '^$' | while read -r f; do
      if [ -e "/$f" ] || [ -L "/$f" ]; then echo "$f" >> "$WORK/replaced.txt"; else echo "$f" >> "$ADDED"; fi
    done
    if [ -s "$WORK/replaced.txt" ]; then (cd / && tar -cf "$BACKUP" -T "$WORK/replaced.txt") && say "backed up $(wc -l < "$WORK/replaced.txt") stock files; $(wc -l < "$ADDED") new"; fi
  fi
  tar -xzf "$WORK/$ALPINE_TGZ" -C / || { say "extract failed"; exit 5; }
else
  # Arch Linux ARM (Omarchy flavor): real pacman packages. -U with explicit files; pacman verifies each package's
  # own checksums from the archive. They are not signed by an ALARM key, so they're installed from verified local
  # files (sha256 above) rather than a repo.
  fetch "$BASE/$ARCH_TAR" "$WORK/$ARCH_TAR" "$ARCH_SHA" || exit $?
  rm -rf "$WORK/arch" && mkdir -p "$WORK/arch" && tar -xf "$WORK/$ARCH_TAR" -C "$WORK/arch"
  pacman -S --noconfirm --needed mesa-utils vulkan-tools vulkan-icd-loader || say "pacman: self-check tools not installed (continuing)"
  # mesa-docs and vulkan-mesa-layers are optional; install the three that matter (+ layers that freedreno depends on).
  set -- "$WORK"/arch/mesa-1*.pkg.tar.xz "$WORK"/arch/vulkan-freedreno-*.pkg.tar.xz "$WORK"/arch/vulkan-mesa-implicit-layers-*.pkg.tar.xz
  # DisableSandbox: pacman's Landlock sandbox fails under proot (setup-omarchy.sh does the same in pacman.conf).
  # No pipe here: `pacman … | tail` would hide pacman's exit status.
  pacman -U --noconfirm --needed --overwrite '*' "$@" || { say "pacman -U failed"; exit 5; }
fi

# Post-checks: the files that make kgsl/Turnip work must be present.
for f in /usr/lib/dri/kgsl_dri.so /usr/lib/dri/zink_dri.so /usr/lib/libvulkan_freedreno.so /usr/share/vulkan/icd.d/freedreno_icd.aarch64.json; do
  [ -e "$f" ] || { say "missing after install: $f"; exit 6; }
done

echo "$BUILD $FLAVOR $(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$MARKER"
say "done: $(cat "$MARKER")"
