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

    @Test fun archDownloadPinsAreWellFormed() {
        assertTrue(ArchRootfsDownloader.KNOWN_SHA256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(ArchRootfsDownloader.KNOWN_MD5.matches(Regex("[0-9a-f]{32}")))
        assertTrue(ArchRootfsDownloader.MIRRORS.all { it.startsWith("https://") && it.endsWith("/os/") })
        // Never skip anything a guest program needs: only kernel, firmware and modules.
        assertEquals(listOf("boot/", "usr/lib/firmware/", "usr/lib/modules/"), ArchRootfsDownloader.SKIP_PREFIXES)
    }
}
