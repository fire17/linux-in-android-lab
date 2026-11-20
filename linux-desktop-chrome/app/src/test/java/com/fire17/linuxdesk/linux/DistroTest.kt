package com.fire17.linuxdesk.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Omarchy lane: the Alpine path must stay exactly what every earlier build used; the two distros never share a dir. */
class DistroTest {

    @Test fun alpineKeepsTodaysPathsAndScripts() {
        val a = Distro.ALPINE
        assertEquals("rootfs", a.dirName)                              // existing installs are reused as is
        assertEquals("etc/linuxdesk/packages-v1", a.packagesMarker)
        assertEquals("/opt/linuxdesk/setup-packages.sh", a.setupScript)
        assertEquals("/opt/linuxdesk/start-browser.sh", a.startScript)
        assertEquals("/opt/linuxdesk/setup-genie.sh", a.genieScript)
    }

    @Test fun omarchyHasItsOwnRootfsAndMarker() {
        val o = Distro.OMARCHY
        assertEquals("rootfs-arch", o.dirName)
        assertEquals("etc/linuxdesk/omarchy-v1", o.packagesMarker)
        assertNotEquals(Distro.ALPINE.dirName, o.dirName)
        assertTrue(o.startScript.endsWith("start-omarchy.sh"))
        assertTrue(o.genieScript.endsWith("setup-genie-arch.sh"))
    }

    /** Omarchy (clean): a third, separate rootfs; it must never share a dir or a marker with the other two. */
    @Test fun omarchyCleanHasItsOwnRootfsAndScripts() {
        val c = Distro.OMARCHY_CLEAN
        assertEquals("omarchy-clean", c.id)
        assertEquals("rootfs-omarchy-clean", c.dirName)
        assertEquals("etc/linuxdesk/omarchy-clean-v1", c.packagesMarker)
        assertEquals("/opt/linuxdesk/setup-omarchy-clean.sh", c.setupScript)
        assertEquals("/opt/linuxdesk/start-omarchy-clean.sh", c.startScript)
        assertTrue(c.genieScript.endsWith("setup-genie-arch.sh"))
        assertEquals(3, Distro.entries.map { it.dirName }.toSet().size)
        assertEquals(3, Distro.entries.map { it.packagesMarker }.toSet().size)
        assertTrue(c.isArch && Distro.OMARCHY.isArch && !Distro.ALPINE.isArch)
    }

    @Test fun archDownloadPinsAreWellFormed() {
        assertTrue(ArchRootfsDownloader.KNOWN_SHA256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(ArchRootfsDownloader.KNOWN_MD5.matches(Regex("[0-9a-f]{32}")))
        assertTrue(ArchRootfsDownloader.MIRRORS.all { it.startsWith("https://") && it.endsWith("/os/") })
        // Never skip anything a guest program needs: only kernel, firmware and modules.
        assertEquals(listOf("boot/", "usr/lib/firmware/", "usr/lib/modules/"), ArchRootfsDownloader.SKIP_PREFIXES)
    }
}
