#!/usr/bin/env bash
# Fetch the Alpine Linux minirootfs tarballs into app/src/main/assets/rootfs/.
# They are bundled in the APK (data, not code) and extracted to filesDir/rootfs on first launch.
#
# Pinned to Alpine 3.24.4 (released 2026-11-05). sha256 values copied 2026-11-19 from the
# official release manifests:
#   https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/latest-releases.yaml
#   https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/x86_64/latest-releases.yaml
# HEAD-checked 2026-11-19: both URLs return 200 with the listed content-length.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/rootfs"
VER="3.24.4"
BRANCH="v3.24"
MIRROR="${ALPINE_MIRROR:-https://dl-cdn.alpinelinux.org/alpine}"

# asset-name  alpine-arch  sha256  size
PINS=$(cat <<'EOF'
alpine-aarch64.tar.gz aarch64 4b14c18f7e90a063b61462ea1462a5732c154bf6adad594974fe6487cd98a8de 4029843
alpine-x86_64.tar.gz  x86_64  cdaa38cb8de3537967ee5b6c9b6cd00cc962da2b018e6eea48696bb274923012 3704088
EOF
)

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

mkdir -p "$OUT"
echo "$PINS" | while read -r name arch sum size; do
  [[ -z "$name" ]] && continue
  dest="$OUT/$name"
  if [[ -f "$dest" ]] && [[ "$(sha256_of "$dest")" == "$sum" ]]; then
    echo "[fetch-rootfs] ok (cached) $name"
    continue
  fi
  url="$MIRROR/$BRANCH/releases/$arch/alpine-minirootfs-$VER-$arch.tar.gz"
  curl -fsSL --retry 3 -o "$dest.part" "$url"
  got="$(sha256_of "$dest.part")"
  if [[ "$got" != "$sum" ]]; then
    echo "[fetch-rootfs] SHA256 MISMATCH for $name: got $got want $sum ($url)" >&2
    rm -f "$dest.part"
    exit 1
  fi
  mv "$dest.part" "$dest"
  echo "[fetch-rootfs] ok $name ($size bytes, $sum)"
done

# Version marker read by the app so a future rootfs bump re-extracts.
echo "alpine-$VER" > "$OUT/VERSION"
