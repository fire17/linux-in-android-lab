# shellcheck shell=bash
# Shared helpers for scripts/fetch-*.sh. Runs on GitHub's ubuntu runners (and macOS for local use).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${PIP_FETCH_CACHE:-$ROOT/.fetch-cache}"
mkdir -p "$CACHE"

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

# fetch URL SHA256 DEST — download (cached) and verify. SHA256="TODO" fails loudly.
fetch() {
  local url="$1" want="$2" dest="$3"
  if [[ "$want" == TODO* ]]; then
    echo "ERROR: no pinned sha256 for $url — refusing to use an unverified binary" >&2
    exit 3
  fi
  local cached="$CACHE/$(basename "$url")"
  if [[ ! -f "$cached" ]] || [[ "$(sha256_of "$cached")" != "$want" ]]; then
    echo "→ downloading $url"
    curl -fL --retry 3 --retry-delay 2 -o "$cached.part" "$url"
    mv "$cached.part" "$cached"
  fi
  local got; got="$(sha256_of "$cached")"
  if [[ "$got" != "$want" ]]; then
    echo "ERROR: sha256 mismatch for $url" >&2
    echo "  want $want" >&2
    echo "  got  $got" >&2
    exit 4
  fi
  mkdir -p "$(dirname "$dest")"
  cp "$cached" "$dest"
  echo "✓ $(basename "$dest")  sha256=$got"
}
