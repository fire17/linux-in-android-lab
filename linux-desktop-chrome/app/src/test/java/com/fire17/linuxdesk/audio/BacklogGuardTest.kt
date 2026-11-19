package com.fire17.linuxdesk.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BacklogGuardTest {
    private val ms = 48 * 4                        // bytes per ms, s16le stereo 48 kHz
    private fun guard() = BacklogGuard(maxBytes = 200 * ms, targetBytes = 40 * ms, frameBytes = 4, windowMs = 2_000)

    @Test fun burstsAreNeverCut() {
        // Null sink renders ahead: backlog swings 0 → 300 ms → 0 every 300 ms. The old rule cut every swing.
        val g = guard()
        var t = 0L
        var dropped = 0
        repeat(200) { i ->
            val backlog = if (i % 3 == 0) 300 * ms else (i % 3) * 10 * ms
            dropped += g.feed(backlog, t); t += 100
        }
        assertEquals(0, dropped)
        assertEquals(300 * ms, g.takeMaxBurst())
    }

    @Test fun sustainedDriftIsDroppedOnceToTarget() {
        val g = guard()
        var t = 0L
        val drops = mutableListOf<Int>()
        repeat(25) { val d = g.feed(260 * ms, t); if (d > 0) drops += d; t += 100 }
        assertEquals(1, drops.size)                        // one cut per 2 s window, not one per read
        assertEquals((260 - 40) * ms, drops[0])           // floor 260 ms → down to the 40 ms target
        assertEquals(0, drops[0] % 4)                     // frame-aligned: channels never swap
    }

    @Test fun neverDropsMoreThanIsQueued() {
        val g = guard()
        g.feed(250 * ms, 0)
        val d = g.feed(250 * ms, 2_000)
        assertTrue(d in 1..(250 * ms))
    }

    @Test fun belowLimitKeepsEverything() {
        val g = guard()
        var t = 0L
        var dropped = 0
        repeat(100) { dropped += g.feed(190 * ms, t); t += 100 }
        assertEquals(0, dropped)
    }
}
