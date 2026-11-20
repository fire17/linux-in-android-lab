package com.fire17.linuxdesk.linux

import android.content.Context

/**
 * The desktop session inside the OMARCHY distro (same rootfs-arch, same Chrome; only the session launcher differs,
 * see notes/sway-desktop-plan.md). ALPINE has no window manager and ignores this.
 *
 *   I3    start-omarchy.sh  i3 on lorie X11. The DEFAULT, unchanged from every earlier build.
 *   SWAY  start-sway.sh     sway (wlroots x11 backend, pixman) + waybar. Experimental, opt-in from the launcher.
 *                           The display then turns one finger into a mouse (x11/DesktopGestures.pointerMode): sway
 *                           does not act on lorie's XI2 touches.
 *   HYPRLAND start-hyprland.sh  the REAL Omarchy desktop: Weston (windowed on lorie) -> the patched Hyprland from
 *                           /opt/omarchy-android + the Omarchy shell -> Chrome (U5, notes/gap/GAP.md; recipe
 *                           notes/hyprland/bar-fix/). Experimental; offered only when [HYPR_STACK] exists in the rootfs.
 *                           Same one-finger mouse as SWAY. Falls back to start-sway.sh on any failure.
 *
 * The choice is written to the guest's /etc/linuxdesk/desktop (i3|sway|hyprland) before setup runs, so setup-omarchy.sh
 * installs the sway packages only for sway users.
 */
enum class Desktop(val id: String) {
    I3("i3"),
    SWAY("sway"),
    HYPRLAND("hyprland");

    companion object {
        private const val PREFS = "linuxdesk_settings"
        private const val KEY = "desktop"
        /** Written by setup-omarchy.sh after the sway packages are installed. */
        const val SWAY_MARKER = "etc/linuxdesk/sway-v1"
        /** Guest path (relative to the rootfs) setup-omarchy.sh reads. */
        const val GUEST_FILE = "etc/linuxdesk/desktop"
        /** The patched Hyprland stack (rootfs-relative) that HYPRLAND needs; without it the choice is disabled. */
        const val HYPR_STACK = "opt/omarchy-android/hyprland/bin/Hyprland"

        /** True if [rootfsDir] (the OMARCHY rootfs) has the patched Hyprland stack (a guest-absolute symlink counts). */
        fun hyprlandAvailable(rootfsDir: java.io.File): Boolean = java.io.File(rootfsDir, HYPR_STACK).let {
            it.isFile || java.nio.file.Files.isSymbolicLink(it.toPath())
        }

        /** Anything unknown (or nothing saved) is I3. */
        fun parse(v: String?): Desktop = entries.firstOrNull { it.id == v } ?: I3

        fun current(ctx: Context): Desktop =
            parse(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

        fun set(ctx: Context, d: Desktop) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, d.id).apply()
        }

        /** The session launcher: [Distro.startScript], except OMARCHY with SWAY / HYPRLAND (same argv contract). */
        fun startScript(distro: Distro, desktop: Desktop): String = when {
            distro != Distro.OMARCHY -> distro.startScript
            desktop == SWAY -> "/opt/linuxdesk/start-sway.sh"
            desktop == HYPRLAND -> "/opt/linuxdesk/start-hyprland.sh"
            else -> distro.startScript
        }
    }
}
