package com.fire17.linuxdesk.hw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PerfStatTest {
    // A real line from Tami's phone (21:41, pid 6650 = proot), shortened after rss.
    private val proot = "6650 (libproot.so) S 6645 6540 0 0 -1 4194560 1234 0 0 0 128 540 0 0 20 0 1 0 5000 12000000 1084 18446744073709551615"

    @Test fun parsesUtimeStimeAndRss() {
        val p = PerfStat.parseStat(proot)!!
        assertEquals(6650, p.pid)
        assertEquals("libproot.so", p.name)
        assertEquals(128L + 540L, p.ticks)
        assertEquals(1084L, p.rssPages)
    }

    @Test fun nameWithSpacesAndParens() {
        val p = PerfStat.parseStat("42 (Thread (x) y) R 1 1 0 0 -1 0 0 0 0 0 7 3 0 0 20 0 1 0 1 1 9 0")!!
        assertEquals("Thread (x) y", p.name)
        assertEquals(10L, p.ticks)
    }

    @Test fun malformedIsNull() {
        assertNull(PerfStat.parseStat(""))
        assertNull(PerfStat.parseStat("12 (short) S 1 2"))
        assertNull(PerfStat.parseStat("x (a) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21"))
    }

    @Test fun diffIsPercentOfOneCoreBusiestFirst() {
        val a = mapOf(1 to PerfStat.Proc(1, "proot", 100, 0), 2 to PerfStat.Proc(2, "chrome", 50, 25600))
        val b = mapOf(1 to PerfStat.Proc(1, "proot", 105, 0), 2 to PerfStat.Proc(2, "chrome", 100, 25600),
            3 to PerfStat.Proc(3, "new", 999, 0))
        val d = PerfStat.diff(a, b, 5_000)
        assertEquals(listOf("chrome", "proot"), d.map { it.name })   // the new pid has no baseline: left out
        assertEquals(10.0, d[0].cpuPercent, 1e-9)   // 50 ticks / (100 Hz × 5 s) = 10 % of one core
        assertEquals(1.0, d[1].cpuPercent, 1e-9)    // 5 ticks over 5 s, the measured idle proot of 21:41
        assertEquals(100L, d[0].rssMb)              // 25600 pages × 4 KiB
    }

    @Test fun memAvailable() {
        assertEquals(3379L, PerfStat.memAvailableMb("MemTotal:  11000000 kB\nMemAvailable:    3460096 kB\n"))
        assertEquals(-1L, PerfStat.memAvailableMb("MemTotal: 1 kB\n"))
    }

    @Test fun reportReadsAFakeProc() {
        val dir = Files.createTempDirectory("perfproc").toFile()
        try {
            File(dir, "7").mkdirs(); File(dir, "7/stat").writeText(proot.replaceFirst("6650", "7"))
            File(dir, "self").mkdirs()
            File(dir, "meminfo").writeText("MemAvailable: 2097152 kB\n")
            PerfStat.soundLine = { "Sound: playing, underruns 0" }
            val r = PerfStat.report(10, dir)
            assertTrue(r, r.contains("libproot.so (7)"))
            assertTrue(r, r.contains("Memory available: 2048 MB"))
            assertTrue(r, r.contains("Sound: playing, underruns 0"))
        } finally { dir.deleteRecursively() }
    }
}
