package com.fire17.linuxdesk.linux

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * A2 (notes/andro/COMPARE.md): a ONE-PASS rootfs install. The raw bytes go network → [DigestingInputStream] (MD5 +
 * SHA-256 + byte count of exactly what was downloaded) → gunzip → tar → the staging dir (`rootfs-*.partial`). The
 * archive is never written to disk. Only when the stream has ended AND [run]'s verify step accepts the digests may the
 * caller [swap] staging → final. Any IO error, truncation or hash mismatch deletes staging and rethrows, so the old
 * rootfs is never touched.
 *
 * Not resumable: a broken stream starts over (the file mode, setting streamInstall OFF, keeps HTTP Range resume).
 * Our own code (andromarchy's GPL extractor was not used).
 */
object StreamInstall {

    class Digests(val md5: String, val sha256: String, val bytes: Long)

    /** The stream ended but its digests do not match what we trust. */
    class VerifyFailed(msg: String) : IOException(msg)

    /** Hashes and counts every byte read from [inner] (including skipped ones), reporting progress via [onBytes]. */
    class DigestingInputStream(inner: InputStream, private val onBytes: (Long) -> Unit = {}) : FilterInputStream(inner) {
        private val md5 = MessageDigest.getInstance("MD5")
        private val sha = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) { md5.update(b.toByte()); sha.update(b.toByte()); bytes++; onBytes(bytes) }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) { md5.update(b, off, r); sha.update(b, off, r); bytes += r; onBytes(bytes) }
            return r
        }

        /** Skipped bytes must still be hashed: read and discard. */
        override fun skip(n: Long): Long {
            val buf = ByteArray(8192)
            var left = n
            while (left > 0) {
                val r = read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) break
                left -= r
            }
            return n - left
        }

        override fun markSupported() = false

        /** Reads to EOF: the bytes after the tar end-of-archive marker and the gzip trailer are part of the hash. */
        fun drain() { val buf = ByteArray(64 * 1024); while (read(buf, 0, buf.size) >= 0) { /* hashing */ } }

        fun digests() = Digests(hex(md5.digest()), hex(sha.digest()), bytes)
    }

    /**
     * Streams [raw] into [extract] (which writes into [staging]), then drains the rest, checks the length against
     * [total] (when > 0) and asks [verify] (null = accept, else the error). On ANY failure [staging] is deleted with
     * [deleteTree] and the exception is rethrown. [onBytes] gets (bytesSoFar, total).
     */
    fun <T> run(
        raw: InputStream,
        total: Long,
        staging: File,
        extract: (InputStream) -> T,
        verify: (Digests) -> String?,
        deleteTree: (File) -> Unit = TarGzExtractor::deleteTreeNoFollow,
        onBytes: (Long, Long) -> Unit = { _, _ -> },
    ): Pair<T, Digests> {
        try {
            val ds = DigestingInputStream(raw) { onBytes(it, total) }
            val result = extract(ds)
            ds.drain()
            val d = ds.digests()
            if (total > 0 && d.bytes != total) throw IOException("download ended at ${d.bytes} of $total bytes")
            verify(d)?.let { throw VerifyFailed(it) }
            return result to d
        } catch (t: Throwable) {
            deleteTree(staging)
            throw t
        }
    }

    /**
     * The atomic swap RootfsInstaller always did: final → [old], [staging] → final, then delete [old]. Call it only
     * after [run] returned (verified).
     */
    fun swap(staging: File, final: File, old: File, deleteTree: (File) -> Unit = TarGzExtractor::deleteTreeNoFollow) {
        deleteTree(old)
        if (final.exists() && !final.renameTo(old)) deleteTree(final)
        if (!staging.renameTo(final)) throw IOException("rename ${staging.path} -> ${final.path} failed")
        deleteTree(old)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

/** A7: refuse to start a rootfs install that cannot fit, with a clear message (pure; RootfsInstaller feeds StatFs). */
object FreeSpace {
    const val GIB = 1024L * 1024 * 1024
    /** Arch: the 0.8 GB tarball, ~0.7 GB unpacked (kernel/firmware skipped), then ~1.4 GB of Omarchy packages. */
    const val ARCH_FLOOR = 5 * GIB
    /** Alpine: a ~4 MB bundled minirootfs; Chromium and its deps add a few hundred MB later. */
    const val ALPINE_FLOOR = GIB / 2

    fun needed(archiveBytes: Long, arch: Boolean): Long = maxOf(archiveBytes * 3, if (arch) ARCH_FLOOR else ALPINE_FLOOR)

    /** null = enough space; otherwise the message to show instead of starting. */
    fun check(availableBytes: Long, archiveBytes: Long, arch: Boolean, what: String): String? {
        val need = needed(archiveBytes, arch)
        if (availableBytes >= need) return null
        return "Not enough free space to install $what: ${gb(availableBytes)} GB free, ${gb(need)} GB needed. " +
            "Free up ${gb(need - availableBytes)} GB on the phone, then Start again."
    }

    private fun gb(n: Long) = "%.1f".format(java.util.Locale.ROOT, n.toDouble() / GIB)
}
