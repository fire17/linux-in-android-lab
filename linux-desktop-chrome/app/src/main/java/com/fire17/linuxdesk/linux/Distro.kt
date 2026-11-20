package com.fire17.linuxdesk.linux

import android.content.Context
import android.os.Build
import java.io.File

/**
 * The guest distro a session runs. Each distro has its OWN rootfs directory, so switching never touches the
 * other one:
 *
 *   ALPINE   filesDir/rootfs        (unchanged from every earlier build: the bundled Alpine minirootfs, apk,
 *                                    Chromium, no window manager). The DEFAULT, and the only choice on x86_64.
 *   OMARCHY  filesDir/rootfs-arch   Arch Linux ARM aarch64, downloaded on first use (not in the APK), plus
 *                                    Omarchy's pacman repo: Google Chrome, Omarchy themes and fonts, i3 on lorie X11.
 *   OMARCHY_CLEAN  filesDir/rootfs-omarchy-clean  (experimental) a fresh Arch Linux ARM with the real `omarchy`
 *                                    package; Hyprland nested in Weston when the patched stack is present, else sway.
 *   OMARCHY_MOBILE filesDir/rootfs-omamobile  Omarchy Mobile's phone shell (portrait Hyprland in Weston), from a
 *                                    prebuilt rootfs release (8 parallel ranges) or, if missing, pacman on ALARM.
 *
 * The X server (lorie, ":x11" process) reads the selected rootfs dir from the bind intent, and every guest
 * process runs under proot -r <rootfsDir>. Paths that other lanes still hardcode as filesDir/rootfs (the genie
 * hash file, sessionInfo) keep pointing at Alpine; see notes/progress/omarchy.log OPEN.
 */
enum class Distro(
    val id: String,
    /** Directory under filesDir. ALPINE keeps today's "rootfs" so existing installs are reused as is. */
    val dirName: String,
    /** Marker that the distro's first-run package script writes; the app checks it. */
    val packagesMarker: String,
    /** Package script under /opt/linuxdesk, run once under proot. */
    val setupScript: String,
    /** Browser launcher under /opt/linuxdesk; same argv contract as start-browser.sh (Chromium flags + URL). */
    val startScript: String,
    /** Genie (deskd) guest-package script under /opt/linuxdesk. */
    val genieScript: String,
) {
    ALPINE("alpine", "rootfs", "etc/linuxdesk/packages-v1",
        "/opt/linuxdesk/setup-packages.sh", "/opt/linuxdesk/start-browser.sh", "/opt/linuxdesk/setup-genie.sh"),
    OMARCHY("omarchy", "rootfs-arch", "etc/linuxdesk/omarchy-v1",
        "/opt/linuxdesk/setup-omarchy.sh", "/opt/linuxdesk/start-omarchy.sh", "/opt/linuxdesk/setup-genie-arch.sh"),
    /**
     * Experimental third option (Tami 2026-11-20 02:17/02:19: "full Omarchy from the start", a "format C"): a FRESH
     * Arch Linux ARM rootfs of its own that installs the REAL `omarchy` package and its dependency tree, plus PRoot
     * shims for systemd/uwsm/sudo (notes/fresh/PLAN.md). Never touches rootfs or rootfs-arch.
     */
    OMARCHY_CLEAN("omarchy-clean", "rootfs-omarchy-clean", "etc/linuxdesk/omarchy-clean-v1",
        "/opt/linuxdesk/setup-omarchy-clean.sh", "/opt/linuxdesk/start-omarchy-clean.sh", "/opt/linuxdesk/setup-genie-arch.sh"),
    /**
     * Omarchy Mobile (Tami 2026-11-20 06:37: "a fully separate OS option"): the phone-shell profile of
     * derekross/omarchy-mobile (touch launcher, home screens, squeekboard, phone.lua rules) over our patched Hyprland,
     * portrait, in its own rootfs (notes/omamobile/REPORT.md). Fast path: ONE prebuilt rootfs from a GitHub Release
     * ([OmamobileRootfs]); fallback: setup-omamobile.sh's pacman path on the plain ALARM tarball.
     */
    OMARCHY_MOBILE("omarchy-mobile", "rootfs-omamobile", "etc/linuxdesk/omamobile-v1",
        "/opt/linuxdesk/setup-omamobile.sh", "/opt/linuxdesk/start-omamobile.sh", "/opt/linuxdesk/setup-genie-arch.sh");

    fun rootfsDir(ctx: Context): File = File(ctx.filesDir, dirName)

    /** Every Omarchy flavour is Arch Linux ARM: same tarball, same pacman, same full-screen setup screen. */
    val isArch: Boolean get() = this != ALPINE

    companion object {
        private const val PREFS = "linuxdesk_settings"
        private const val KEY = "distro"

        /** Arch Linux ARM is aarch64 only, so Omarchy is offered on arm64-v8a devices only (never the x86_64 CI emulator). */
        fun omarchySupported(): Boolean = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" } == "arm64-v8a"

        /** The selected distro. Anything unknown, or an Arch distro on a non-arm64 device, falls back to ALPINE. */
        fun current(ctx: Context): Distro {
            val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            val d = entries.firstOrNull { it.id == v } ?: ALPINE
            return if (d.isArch && !omarchySupported()) ALPINE else d
        }

        fun set(ctx: Context, d: Distro) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, d.id).apply()
        }

        fun currentRootfs(ctx: Context): File = current(ctx).rootfsDir(ctx)
    }
}
