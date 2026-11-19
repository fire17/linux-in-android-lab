#!/usr/bin/env bash
# Starts a redroid guest for the Stream backend on an arm64 (or x86_64) LINUX host.
# From redroid-doc README (verified 2026-11-19). Needs root on the HOST (kernel modules), not on the phone.
#
# SECURITY: never expose 5555 publicly. Put host + phone on Tailscale/WireGuard and connect the
# app to the host's tailnet IP. redroid's README warns the container — or host — can be compromised.
set -euo pipefail
TAG="${1:-16.0.0_64only-latest}"

sudo apt-get install -y "linux-modules-extra-$(uname -r)" || true
sudo modprobe binder_linux devices="binder,hwbinder,vndbinder"
sudo modprobe ashmem_linux || echo "ashmem_linux missing — redroid 12+ can use memfd (androidboot.use_memfd=true)"

docker run -itd --rm --privileged \
  --name pip-redroid \
  -v "$HOME/redroid-data:/data" \
  -p 127.0.0.1:5555:5555 \
  "redroid/redroid:$TAG" \
  androidboot.use_memfd=true

echo "redroid up on 127.0.0.1:5555. Expose it to the phone ONLY over your tailnet, e.g.:"
echo "  tailscale serve --tcp 5555 tcp://127.0.0.1:5555     (then use the host's 100.x IP in the app)"
