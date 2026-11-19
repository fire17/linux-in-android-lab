#!/bin/sh
# LinuxDesk GPU self-check (experimental GPU mode only). Runs INSIDE the guest at session start, after the
# X server is up, with the same GPU env Chromium gets (ChromiumFlags.GPU_ENV). Prints glxinfo -B and
# vulkaninfo --summary; GpuMode.parseRenderer() turns that into the one session-log line
#   gpu: renderer=<OpenGL renderer string> hardware=<true|false> (via glxinfo|vulkaninfo)
# so `sys.logs` shows whether the phone's GPU is really used (zink/Turnip Adreno) or software (llvmpipe).
# Bounded: each probe is killed after 20 s, since a wedged driver must never hold the session up.
export DISPLAY="${DISPLAY:-:0}"
echo "[gpu-check] marker: $(cat /etc/linuxdesk/gpu-v1 2>/dev/null || echo none)"
if [ -e /dev/kgsl-3d0 ]; then echo "[gpu-check] /dev/kgsl-3d0 visible"; else echo "[gpu-check] /dev/kgsl-3d0 NOT visible in the guest"; fi
run() {  # bounded probe: timeout(1) from busybox/coreutils when present
  if command -v timeout >/dev/null 2>&1; then timeout 20 "$@" 2>&1; else "$@" 2>&1; fi
}
if command -v vulkaninfo >/dev/null 2>&1; then
  run vulkaninfo --summary | grep -E "deviceName|driverName|driverInfo|apiVersion|ERROR" | head -8
else
  echo "[gpu-check] vulkaninfo not installed"
fi
if command -v glxinfo >/dev/null 2>&1; then
  run glxinfo -B | grep -E "OpenGL renderer string|OpenGL version string|OpenGL vendor string|Accelerated|Error|error" | head -8
else
  echo "[gpu-check] glxinfo not installed"
fi
echo "[gpu-check] end"
