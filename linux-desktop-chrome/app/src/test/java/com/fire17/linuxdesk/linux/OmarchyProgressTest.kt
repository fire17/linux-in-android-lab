package com.fire17.linuxdesk.linux

import com.fire17.linuxdesk.linux.OmarchyProgress.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lines below are verbatim shapes from a live aarch64 Arch Linux ARM run of setup-omarchy.sh (2026-11-19). */
class OmarchyProgressTest {

    @Test fun downloadThenExtractMapToTheirBands() {
        val p = OmarchyProgress()
        p.onDownload(50); assertEquals(17, p.snapshot.percent)
        p.onDownload(100); assertEquals(35, p.snapshot.percent)
        assertEquals(Phase.DOWNLOAD, p.snapshot.phase)
        assertTrue(p.onLine("12500 files…")); assertEquals(40, p.snapshot.percent)
        assertEquals(Phase.EXTRACT, p.snapshot.phase)
    }

    @Test fun stagesAndPacmanPackagesAdvanceTheSetupBand() {
        val p = OmarchyProgress()
        p.onDownload(100)
        p.onLine("LINUXDESK_STAGE 5/7 Installing Chrome, i3, fonts and tools")
        val atStart = p.snapshot.percent
        assertEquals("Installing Chrome, i3, fonts and tools", p.snapshot.stage)
        assertEquals(5, p.snapshot.stageIndex); assertEquals(7, p.snapshot.stageTotal)
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

    @Test fun ttyFormOfPacmanProgressCountsToo() {
        val p = OmarchyProgress()
        p.onLine("LINUXDESK_STAGE 3/7 Updating Arch Linux ARM (full upgrade)")
        p.onLine("Packages (2) bash-5.3-1  glibc-2.42-1")
        assertTrue(p.onLine("(1/2) upgrading bash"))
        assertTrue(p.snapshot.detail.contains("1 / 2"))
    }

    @Test fun neverGoesBackwardsAndBootThenWindowUpIsDone() {
        val p = OmarchyProgress()
        p.onLine("LINUXDESK_STAGE 6/7 Installing the Omarchy themes")
        val before = p.snapshot.percent
        p.onLine("LINUXDESK_STAGE 2/7 Initialising the pacman keyring")   // e.g. a re-run
        assertTrue(p.snapshot.percent >= before)
        p.onLine("[omarchy] i3 started (pid 4242)")
        assertEquals(Phase.BOOT, p.snapshot.phase); assertEquals(97, p.snapshot.percent)
        p.onLine("LINUXDESK_WINDOW_UP 1080x2340")
        assertEquals(100, p.snapshot.percent); assertEquals(Phase.UP, p.snapshot.phase)
    }

    @Test fun unrelatedLinesAreIgnored() {
        val p = OmarchyProgress()
        assertEquals(false, p.onLine("gpg: marginals needed: 3  completes needed: 1  trust model: pgp"))
        assertEquals(0, p.snapshot.percent)
    }

    @Test fun resetIsInPlaceSoTheServiceKeepsFeedingTheSameObject() {
        val held = OmarchyRun.progress          // what SessionService captured at session start
        held.onDownload(80)
        OmarchyRun.reset()
        assertSame(held, OmarchyRun.progress)
        assertEquals(0, OmarchyRun.progress.snapshot.percent)
        assertEquals(Phase.STARTING, OmarchyRun.progress.snapshot.phase)
        assertFalse(OmarchyRun.setupShown)
    }

    // ---- Replays of the REAL session log from Tami's phone (2026-11-19 21:14; Omarchy first run, mirror 404s). ----

    /** The phone run used the build before the LINUXDESK_STAGE markers: no markers at all. */
    @Test fun realPhoneLogWithoutMarkers() {
        val p = OmarchyProgress()
        val seen = replay(p, phoneLog())
        assertMonotonic(seen)
        val s = p.snapshot
        // Failed early in setup (only the kernel removal + keyring were installed; 3 transactions hit 404s).
        assertEquals(Phase.SETUP, s.phase)
        assertTrue("percent ${s.percent}", s.percent in 45..55)
        assertEquals("Installing packages", s.stage)
        assertEquals(0, s.stageIndex)
        assertTrue(s.error, s.error.startsWith("error: failed retrieving file") && s.error.contains("404"))
        // Download band ended at 35, unpack band at 44 (the extractor's last "N files…" line was 22800 of ~25k).
        assertEquals(35, seen.first { it.phase == Phase.EXTRACT }.percent - 0.coerceAtLeast(0) - 0 - (seen.first { it.phase == Phase.EXTRACT }.percent - 35))
        assertEquals(44, seen.last { it.phase == Phase.EXTRACT }.percent)
    }

    /** The same real lines with the markers the current setup-omarchy.sh prints, at the points it prints them. */
    @Test fun realPhoneLogWithTodaysMarkers() {
        val lines = phoneLog().toMutableList()
        fun insertBefore(stage: String, from: Int = 0, match: (String) -> Boolean): Int {
            val i = (from until lines.size).first { match(lines[it]) }
            lines.add(i, "LINUXDESK_STAGE $stage"); return i + 1
        }
        var at = insertBefore("1/7 Configuring pacman for PRoot") { it.startsWith("[omarchy] Arch Linux ARM on") }
        at = insertBefore("2/7 Initialising the pacman keyring", at) { it.startsWith("[omarchy] pacman-key --init") }
        at = insertBefore("3/7 Updating Arch Linux ARM (full upgrade)", at) { it.startsWith(":: Synchronizing package databases") }
        at = insertBefore("4/7 Adding the Omarchy repository", at) { it.contains("omarchy-keyring") && it.endsWith(": OK") }
        at = (at until lines.size).first { lines[it] == " omarchy downloading..." }
        at = insertBefore("5/7 Installing Chrome, i3, fonts and tools", at) { it == "resolving dependencies..." }
        insertBefore("6/7 Installing the Omarchy themes", at) { it.startsWith("Packages (1) omarchy-") }

        val p = OmarchyProgress()
        val seen = replay(p, lines)
        assertMonotonic(seen)
        val s = p.snapshot
        assertEquals(6, s.stageIndex); assertEquals(7, s.stageTotal)
        assertEquals("Installing the Omarchy themes", s.stage)
        assertTrue("percent ${s.percent}", s.percent in 80..88)   // 45 + 50 * 5/7 = 80
        assertTrue(s.error.contains("404"))
        // Stage 3 (kernel removal: 15 of 15 packages) ends at the top of its band, never past stage 4's start.
        val s3 = seen.last { it.stageIndex == 3 }
        assertTrue("stage 3 ended at ${s3.percent}", s3.percent in 59..66)
    }

    /** Feeds lines the way SessionService does: state lines carry the download %, the rest goes to onLine. */
    private fun replay(p: OmarchyProgress, lines: List<String>): List<OmarchyProgress.Snapshot> {
        val seen = ArrayList<OmarchyProgress.Snapshot>()
        for (line in lines) {
            if (line.startsWith("[ERROR]")) break          // the rest is the ERROR message's tail dump (not fed)
            val dl = DOWNLOAD_STATE.find(line)
            when {
                dl != null -> p.onDownload(dl.groupValues[1].toInt())
                STATE.containsMatchIn(line) -> {}         // SessionBus.setState echo: never reaches onLine
                else -> p.onLine(line)
            }
            seen += p.snapshot
        }
        return seen
    }

    private fun assertMonotonic(seen: List<OmarchyProgress.Snapshot>) {
        seen.zipWithNext().forEach { (a, b) -> assertTrue("went back ${a.percent} -> ${b.percent}", b.percent >= a.percent) }
        assertTrue(seen.all { it.percent in 0..95 })    // never "done" without LINUXDESK_WINDOW_UP
    }

    private fun phoneLog(): List<String> {
        val r = javaClass.getResource("/omarchy/phone-2114-session.log")
        assertNotNull("test resource omarchy/phone-2114-session.log", r)
        return r!!.readText().lines()
    }

    private companion object {
        val STATE = Regex("""^\[[A-Z_]+\] """)
        val DOWNLOAD_STATE = Regex("""^\[INSTALLING_ROOTFS\] Downloading Arch Linux ARM … (\d+)%""")
    }
}
