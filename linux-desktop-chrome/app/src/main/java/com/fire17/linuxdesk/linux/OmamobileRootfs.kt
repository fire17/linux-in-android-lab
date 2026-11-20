package com.fire17.linuxdesk.linux

/**
 * Omarchy Mobile's fast install (Tami 2026-11-20 06:50: "everything works in under a minute end to end"): ONE prebuilt
 * rootfs, built by .github/workflows/omamobile-rootfs.yml (ALARM + setup-omamobile.sh, zstd -19 --long) and published
 * as a GitHub Release asset. [PrebuiltRootfs.tryInstall] (the shared fast path: HEAD probe, 8 parallel HTTP Range
 * chunks, sha256 from [SHA256_URL], zstd → tar straight into the staging dir) installs it. When the asset is missing
 * (HEAD != 200) RootfsInstaller falls back to the plain ALARM tarball and setup-omamobile.sh's pacman path.
 *
 * The prebuilt tree already holds /etc/linuxdesk/omamobile-v1 (Distro.OMARCHY_MOBILE.packagesMarker), so the first
 * Start goes straight to the desktop; setup-omamobile.sh is never run on the phone on the fast path.
 */
object OmamobileRootfs {
    /** Marker version for RootfsInstaller (both paths; bump to force a re-install of rootfs-omamobile). */
    const val VERSION = "omamobile-rootfs-v1"
    /** The Release tag the workflow publishes to (fire17/linux-in-android-lab). */
    const val RELEASE_TAG = "omamobile-rootfs-v1"
    const val ASSET = "omamobile-rootfs-aarch64.tar.zst"
    const val BASE_URL = "https://github.com/fire17/linux-in-android-lab/releases/download/$RELEASE_TAG/"
    const val ASSET_URL = BASE_URL + ASSET
    /** `sha256sum` output ("<hex>  <name>"); PrebuiltRootfs reads the first word. */
    const val SHA256_URL = "$ASSET_URL.sha256"
    /** Projection (notes/omamobile/REPORT.md §4): ~1.0-1.2 GB compressed, ~3.0-3.5 GB unpacked. For the free-space check. */
    const val APPROX_SIZE = 1_200L * 1024 * 1024
}
