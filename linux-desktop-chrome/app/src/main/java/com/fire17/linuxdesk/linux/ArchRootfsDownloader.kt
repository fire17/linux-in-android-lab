package com.fire17.linuxdesk.linux

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * First-use download of the official Arch Linux ARM aarch64 rootfs tarball (NOT bundled in the APK: 829 MB).
 *
 * Source: os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz. That host redirects plain HTTP to a geo
 * mirror and its HTTPS certificate does not match its name (checked 2026-11-19), and Android blocks cleartext by
 * default, so the app downloads from HTTPS mirrors whose certificates DO verify (fl.us, ca.us, de3; checked
 * 2026-11-19), in that order.
 *
 * Verification, honestly:
 *  - The mirror's published ArchLinuxARM-aarch64-latest.tar.gz.md5 is fetched over HTTPS and must match. That
 *    catches corruption and a truncated download. MD5 alone is not a strong defence against a hostile mirror.
 *  - The tarball is "latest" (rolling), so no sha256 can be pinned in the APK forever. We pin the build verified
 *    on 2026-11-19 (KNOWN_SHA256 below). If the download matches it, the log says "pinned sha256 OK". If the mirror
 *    has a newer build, the md5 must still match and the log says so plainly.
 *  - On 2026-11-19 the lane also checked the detached GPG signature (.sig) on a Mac: "Good signature from Arch
 *    Linux ARM Build System", key 68B3537F39A313B3E574D06777193F152BDBE6A6. The app does NOT check GPG on the
 *    phone (no gpg on the host side); setup-omarchy.sh does verify every package pacman installs afterwards.
 *
 * File mode (setting streamInstall OFF): the download resumes (HTTP Range) after an interrupted first run.
 * Stream mode (default, [streamInto]): one pass, nothing stored, not resumable.
 */
class ArchRootfsDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ArchRootfs"
        /** Marker version for RootfsInstaller (bump to force a re-install of rootfs-arch). */
        const val VERSION = "archlinuxarm-aarch64-v1"
        const val FILE = "ArchLinuxARM-aarch64-latest.tar.gz"
        val MIRRORS = listOf(
            "https://fl.us.mirror.archlinuxarm.org/os/",
            "https://ca.us.mirror.archlinuxarm.org/os/",
            "https://de3.mirror.archlinuxarm.org/os/",
        )
        /** The build the lane downloaded and GPG-verified on 2026-11-19 (Last-Modified 2026-08-05, 829,367,415 B). */
        const val KNOWN_SHA256 = "42a4eeaa038994ffd31fa173256ef2f0ef511358eeb41b9ea1f8626391b9b319"
        const val KNOWN_MD5 = "23eec86365b24f7913c403e8f4e8719b"
        const val KNOWN_SIZE = 829_367_415L
        /** Kernel, device trees, firmware and modules: about 1.38 GB unpacked, useless under proot. */
        val SKIP_PREFIXES = listOf("boot/", "usr/lib/firmware/", "usr/lib/modules/")
    }

    private val dir: File get() = File(context.filesDir, "downloads").apply { mkdirs() }

    /** Returns the verified tarball (downloading or resuming it as needed). Blocking. */
    fun ensureDownloaded(onProgress: (String) -> Unit, onPercent: (Int) -> Unit): File {
        val done = File(dir, FILE)
        val part = File(dir, "$FILE.part")
        var lastError: Exception? = null
        for (base in MIRRORS) {
            try {
                val md5 = fetchText(base + FILE + ".md5").trim().split(Regex("\\s+")).first().lowercase()
                if (!md5.matches(Regex("[0-9a-f]{32}"))) throw IOException("bad .md5 from $base: '$md5'")
                if (md5 != KNOWN_MD5) onProgress("note: the mirror has a newer Arch Linux ARM build than the one verified on 2026-11-19 (md5 $md5)")
                if (done.isFile) {
                    val (gotMd5, _) = hashes(done)
                    if (gotMd5 == md5) { onPercent(100); return done }
                    done.delete()
                }
                download(base + FILE, part, onProgress, onPercent)
                onProgress("Verifying download …")
                val (gotMd5, gotSha) = hashes(part)
                if (gotMd5 != md5) {
                    part.delete()
                    throw IOException("md5 mismatch from $base: got $gotMd5, mirror says $md5")
                }
                onProgress(if (gotSha == KNOWN_SHA256) "md5 OK, pinned sha256 OK (the 2026-11-19 GPG-verified build)"
                    else "md5 OK; sha256 $gotSha (a newer build than the pinned one; GPG not checked on the phone)")
                if (!part.renameTo(done)) throw IOException("rename ${part.path} failed")
                return done
            } catch (e: Exception) {
                Log.w(TAG, "mirror $base failed", e)
                onProgress("mirror ${URL(base).host} failed: ${e.javaClass.simpleName}: ${e.message}")
                lastError = e
            }
        }
        throw IOException("Arch Linux ARM download failed on every mirror (check network and free space: ~3 GB)", lastError)
    }

    /**
     * A2 stream mode (setting streamInstall, default ON): download → gunzip → tar straight into [staging], hashing the
     * raw bytes on the fly (StreamInstall). The tarball never touches the disk. Trust is the same as the file mode:
     * the pinned sha256 (the 2026-11-19 GPG-verified build) passes; a newer mirror build passes only if its md5 equals
     * the mirror's published .md5 (said plainly in the log). Any mismatch, truncation or IO error deletes [staging]
     * and tries the next mirror; the installed rootfs is never touched. Not resumable: a broken stream starts over.
     */
    fun streamInto(staging: File, onProgress: (String) -> Unit, onPercent: (Int) -> Unit): TarGzExtractor.Stats {
        // A tarball left by the file mode is useless here; free its 0.8 GB first.
        File(dir, FILE).delete(); File(dir, "$FILE.part").delete()
        var lastError: Exception? = null
        for (base in MIRRORS) {
            try {
                val md5 = fetchText(base + FILE + ".md5").trim().split(Regex("\\s+")).first().lowercase()
                if (!md5.matches(Regex("[0-9a-f]{32}"))) throw IOException("bad .md5 from $base: '$md5'")
                if (md5 != KNOWN_MD5) onProgress("note: the mirror has a newer Arch Linux ARM build than the one verified on 2026-11-19 (md5 $md5)")
                staging.mkdirs()
                val c = open(base + FILE, 0)
                try {
                    if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for ${base + FILE}")
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: if (md5 == KNOWN_MD5) KNOWN_SIZE else -1L
                    onProgress("Downloading + unpacking Arch Linux ARM in one pass (${mb(total)} MB) from ${URL(base).host}")
                    var lastPct = -1
                    val (stats, d) = StreamInstall.run(c.inputStream, total, staging,
                        extract = { TarGzExtractor(staging, SKIP_PREFIXES).extract(it, onProgress) },
                        verify = { d ->
                            when {
                                d.sha256 == KNOWN_SHA256 -> null
                                d.md5 != md5 -> "md5 mismatch from $base: got ${d.md5}, mirror says $md5"
                                else -> null
                            }
                        },
                        onBytes = { got, tot ->
                            if (tot > 0) {
                                val pct = (got * 100 / tot).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onPercent(pct)
                                    if (pct % 5 == 0) onProgress("download $pct% (${mb(got)} / ${mb(tot)} MB)")
                                }
                            }
                        })
                    onProgress(if (d.sha256 == KNOWN_SHA256) "md5 OK, pinned sha256 OK (the 2026-11-19 GPG-verified build)"
                        else "md5 OK; sha256 ${d.sha256} (a newer build than the pinned one; GPG not checked on the phone)")
                    return stats
                } finally { c.disconnect() }
            } catch (e: Exception) {
                Log.w(TAG, "mirror $base failed (stream)", e)
                onProgress("mirror ${URL(base).host} failed: ${e.javaClass.simpleName}: ${e.message}")
                TarGzExtractor.deleteTreeNoFollow(staging)
                lastError = e
            }
        }
        throw IOException("Arch Linux ARM download failed on every mirror (check the network; turn off the fast install " +
            "in Settings to use the resumable download)", lastError)
    }

    private fun fetchText(url: String): String {
        val c = open(url, 0)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun open(url: String, from: Long): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "LinuxDesk")
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        return c
    }

    private fun download(url: String, part: File, onProgress: (String) -> Unit, onPercent: (Int) -> Unit) {
        var have = if (part.isFile) part.length() else 0L
        val c = open(url, have)
        try {
            val code = c.responseCode
            val append = code == 206 && have > 0
            if (code != 200 && code != 206) throw IOException("HTTP $code for $url")
            if (!append) have = 0
            val total = if (append) have + c.contentLengthLong else c.contentLengthLong
            onProgress("Downloading Arch Linux ARM (${mb(total)} MB) from ${URL(url).host}" +
                if (append) ", resuming at ${mb(have)} MB" else "")
            val buf = ByteArray(256 * 1024)
            var got = have
            var lastPct = -1
            c.inputStream.use { ins ->
                java.io.FileOutputStream(part, append).use { out ->
                    while (true) {
                        val r = ins.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        got += r
                        if (total > 0) {
                            val pct = (got * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onPercent(pct)
                                if (pct % 5 == 0) onProgress("download $pct% (${mb(got)} / ${mb(total)} MB)")
                            }
                        }
                    }
                }
            }
            if (total > 0 && got != total) throw IOException("download ended at $got of $total bytes (will resume)")
        } finally { c.disconnect() }
    }

    private fun hashes(f: File): Pair<String, String> {
        val md5 = MessageDigest.getInstance("MD5")
        val sha = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val r = ins.read(buf)
                if (r < 0) break
                md5.update(buf, 0, r); sha.update(buf, 0, r)
            }
        }
        return hex(md5.digest()) to hex(sha.digest())
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun mb(n: Long) = n / (1024 * 1024)
}
