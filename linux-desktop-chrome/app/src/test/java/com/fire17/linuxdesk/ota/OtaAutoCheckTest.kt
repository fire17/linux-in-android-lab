package com.fire17.linuxdesk.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OtaAutoCheckTest {
    private fun store() = BundleStore(Files.createTempDirectory("ota").toFile(), 281, "x", listOf("AAAA"))

    @Test
    fun offDoesNothing() {
        val st = store()
        assertEquals("off", OtaAutoCheck.maybeRun(st, enabled = false, baseUrl = "https://example.invalid/", async = false))
        assertFalse(File(st.root, "last_auto_check").exists())
        assertTrue(st.ledgerRows().isEmpty())
    }

    @Test
    fun onChecksAtMostOncePer6h() {
        val st = store()
        val t0 = 1_000_000_000_000L
        // Non-https base: the check runs, fails closed (NOT_HTTPS), and only writes an auto_check ledger row.
        assertNull(OtaAutoCheck.maybeRun(st, true, "http://example.invalid/", now = t0, async = false))
        assertEquals("auto_check", st.ledgerRows().first().getString("event"))
        assertEquals("too_soon", OtaAutoCheck.maybeRun(st, true, "http://example.invalid/", now = t0 + 60_000, async = false))
        assertNull(OtaAutoCheck.maybeRun(st, true, "http://example.invalid/", now = t0 + OtaAutoCheck.INTERVAL_MS, async = false))
        assertEquals(0L, st.activeVersion())                 // nothing ever applied
    }

    @Test
    fun onAppliedNotCalledWhenNothingApplied() {
        val st = store()
        var called = 0L
        OtaAutoCheck.maybeRun(st, true, "http://example.invalid/", async = false, onApplied = { called = it })
        assertEquals(0L, called)
        assertEquals("Update 7 ready, applies at next start", OtaAutoCheck.readyLine(7))
    }
}
