package com.fire17.linuxdesk.linux

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A2 one-pass install (StreamInstall) on the plain JVM. TarGzExtractor needs android.system.Os, so the test feeds
 * StreamInstall.run a tiny ustar reader (files + dirs only) as its `extract` step; the pipeline under test (digest
 * wrapper, drain, length check, verify, staging cleanup, swap) is the production code.
 */
class StreamInstallTest {

    private lateinit var root: File
    private val files = linkedMapOf(
        "etc/os-release" to "NAME=\"Arch Linux ARM\"\n".toByteArray(),
        "usr/bin/hello" to ByteArray(70_000) { (it * 31).toByte() },
        "root/.keep" to ByteArray(0),
    )

    @Before fun setUp() { root = Files.createTempDirectory("stream-install-test").toFile() }
    @After fun tearDown() { root.deleteRecursively() } // test tree has no symlinks

    // --- fixture: a real tar.gz built in memory ---------------------------------------------------------------------
    private fun tarGz(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz ->
            fun header(name: String, size: Long, type: Char) {
                val h = ByteArray(512)
                fun put(off: Int, s: String) { s.toByteArray().copyInto(h, off) }
                put(0, name); put(100, "0000644\u0000"); put(108, "0000000\u0000"); put(116, "0000000\u0000")
                put(124, "%011o".format(size) + "\u0000"); put(136, "00000000000\u0000")
                h[156] = type.code.toByte(); put(257, "ustar\u0000"); put(263, "00")
                for (i in 148 until 156) h[i] = ' '.code.toByte()
                val sum = h.sumOf { it.toInt() and 0xFF }
                put(148, "%06o".format(sum) + "\u0000 ")
                gz.write(h)
            }
            val dirs = entries.keys.mapNotNull { it.substringBeforeLast('/', "").ifEmpty { null } }.toSortedSet()
            for (d in dirs) header("$d/", 0, '5')
            for ((n, data) in entries) {
                header(n, data.size.toLong(), '0'); gz.write(data)
                val pad = (512 - data.size % 512) % 512; gz.write(ByteArray(pad))
            }
            gz.write(ByteArray(1024)) // end-of-archive
        }
        return bos.toByteArray()
    }

    /** Minimal ustar extractor (regular files + dirs) standing in for TarGzExtractor, which needs android.system.Os. */
    private fun extractInto(dest: File): (InputStream) -> Int = { raw ->
        val tin = GZIPInputStream(raw)
        val h = ByteArray(512); var n = 0
        fun readFully(b: ByteArray, len: Int = b.size): Boolean {
            var off = 0
            while (off < len) { val r = tin.read(b, off, len - off); if (r < 0) { if (off == 0) return false; throw IOException("truncated tar") }; off += r }
            return true
        }
        while (readFully(h) && !h.all { it.toInt() == 0 }) {
            val name = String(h, 0, 100).trimEnd('\u0000')
            val size = String(h, 124, 11).trim().toLong(8)
            val out = File(dest, name)
            if (h[156] == '5'.code.toByte()) out.mkdirs() else {
                out.parentFile?.mkdirs()
                val data = ByteArray(size.toInt()); if (size > 0 && !readFully(data)) throw IOException("truncated tar file")
                out.writeBytes(data); n++
                val pad = ((512 - size % 512) % 512).toInt(); if (pad > 0 && !readFully(ByteArray(pad))) throw IOException("truncated tar pad")
            }
        }
        n
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
    private val del: (File) -> Unit = { it.deleteRecursively() }

    private fun finalWithOldContent(): File = File(root, "rootfs-arch").apply { mkdirs(); File(this, "OLD").writeText("old rootfs") }

    // --- tests ------------------------------------------------------------------------------------------------------

    @Test fun streamedTarGz_extractsExactFiles_andSwapsAfterHashMatch() {
        val archive = tarGz(files)
        val staging = File(root, "rootfs-arch.partial").apply { mkdirs() }
        val final = finalWithOldContent()
        val progress = ArrayList<Long>()
        val (count, d) = StreamInstall.run(ByteArrayInputStream(archive), archive.size.toLong(), staging,
            extract = extractInto(staging), verify = { if (it.sha256 == sha256(archive)) null else "bad" },
            deleteTree = del, onBytes = { got, _ -> progress.add(got) })
        assertEquals(files.size, count)
        assertEquals(sha256(archive), d.sha256)
        assertEquals(md5(archive), d.md5)
        assertEquals(archive.size.toLong(), d.bytes)
        assertEquals("progress ends at the full size", archive.size.toLong(), progress.last())
        assertTrue("progress is monotonic", progress.zipWithNext().all { (a, b) -> b >= a })
        StreamInstall.swap(staging, final, File(root, "rootfs-arch.old"), del)
        for ((n, data) in files) assertArrayEquals(n, data, File(final, n).readBytes())
        assertFalse("old rootfs content replaced", File(final, "OLD").exists())
        assertFalse(staging.exists()); assertFalse(File(root, "rootfs-arch.old").exists())
    }

    @Test fun hashMismatch_deletesStaging_finalUntouched() {
        val archive = tarGz(files)
        val staging = File(root, "rootfs-arch.partial").apply { mkdirs() }
        val final = finalWithOldContent()
        try {
            StreamInstall.run(ByteArrayInputStream(archive), archive.size.toLong(), staging, extractInto(staging),
                verify = { "md5 mismatch: got ${it.md5}" }, deleteTree = del)
            fail("expected VerifyFailed")
        } catch (e: StreamInstall.VerifyFailed) {
            assertTrue(e.message!!.contains("md5 mismatch"))
        }
        assertFalse("staging deleted", staging.exists())
        assertEquals("old rootfs", File(final, "OLD").readText())
        assertEquals(1, final.list()!!.size)
    }

    @Test fun truncatedStream_deletesStaging_finalUntouched() {
        val archive = tarGz(files)
        val cut = archive.copyOf(archive.size / 2)
        val staging = File(root, "rootfs-arch.partial").apply { mkdirs() }
        val final = finalWithOldContent()
        var verifyCalled = false
        try {
            StreamInstall.run(ByteArrayInputStream(cut), archive.size.toLong(), staging, extractInto(staging),
                verify = { verifyCalled = true; null }, deleteTree = del)
            fail("expected an IOException")
        } catch (e: IOException) { /* EOFException from gunzip or our truncated-tar error: both are IOExceptions */ }
        assertFalse("verify never runs on a broken stream", verifyCalled)
        assertFalse("staging deleted", staging.exists())
        assertEquals("old rootfs", File(final, "OLD").readText())
    }

    @Test fun shortButValidLookingStream_failsLengthCheck() {
        // The server closes early but what arrived is a complete archive: the Content-Length check still catches it.
        val archive = tarGz(files)
        val staging = File(root, "s.partial").apply { mkdirs() }
        try {
            StreamInstall.run(ByteArrayInputStream(archive), archive.size + 100L, staging, extractInto(staging), { null }, del)
            fail("expected IOException")
        } catch (e: IOException) { assertTrue(e.message!!.contains("download ended at")) }
        assertFalse(staging.exists())
    }

    @Test fun digestCoversTrailingBytesAndSkips() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val ds = StreamInstall.DigestingInputStream(ByteArrayInputStream(data))
        ds.read(ByteArray(10)); ds.skip(50_000); ds.read(); ds.drain()
        val d = ds.digests()
        assertEquals(sha256(data), d.sha256); assertEquals(md5(data), d.md5); assertEquals(data.size.toLong(), d.bytes)
    }

    @Test fun freeSpace_archFloorAndMultiplier() {
        val g = FreeSpace.GIB
        assertEquals(5 * g, FreeSpace.needed(829_367_415L, arch = true))      // 3 x 0.83 GB < 5 GB floor
        assertEquals(6 * g, FreeSpace.needed(2 * g, arch = true))             // 3 x 2 GB > floor
        assertEquals(g / 2, FreeSpace.needed(0, arch = false))
        assertNull(FreeSpace.check(6 * g, 829_367_415L, true, "Arch"))
        val msg = FreeSpace.check(2 * g, 829_367_415L, true, "Arch Linux ARM (Omarchy)")
        assertNotNull(msg)
        assertTrue(msg!!, msg.contains("2.0 GB free") && msg.contains("5.0 GB needed") && msg.contains("Free up 3.0 GB"))
    }
}
