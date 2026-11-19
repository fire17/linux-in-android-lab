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
        Desktop.entries.forEach { assertEquals(it, Desktop.parse(it.id)) }
    }

    @Test fun startScriptOnlyChangesForOmarchySway() {
        assertEquals("/opt/linuxdesk/start-omarchy.sh", Desktop.startScript(Distro.OMARCHY, Desktop.I3))
        assertEquals("/opt/linuxdesk/start-sway.sh", Desktop.startScript(Distro.OMARCHY, Desktop.SWAY))
        assertEquals("/opt/linuxdesk/start-browser.sh", Desktop.startScript(Distro.ALPINE, Desktop.SWAY))
    }
}
