package com.fire17.linuxdesk.storage

import com.fire17.linuxdesk.storage.DeleteGate.Ev
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Random

/**
 * The delete gate (DeleteGate.kt, which Proposals.kt calls for every decision). Pure JVM: no Android, no org.json.
 * What Proposals adds on top (persistence, MediaStore, notifications) needs a device and is NOT covered here.
 */
class ProposalsTest {

    private val S = DeleteGate

    // ------------------------------------------------------------ the state machine

    @Test fun remoteCanNeverReachExecuting() {
        // From every state, the only events a remote genie can cause are EXECUTE, CANCEL, ADJUST, REPLACE
        // (fs.execute / fs.cancel / fs.adjust / a new fs.propose_delete). None of them leads to `executing`.
        val states = listOf(S.PENDING, S.SELECTED, S.AWAITING, S.EXECUTING, S.DONE, S.CANCELLED, S.DISMISSED)
        for (st in states) for (ev in listOf(Ev.EXECUTE, Ev.CANCEL, Ev.ADJUST, Ev.REPLACE)) for (l in listOf(false, true)) {
            val n = S.next(st, ev, l)
            assertNotEquals("$st --$ev--> executing", S.EXECUTING, n)
            assertNotEquals("$st --$ev--> done", S.DONE, n)
        }
    }

    @Test fun executeOnlyMovesSelectedToAwaiting() {
        assertEquals(S.AWAITING, S.next(S.SELECTED, Ev.EXECUTE))
        for (st in listOf(S.PENDING, S.AWAITING, S.EXECUTING, S.DONE, S.CANCELLED, S.DISMISSED))
            assertNull("execute from $st", S.next(st, Ev.EXECUTE))
    }

    @Test fun onlyApproveFromAwaitingStartsExecution() {
        assertEquals(S.EXECUTING, S.next(S.AWAITING, Ev.APPROVE))
        for (st in listOf(S.PENDING, S.SELECTED, S.EXECUTING, S.DONE, S.CANCELLED, S.DISMISSED))
            assertNull("approve from $st", S.next(st, Ev.APPROVE))
    }

    @Test fun sheetCancelAndAdjust() {
        assertEquals(S.SELECTED, S.next(S.AWAITING, Ev.SHEET_CANCEL))
        assertEquals(S.PENDING, S.next(S.AWAITING, Ev.SHEET_ADJUST))
        assertNull(S.next(S.SELECTED, Ev.SHEET_CANCEL))
        assertNull(S.next(S.EXECUTING, Ev.SHEET_ADJUST))
        assertEquals(S.SELECTED, S.next(S.EXECUTING, Ev.DIALOG_CANCEL))
        assertEquals(S.DONE, S.next(S.EXECUTING, Ev.COMPLETE))
        assertNull(S.next(S.AWAITING, Ev.COMPLETE))
    }

    @Test fun cancelRules() {
        for (st in listOf(S.PENDING, S.SELECTED, S.AWAITING)) assertEquals(S.CANCELLED, S.next(st, Ev.CANCEL))
        assertEquals(S.CANCELLED, S.next(S.EXECUTING, Ev.CANCEL, launched = false))
        assertNull("system dialog already on screen", S.next(S.EXECUTING, Ev.CANCEL, launched = true))
        for (st in listOf(S.DONE, S.CANCELLED, S.DISMISSED)) assertNull(S.next(st, Ev.CANCEL))
    }

    @Test fun replaceAndAdjustAndInterrupt() {
        assertEquals(S.DISMISSED, S.next(S.PENDING, Ev.REPLACE))
        assertEquals(S.DISMISSED, S.next(S.SELECTED, Ev.REPLACE))
        assertNull("a list under review is not silently replaced", S.next(S.AWAITING, Ev.REPLACE))
        assertNull(S.next(S.EXECUTING, Ev.REPLACE))
        assertEquals(S.PENDING, S.next(S.SELECTED, Ev.ADJUST))
        assertNull(S.next(S.AWAITING, Ev.ADJUST))
        assertEquals(S.SELECTED, S.next(S.EXECUTING, Ev.INTERRUPT))
        assertEquals(S.SELECTED, S.next(S.PENDING, Ev.SELECT))
        assertNull(S.next(S.SELECTED, Ev.SELECT))
    }

    @Test fun liveStatesIncludeAwaiting() {
        assertTrue(S.AWAITING in S.LIVE)
        assertEquals(setOf("pending", "selected", "awaiting_confirm", "executing"), S.LIVE)
        assertEquals("awaiting_confirm", S.AWAITING)
    }

    // ------------------------------------------------------------ hash + nonce

    @Test fun hashIsStorageGenieCompatible() {
        // sha256("a\t1\nb\t2\n"), path-sorted, byte-identical to SG Proposals.hashOf.
        val h = S.hashOf(listOf("b" to 2L, "a" to 1L))
        assertEquals(S.hashOf(listOf("a" to 1L, "b" to 2L)), h)
        assertEquals(64, h.length)
        val md = java.security.MessageDigest.getInstance("SHA-256").digest("a\t1\nb\t2\n".toByteArray())
        assertEquals(md.joinToString("") { "%02x".format(it) }, h)
        assertNotEquals(h, S.hashOf(listOf("a" to 1L, "b" to 3L)))
        assertNotEquals(h, S.hashOf(listOf("a" to 1L)))
    }

    @Test fun nonceShapeAndRandomness() {
        val rng = SecureRandom()
        val seen = HashSet<String>()
        repeat(200) {
            val n = S.nonce(rng)
            assertEquals(8, n.length)
            assertTrue(n, n.all { it in S.NONCE_ABC })
            seen.add(n)
        }
        assertTrue("nonces repeat", seen.size > 195)
        assertFalse(S.NONCE_ABC.contains('0')); assertFalse(S.NONCE_ABC.contains('O'))
        assertFalse(S.NONCE_ABC.contains('1')); assertFalse(S.NONCE_ABC.contains('I'))
    }

    // ------------------------------------------------------------ fs.execute checks

    private val sel = listOf("Download/a.zip" to 100L, "Movies/b.mp4" to 2000L)
    private val good = S.hashOf(sel)

    private fun exec(state: String = S.SELECTED, mode: String = "trash", nonce: String = "ABCDEFGH", hash: String = good,
                     stored: String = "ABCDEFGH", storedHash: String = good, list: List<Pair<String, Long>> = sel) =
        S.checkExecute(state, mode, nonce, hash, stored, storedHash, list)

    @Test fun executeAcceptsExactMatch() {
        assertNull(exec()); assertNull(exec(mode = "delete"))
    }

    @Test fun executeRefusesWrongState() {
        for (st in listOf(S.PENDING, S.AWAITING, S.EXECUTING, S.DONE, S.CANCELLED, S.DISMISSED))
            assertEquals(st, "not_selected", exec(state = st))
    }

    @Test fun executeRefusesBadMode() {
        assertEquals("bad_mode", exec(mode = "")); assertEquals("bad_mode", exec(mode = "shred"))
    }

    @Test fun executeRefusesNonce() {
        assertEquals("nonce_mismatch", exec(nonce = "ABCDEFGX"))
        assertEquals("nonce_mismatch", exec(nonce = ""))
        assertEquals("nonce_mismatch", exec(stored = "", nonce = ""))   // nonce cleared (replaced/cancelled)
        assertEquals("nonce_mismatch", exec(nonce = "abcdefgh"))
    }

    @Test fun executeRefusesHash() {
        assertEquals("hash_mismatch", exec(hash = "0".repeat(64)))
        assertEquals("hash_mismatch", exec(hash = ""))
        // The stored list was changed after the freeze: the recomputed hash no longer matches the stored one.
        assertEquals("hash_mismatch", exec(list = sel + ("DCIM/c.jpg" to 5L)))
        assertEquals("hash_mismatch", exec(list = emptyList(), storedHash = S.hashOf(emptyList()), hash = S.hashOf(emptyList())))
    }

    // ------------------------------------------------------------ propose: plan (≤200, seen, size now)

    @Test fun planAcceptsOnlySeenAndUnchanged() {
        val seen = mapOf("a" to 1L, "b" to 2L, "c" to 3L)
        val now = mapOf("a" to 1L, "b" to 99L)   // b resized, c gone
        val p = S.plan(listOf("a", "b", "c", "x", "a"), { seen[it] }, { now[it] })
        assertNull(p.error)
        assertEquals(listOf("a"), p.items.map { it.path })
        assertEquals(listOf("b", "c", "x"), p.rejected)
        assertEquals(1L, p.bytes)
    }

    @Test fun planNothingValid() {
        val p = S.plan(listOf("x"), { null }, { 1L })
        assertEquals("nothing_valid", p.error)
        assertEquals(listOf("x"), p.rejected)
    }

    @Test fun planCapIs200DistinctPaths() {
        val ok = (1..200).map { "f$it" }
        assertNull(S.plan(ok, { 1L }, { 1L }).error)
        assertEquals(200, S.plan(ok + ok, { 1L }, { 1L }).items.size)          // duplicates collapse
        val over = (1..201).map { "f$it" }
        val p = S.plan(over, { 1L }, { 1L })
        assertEquals("too_many", p.error)
        assertTrue("a refused request accepts nothing", p.items.isEmpty())
        assertEquals(200, S.MAX_PATHS)
    }

    // ------------------------------------------------------------ re-check right before acting

    private val items = listOf(
        DeleteGate.Item("Movies/a.mp4", 10, media = true),
        DeleteGate.Item("Download/b.zip", 20, media = false),
        DeleteGate.Item("Download/gone.pdf", 30, media = false),
        DeleteGate.Item("DCIM/c.jpg", 40, media = true),
    )
    private val sizes = mapOf("Movies/a.mp4" to 10L, "Download/b.zip" to 20L, "DCIM/c.jpg" to 41L)

    @Test fun recheckDeleteMode() {
        val r = S.recheck(items, "delete") { sizes[it] }
        assertEquals(listOf("Movies/a.mp4"), r.media.map { it.path })
        assertEquals(listOf("Download/b.zip"), r.direct.map { it.path })
        assertEquals(listOf("Download/gone.pdf" to "missing", "DCIM/c.jpg" to "changed"), r.failed.map { it.first.path to it.second })
        assertTrue(r.skipped.isEmpty())
        assertEquals(2, r.acting.size)
    }

    @Test fun recheckTrashModeNeverDeletesNonMedia() {
        val r = S.recheck(items, "trash") { sizes[it] }
        assertEquals(listOf("Movies/a.mp4"), r.media.map { it.path })
        assertTrue("trash mode must not permanently delete anything", r.direct.isEmpty())
        assertEquals(listOf("Download/b.zip" to "non_media"), r.skipped.map { it.first.path to it.second })
    }

    @Test fun recheckEverythingGone() {
        val r = S.recheck(items, "delete") { null }
        assertTrue(r.acting.isEmpty())
        assertEquals(4, r.failed.size)
    }

    // ------------------------------------------------------------ constant-time compare

    @Test fun constEq() {
        assertTrue(S.constEq("ABCDEFGH", "ABCDEFGH"))
        assertFalse(S.constEq("ABCDEFGH", "ABCDEFG"))
        assertFalse(S.constEq("", "A"))
        assertTrue(S.constEq("", ""))
    }

    @Test fun deterministicNonceWithSeededRandomIsStable() {
        assertEquals(S.nonce(Random(7)), S.nonce(Random(7)))
    }
}
