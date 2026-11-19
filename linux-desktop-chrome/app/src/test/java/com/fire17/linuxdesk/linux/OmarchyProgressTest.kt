package com.fire17.linuxdesk.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lines below are verbatim shapes from a live aarch64 Arch Linux ARM run of setup-omarchy.sh (2026-11-19). */
class OmarchyProgressTest {

    @Test fun downloadThenExtractMapToTheirBands() {
        val p = OmarchyProgress()
        p.onDownload(50); assertEquals(17, p.snapshot.percent)
        p.onDownload(100); assertEquals(35, p.snapshot.percent)
        assertTrue(p.onLine("12500 files…")); assertEquals(40, p.snapshot.percent)
    }

    @Test fun stagesAndPacmanPackagesAdvanceTheSetupBand() {
        val p = OmarchyProgress()
        p.onDownload(100)
        p.onLine("LINUXDESK_STAGE 5/7 Installing Chrome, i3, fonts and tools")
        val atStart = p.snapshot.percent
        assertEquals("Installing Chrome, i3, fonts and tools", p.snapshot.stage)
        p.onLine("Packages (4) alacritty-0.17.0-1  i3-wm-4.25.1-1  rofi-1.7.5-3  python-3.14.10-1")
        p.onLine("installing alacritty...")
        p.onLine("installing i3-wm...")
        assertTrue(p.snapshot.percent > atStart)
        assertTrue(p.snapshot.detail.contains("i3-wm"))
        assertTrue(p.snapshot.detail.contains("2 / 4"))
        p.onLine(" google-chrome-158.0.8127.36-1-aarch64 downloading...")
        assertTrue(p.snapshot.detail.startsWith("downloading google-chrome"))
        p.onLine("LINUXDESK_STAGE 7/7 Applying the Omarchy look")
        assertTrue(p.snapshot.percent in 45..95)
    }

    @Test fun neverGoesBackwardsAndWindowUpIsDone() {
        val p = OmarchyProgress()
        p.onLine("LINUXDESK_STAGE 6/7 Installing the Omarchy themes")
        val before = p.snapshot.percent
        p.onLine("LINUXDESK_STAGE 2/7 Initialising the pacman keyring")   // e.g. a re-run
        assertTrue(p.snapshot.percent >= before)
        p.onLine("LINUXDESK_WINDOW_UP 1080x2340")
        assertEquals(100, p.snapshot.percent)
    }

    @Test fun unrelatedLinesAreIgnored() {
        val p = OmarchyProgress()
        assertEquals(false, p.onLine("gpg: marginals needed: 3  completes needed: 1  trust model: pgp"))
        assertEquals(0, p.snapshot.percent)
    }
}
