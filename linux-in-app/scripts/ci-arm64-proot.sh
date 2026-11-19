#!/usr/bin/env bash
# arm64 proot smoke test for CI (job "arm64-proot" in .github/workflows/build.yml).
#
# Runs INSIDE termux/termux-docker:aarch64 on a native arm64 GitHub runner (ubuntu-24.04-arm).
# That image provides Android's bionic userland (/system/bin/linker64, libc.so), so the EXACT
# arm64-v8a jniLibs files the APK ships (libproot.so + loader + talloc + shmem) can run, with the
# EXACT Alpine aarch64 rootfs asset, using the same proot flags and env as ProotLauncher.
#
# What this PROVES if green: the arm64 binaries + our libtalloc soname patch load under bionic;
# proot + its loader run aarch64 musl binaries; aarch64 musl fork (= clone(SIGCHLD), there is no
# fork syscall on aarch64) works under this proot.
# What it does NOT prove: Android's app sandbox. The runner kernel is a stock Linux kernel, NOT an
# Android kernel, so there is no zygote seccomp policy and no untrusted_app SELinux domain here.
# That still needs a real arm64 phone (or an arm64 emulator, which hosted runners can't provide).
set -u

W="${WORK_DIR:-/work}"
J="$W/app/src/main/jniLibs/arm64-v8a"
ROOTFS_TGZ="$W/app/src/main/assets/rootfs/alpine-aarch64.rootfs.bin"
T="$(mktemp -d)"
mkdir -p "$T/rootfs" "$T/tmp" "$T/l2s"

echo "host: $(uname -a)"
echo "host uname -m: $(uname -m)"
ls -l "$J"

tar -xzf "$ROOTFS_TGZ" -C "$T/rootfs" || { echo "FATAL: rootfs extract failed"; exit 1; }
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$T/rootfs/etc/resolv.conf"

# Same host env as ProotLauncher.hostEnv() (termux-exec's LD_PRELOAD removed: it would rewrite paths).
run_step() {
  local name="$1" script="$2" out rc
  out="$(env -u LD_PRELOAD \
      LD_LIBRARY_PATH="$J" \
      PROOT_LOADER="$J/libproot_loader.so" \
      PROOT_LOADER_32="$J/libproot_loader32.so" \
      PROOT_TMP_DIR="$T/tmp" \
      PROOT_L2S_DIR="$T/l2s" \
      PROOT_NO_SECCOMP=1 \
      "$J/libproot.so" --kill-on-exit --link2symlink -0 -r "$T/rootfs" \
        -b /dev -b /proc -b /sys \
        -b /proc/self/fd:/dev/fd \
        -b /proc/self/fd/0:/dev/stdin -b /proc/self/fd/1:/dev/stdout -b /proc/self/fd/2:/dev/stderr \
        -b "$T/l2s" -b "$J" -w /root \
        /usr/bin/env -i HOME=/root USER=root SHELL=/bin/sh TERM=xterm LANG=C.UTF-8 \
          PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        /bin/sh -c "$script" 2>&1)"
  rc=$?
  echo "STEP $name exit=$rc :: $(printf '%s\n' "$out" | grep -m1 -v '^[[:space:]]*$' || echo '(no output)')"
  printf '%s\n' "$out" | head -20 | sed "s/^/  $name| /"
  [[ $rc -eq 0 ]] || FAILED="$FAILED $name"
}

FAILED=""
# Keep in sync with ProotLauncher.SELF_TEST_STEPS.
run_step builtin-echo 'echo builtin-ok'
run_step exec-uname-m 'exec /bin/uname -m'
run_step fork-probe   '/bin/true; echo fork-ok'
run_step uname-a      'uname -a'
run_step os-release   'cat /etc/os-release'
run_step id           'id'

if [[ -n "$FAILED" ]]; then echo "SUMMARY arm64 FAILED:$FAILED"; exit 1; fi
echo "SUMMARY arm64 all steps ok"
