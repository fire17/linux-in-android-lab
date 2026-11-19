#!/usr/bin/env bash
# Fetch prebuilt PRoot binaries (Android/Bionic) into app/src/main/jniLibs/<abi>/.
#
# SOURCE (since 2026-11-19, run 4): the official Termux packages, i.e. termux/proot built by
# termux-packages' packages/proot/build.sh (GPL-2.0-or-later proot; LGPL-3.0+ talloc; BSD shmem):
#   proot            5.1.107.102  = termux/proot tag v5.1.107.102 (52d65a4dc140ca554d241a1438ddd4f6b00c9813)
#   libtalloc        2.5.0
#   libandroid-shmem 0.7
#
# WHY NOT LinuxOnAndroid@d1e0facd any more: its vendored proot is termux/proot@7266fb3e, which
# PREDATES termux/proot commit 9bd53079 "seccomp: run fork(2) as clone(2) when the outer policy
# rejects it" (+ e2d843ee, first released in tag v5.1.107.98). Without it, on an x86_64 Android app
# every musl fork(2) dies with ENOSYS: CI run 44508979139 printed exactly
#   /bin/sh: can't fork: Function not implemented
# (see README "x86_64 fork / ENOSYS"). 5.1.107.102 contains both fix commits (compare: ahead 42/43,
# behind 0).
#
# HOW: the .deb files are ar archives (BSD `ar` on macOS can't read them, so a tiny Python ar+tar
# reader is used). We take
#   usr/bin/proot                  -> libproot.so
#   usr/libexec/proot/loader       -> libproot_loader.so      (static, PROOT_LOADER)
#   usr/libexec/proot/loader32     -> libproot_loader32.so    (static, PROOT_LOADER_32)
#   usr/lib/libtalloc.so.2.5.0     -> libtalloc.so
#   usr/lib/libandroid-shmem.so    -> libandroid-shmem.so
# and make ONE same-length binary patch: proot's DT_NEEDED and talloc's DT_SONAME say
# "libtalloc.so.2", but the APK can only carry lib*.so names, so "libtalloc.so.2\0" is rewritten to
# "libtalloc.so\0\0\0" (both strings live in .dynstr; exactly one occurrence per file, asserted).
# Termux's RUNPATH (/data/data/com.termux/files/usr/lib) is left alone: it doesn't exist in our app
# and LD_LIBRARY_PATH=nativeLibraryDir (ProotLauncher.hostEnv) resolves the deps. The compiled-in
# Termux loader/tmp paths are overridden by PROOT_LOADER / PROOT_LOADER_32 / PROOT_TMP_DIR, which
# ProotLauncher always sets.
#
# VERIFIED 2026-11-19 (downloaded to /tmp, hashed, ELF-parsed, deleted):
#   - every PT_LOAD of the 64-bit files has Align 0x4000 (16 KB pages OK); loader32 is 0x1000
#     (only used for 32-bit guests, which we don't ship)
#   - proot: PIE, interp /system/bin/linker64, NEEDED libtalloc.so.2 libandroid-shmem.so libc.so
#   - shmem: NEEDED liblog.so libc.so (system libs)
#   - strings show 5.1.107.102 and PROOT_LOADER/PROOT_LOADER_32/PROOT_L2S_DIR/PROOT_NO_SECCOMP/PROOT_TMP_DIR
# NOT YET RUN on a device or emulator. The next CI emulator run is the proof.
#
# RISK: the Termux pool keeps only the CURRENT version of each package (proot_5.1.107.101 already
# 404s). When Termux bumps proot, these URLs 404 and the script fails loudly (never silently).
# Fix then: bump the versions + hashes below, or set PROOT_SOURCE=linuxonandroid to get the old
# (fork-broken on x86_64) binaries. A long-term fix is mirroring the .debs as a GitHub release asset.
#
# GPL NOTE: proot is executed as a separate program; its corresponding source is termux/proot tag
# v5.1.107.102 plus termux-packages packages/proot/build.sh. Keep this provenance in the README.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI="$ROOT/app/src/main/jniLibs"
SOURCE="${PROOT_SOURCE:-termux}"

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

# ------------------------------------------------------------------------------------------------
# Fallback: the previous LinuxOnAndroid@d1e0facd pins (termux/proot@7266fb3e, no fork->clone fix).
# Kept only so the x86_64 regression can be reproduced on purpose: PROOT_SOURCE=linuxonandroid.
# ------------------------------------------------------------------------------------------------
if [[ "$SOURCE" == "linuxonandroid" ]]; then
  COMMIT="d1e0facd2f5bd3607d6e4bf56bc3c5851c14d116"
  BASE="https://raw.githubusercontent.com/devwithzachary/LinuxOnAndroid/${COMMIT}/app/src/main/jniLibs"
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
  echo "$PINS" | while read -r abi file sum; do
    [[ -z "$abi" ]] && continue
    dest="$JNI/$abi/$file"
    mkdir -p "$(dirname "$dest")"
    curl -fsSL --retry 3 -o "$dest.part" "$BASE/$abi/$file"
    got="$(sha256_of "$dest.part")"
    if [[ "$got" != "$sum" ]]; then
      echo "[fetch-proot] SHA256 MISMATCH for $abi/$file: got $got want $sum" >&2
      rm -f "$dest.part"; exit 1
    fi
    mv "$dest.part" "$dest"
    echo "[fetch-proot] ok (linuxonandroid, NO x86_64 fork fix) $abi/$file"
  done
  exit 0
fi

# ------------------------------------------------------------------------------------------------
# Default: official Termux packages.
# ------------------------------------------------------------------------------------------------
MIRROR="${TERMUX_MIRROR:-https://packages-cf.termux.dev/apt/termux-main}"
PROOT_VER="5.1.107.102"
TALLOC_VER="2.5.0"
SHMEM_VER="0.7"

# deb-id  termux-arch  pool-path  sha256-of-.deb
DEBS=$(cat <<EOF
proot  x86_64  pool/main/p/proot/proot_${PROOT_VER}_x86_64.deb                         2497064226c15971be8131c7facead94a06c225347796326a383c832dca57758
proot  aarch64 pool/main/p/proot/proot_${PROOT_VER}_aarch64.deb                        10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030
talloc x86_64  pool/main/libt/libtalloc/libtalloc_${TALLOC_VER}_x86_64.deb             b8c6d95f20075dc1f9ec6573575b2444e8d526e48e0d8d6d5cf4e071e6e06530
talloc aarch64 pool/main/libt/libtalloc/libtalloc_${TALLOC_VER}_aarch64.deb            556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7
shmem  x86_64  pool/main/liba/libandroid-shmem/libandroid-shmem_${SHMEM_VER}_x86_64.deb  ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797
shmem  aarch64 pool/main/liba/libandroid-shmem/libandroid-shmem_${SHMEM_VER}_aarch64.deb 0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6
EOF
)

# sha256 of the FINAL jniLibs files (after the libtalloc.so.2 -> libtalloc.so patch).
OUT_PINS=$(cat <<'EOF'
x86_64    libproot.so          08926a2164a54a4f7d8d4e64320fd4791e664906851600fd64491463b409a2d2
x86_64    libproot_loader.so   a836b0569c84b1661333d16bacf6b396ff08fea816a5c936a82e08ddeeca8341
x86_64    libproot_loader32.so fd5b394aa0801619e293b76fe6e830a3beb363aed4fa4b9a5a41f1388a0cb9b9
x86_64    libtalloc.so         7354442094c5363639cc87e1126e9298442a2546dd2a0699972f74d263901da2
x86_64    libandroid-shmem.so  092926060298acd3778e6239033d7aef1280dcb59aebe021a3719612e6a3465f
arm64-v8a libproot.so          aad737c5b546ee7471f133be90ef20f765aa98a303522557ceed3dff76605085
arm64-v8a libproot_loader.so   1da54355333cca3fb6ee10e3b82a24df24fb230c99241ce83809bf9e240609b5
arm64-v8a libproot_loader32.so c429996fee730e1a4fe12625b22dab5a5bc2847a4cae274d36fd4a19cd8299a8
arm64-v8a libtalloc.so         160d39bc0d7ed946f22310d1d6f20addda21b02b0972dffe929fe49951543941
arm64-v8a libandroid-shmem.so  84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731
EOF
)

all_cached=1
while read -r abi file sum; do
  [[ -z "$abi" ]] && continue
  f="$JNI/$abi/$file"
  if [[ ! -f "$f" ]] || [[ "$(sha256_of "$f")" != "$sum" ]]; then all_cached=0; break; fi
done <<< "$OUT_PINS"
if [[ "$all_cached" == "1" ]]; then
  echo "[fetch-proot] ok (cached) termux proot $PROOT_VER, all 10 files match"
  exit 0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

while read -r id arch path sum; do
  [[ -z "$id" ]] && continue
  dest="$WORK/${id}_${arch}.deb"
  curl -fsSL --retry 3 -o "$dest" "$MIRROR/$path" || {
    echo "[fetch-proot] DOWNLOAD FAILED: $MIRROR/$path" >&2
    echo "[fetch-proot] Termux keeps only the current version in its pool; bump the versions/hashes in this script." >&2
    exit 1
  }
  got="$(sha256_of "$dest")"
  if [[ "$got" != "$sum" ]]; then
    echo "[fetch-proot] SHA256 MISMATCH for $path: got $got want $sum" >&2
    exit 1
  fi
  echo "[fetch-proot] ok $(basename "$path") ($sum)"
done <<< "$DEBS"

python3 - "$WORK" "$JNI" <<'PY'
import io, os, sys, tarfile
work, jni = sys.argv[1], sys.argv[2]
P = "data/data/com.termux/files/usr/"
WANT = {  # (deb-id, member suffix) -> jniLibs name
    ("proot", "bin/proot"): "libproot.so",
    ("proot", "libexec/proot/loader"): "libproot_loader.so",
    ("proot", "libexec/proot/loader32"): "libproot_loader32.so",
    ("talloc", "lib/libtalloc.so.2.5.0"): "libtalloc.so",
    ("shmem", "lib/libandroid-shmem.so"): "libandroid-shmem.so",
}
ABI = {"x86_64": "x86_64", "aarch64": "arm64-v8a"}

def ar_members(path):
    d = open(path, "rb").read()
    assert d[:8] == b"!<arch>\n", path
    o = 8
    while o < len(d):
        name = d[o:o + 16].decode().strip().rstrip("/")
        size = int(d[o + 48:o + 58])
        o += 60
        yield name, d[o:o + size]
        o += size + (size & 1)

for arch, abi in ABI.items():
    os.makedirs(os.path.join(jni, abi), exist_ok=True)
    for deb_id in ("proot", "talloc", "shmem"):
        deb = os.path.join(work, f"{deb_id}_{arch}.deb")
        data = next(b for n, b in ar_members(deb) if n.startswith("data.tar"))
        tf = tarfile.open(fileobj=io.BytesIO(data))
        for m in tf.getmembers():
            for (did, suffix), out in WANT.items():
                if did == deb_id and m.isfile() and m.name.lstrip("./") == P + suffix:
                    blob = tf.extractfile(m).read()
                    if out in ("libproot.so", "libtalloc.so"):
                        old, new = b"libtalloc.so.2\0", b"libtalloc.so\0\0\0"
                        assert blob.count(old) == 1, f"{abi}/{out}: expected exactly one {old!r}"
                        blob = blob.replace(old, new)
                    dst = os.path.join(jni, abi, out)
                    with open(dst, "wb") as fh:
                        fh.write(blob)
                    os.chmod(dst, 0o755)
PY

fail=0
while read -r abi file sum; do
  [[ -z "$abi" ]] && continue
  f="$JNI/$abi/$file"
  got="$( [[ -f "$f" ]] && sha256_of "$f" || echo MISSING)"
  if [[ "$got" != "$sum" ]]; then
    echo "[fetch-proot] OUTPUT SHA256 MISMATCH $abi/$file: got $got want $sum" >&2; fail=1
  else
    echo "[fetch-proot] ok $abi/$file ($sum)"
  fi
done <<< "$OUT_PINS"
[[ "$fail" == "0" ]] || exit 1
echo "[fetch-proot] termux proot $PROOT_VER (with fork->clone SIGSYS emulation) ready"
