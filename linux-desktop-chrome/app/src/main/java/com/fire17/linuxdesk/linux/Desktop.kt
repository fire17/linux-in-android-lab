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
 *
 * The choice is written to the guest's /etc/linuxdesk/desktop (i3|sway) before setup runs, so setup-omarchy.sh
 * installs the sway packages only for sway users.
 */
enum class Desktop(val id: String) {
    I3("i3"),
    SWAY("sway");

    companion object {
        private const val PREFS = "linuxdesk_settings"
        private const val KEY = "desktop"
        /** Written by setup-omarchy.sh after the sway packages are installed. */
        const val SWAY_MARKER = "etc/linuxdesk/sway-v1"
        /** Guest path (relative to the rootfs) setup-omarchy.sh reads. */
        const val GUEST_FILE = "etc/linuxdesk/desktop"

        /** Anything unknown (or nothing saved) is I3. */
        fun parse(v: String?): Desktop = entries.firstOrNull { it.id == v } ?: I3

        fun current(ctx: Context): Desktop =
            parse(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

        fun set(ctx: Context, d: Desktop) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, d.id).apply()
        }

        /** The session launcher: [Distro.startScript], except OMARCHY with SWAY runs start-sway.sh (same argv contract). */
        fun startScript(distro: Distro, desktop: Desktop): String =
            if (distro == Distro.OMARCHY && desktop == SWAY) "/opt/linuxdesk/start-sway.sh" else distro.startScript
    }
}
