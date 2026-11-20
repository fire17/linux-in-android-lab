package com.fire17.linuxdesk.linux

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Parallel HTTP Range download as ONE in-order stream (Tami 2026-11-20: "<1 min, massive parallel download").
 * [conns] workers fetch [seg]-byte segments with `Range` requests; [read] hands them out strictly in order, so the
 * caller can pipe it straight into a decompressor + tar (StreamInstall). Memory is bounded: a worker never runs more
 * than [ahead] segments past the reader (default 16 x 4 MiB = 64 MiB). Each segment is retried 3 times; a final
 * failure surfaces as an IOException from [read]. Redirects are followed on every request (GitHub release URLs
 * redirect to short-lived signed URLs, so the final URL is never cached).
 */
class ParallelRangeStream(
    private val url: String,
    private val total: Long,
    conns: Int = 8,
    private val seg: Int = 4 shl 20,
    private val ahead: Int = 16,
) : InputStream() {
    private val lock = Object()
    private val ready = HashMap<Long, ByteArray>()
    private val nSeg = (total + seg - 1) / seg
    private var nextClaim = 0L
    private var taken = 0L
    private var error: IOException? = null
    @Volatile private var closed = false
    private var cur: ByteArray? = null
    private var pos = 0

    init {
        require(total > 0) { "total must be known" }
        repeat(conns) { Thread(::work, "range-$it").apply { isDaemon = true; start() } }
    }

    private fun work() {
        while (true) {
            val i = synchronized(lock) {
                while (!closed && error == null && nextClaim < nSeg && nextClaim >= taken + ahead) lock.wait()
                if (closed || error != null || nextClaim >= nSeg) -1L else nextClaim++
            }
            if (i < 0) return
            try {
                val b = fetch(i)
                synchronized(lock) { ready[i] = b; lock.notifyAll() }
            } catch (e: Exception) {
                synchronized(lock) { if (error == null) error = e as? IOException ?: IOException(e); lock.notifyAll() }
                return
            }
        }
    }

    private fun fetch(i: Long): ByteArray {
        val start = i * seg
        val end = minOf(total, start + seg) - 1
        val buf = ByteArray((end - start + 1).toInt())
        var last: Exception? = null
        for (attempt in 1..3) {
            if (closed) throw IOException("closed")
            val c = Http.open(url, "bytes=$start-$end")
            try {
                if (c.responseCode != 206) throw IOException("HTTP ${c.responseCode} for range $start-$end of $url")
                c.inputStream.use { ins ->
                    var off = 0
                    while (off < buf.size) {
                        val r = ins.read(buf, off, buf.size - off)
                        if (r < 0) throw IOException("range $start-$end ended at ${start + off}")
                        off += r
                    }
                }
                return buf
            } catch (e: Exception) {
                last = e
                if (attempt < 3) Thread.sleep(500L * attempt)
            } finally { c.disconnect() }
        }
        throw last as? IOException ?: IOException(last)
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("closed")
        if (len == 0) return 0
        var c = cur
        if (c == null || pos >= c.size) {
            if (taken >= nSeg) return -1
            c = synchronized(lock) {
                while (!ready.containsKey(taken) && error == null) lock.wait()
                val got = ready.remove(taken) ?: throw error!!
                taken++
                lock.notifyAll()
                got
            }
            cur = c; pos = 0
        }
        val n = minOf(len, c.size - pos)
        System.arraycopy(c, pos, b, off, n)
        pos += n
        return n
    }

    override fun close() {
        closed = true
        synchronized(lock) { ready.clear(); lock.notifyAll() }
    }
}

/** Small HTTP helpers shared by PrebuiltRootfs and ArchRootfsDownloader. */
object Http {
    fun open(url: String, range: String? = null, method: String = "GET", timeoutMs: Int = 20_000): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = maxOf(timeoutMs, 60_000)
        c.instanceFollowRedirects = true
        c.requestMethod = method
        c.setRequestProperty("User-Agent", "LinuxDesk")
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    /** Content length when HEAD answers 200 (after redirects), else null. Never throws. */
    fun headLength(url: String, timeoutMs: Int = 10_000): Long? = try {
        val c = open(url, method = "HEAD", timeoutMs = timeoutMs)
        try { if (c.responseCode == 200) c.contentLengthLong else null } finally { c.disconnect() }
    } catch (_: Exception) { null }

    fun text(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /**
     * Mirror race (Tami 07:16, Israel: the fixed US-first order was slow): every base gets a 1 MiB Range probe of
     * [file] in parallel, bounded by [timeoutMs]; returns the bases fastest first, then the ones that failed or timed
     * out in their original order (so a slow network still tries everything).
     */
    fun rankBySpeed(bases: List<String>, file: String, timeoutMs: Int = 3_000): List<String> {
        val pool = Executors.newFixedThreadPool(bases.size)
        try {
            val futures = bases.map { base ->
                pool.submit<Long> {
                    val t0 = System.nanoTime()
                    val c = open(base + file, "bytes=0-1048575", timeoutMs = timeoutMs)
                    try {
                        if (c.responseCode != 206) return@submit Long.MAX_VALUE
                        c.inputStream.use { val buf = ByteArray(64 * 1024); while (it.read(buf) >= 0) { } }
                        System.nanoTime() - t0
                    } finally { c.disconnect() }
                }
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong() + 500)
            val times = futures.map { f ->
                try { f.get(maxOf(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) } catch (_: Exception) { Long.MAX_VALUE }
            }
            return bases.indices.sortedWith(compareBy({ times[it] }, { it })).map { bases[it] }
        } finally { pool.shutdownNow() }
    }
}
