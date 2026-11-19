#!/usr/bin/env bash
# Fetches the scrcpy server (Apache-2.0, Genymobile) that the Stream backend pushes to the guest.
# Version MUST equal pip.scrcpyVersion in gradle.properties (server aborts on version mismatch).
# sha256 taken from the GitHub release asset digest of v5.0.1 (verified 2026-11-19).
source "$(dirname "$0")/_lib.sh"

VERSION="5.0.1"
SHA256="764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536"
URL="https://github.com/Genymobile/scrcpy/releases/download/v${VERSION}/scrcpy-server-v${VERSION}"

prop_version="$(grep '^pip.scrcpyVersion=' "$ROOT/gradle.properties" | cut -d= -f2)"
if [[ "$prop_version" != "$VERSION" ]]; then
  echo "ERROR: gradle.properties pip.scrcpyVersion=$prop_version but this script fetches $VERSION" >&2
  exit 5
fi

fetch "$URL" "$SHA256" "$ROOT/app/src/main/assets/scrcpy-server.jar"
