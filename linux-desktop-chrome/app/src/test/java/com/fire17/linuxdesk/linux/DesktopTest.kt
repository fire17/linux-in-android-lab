package com.fire17.linuxdesk.linux

import org.junit.Assert.assertEquals
import org.junit.Test

/** Sway lane: i3 stays the default; only Omarchy + SWAY swaps the start script. */
class DesktopTest {

    @Test fun defaultAndUnknownAreI3() {
        assertEquals(Desktop.I3, Desktop.parse(null))
        assertEquals(Desktop.I3, Desktop.parse(""))
        assertEquals(Desktop.I3, Desktop.parse("SWAY"))   // ids are lowercase, as written to /etc/linuxdesk/desktop
        assertEquals(Desktop.I3, Desktop.parse("gnome"))
    }

    @Test fun parsesIds() {
        assertEquals(Desktop.I3, Desktop.parse("i3"))
        assertEquals(Desktop.SWAY, Desktop.parse("sway"))
        assertEquals(Desktop.HYPRLAND, Desktop.parse("hyprland"))
        assertEquals(Desktop.I3, Desktop.parse("Hyprland"))
        Desktop.entries.forEach { assertEquals(it, Desktop.parse(it.id)) }
    }

    @Test fun hyprlandNeedsThePatchedStack() {
        val root = java.nio.file.Files.createTempDirectory("rootfs").toFile()
        try {
            assertEquals(false, Desktop.hyprlandAvailable(root))
            java.io.File(root, Desktop.HYPR_STACK).apply { parentFile?.mkdirs() }.writeText("")
            assertEquals(true, Desktop.hyprlandAvailable(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun startScriptOnlyChangesForOmarchySway() {
        assertEquals("/opt/linuxdesk/start-omarchy.sh", Desktop.startScript(Distro.OMARCHY, Desktop.I3))
        assertEquals("/opt/linuxdesk/start-sway.sh", Desktop.startScript(Distro.OMARCHY, Desktop.SWAY))
        assertEquals("/opt/linuxdesk/start-hyprland.sh", Desktop.startScript(Distro.OMARCHY, Desktop.HYPRLAND))
        assertEquals("/opt/linuxdesk/start-browser.sh", Desktop.startScript(Distro.ALPINE, Desktop.HYPRLAND))
        assertEquals("/opt/linuxdesk/start-omarchy-clean.sh", Desktop.startScript(Distro.OMARCHY_CLEAN, Desktop.HYPRLAND))
        assertEquals("/opt/linuxdesk/start-browser.sh", Desktop.startScript(Distro.ALPINE, Desktop.SWAY))
        // Omarchy (clean) has its own launcher, whatever the i3/sway checkbox says (it falls back to sway itself).
        assertEquals("/opt/linuxdesk/start-omarchy-clean.sh", Desktop.startScript(Distro.OMARCHY_CLEAN, Desktop.SWAY))
        assertEquals("/opt/linuxdesk/start-omarchy-clean.sh", Desktop.startScript(Distro.OMARCHY_CLEAN, Desktop.I3))
        // Omarchy Mobile likewise always runs its own launcher.
        for (d in Desktop.entries) assertEquals("/opt/linuxdesk/start-omamobile.sh", Desktop.startScript(Distro.OMARCHY_MOBILE, d))
    }
}
