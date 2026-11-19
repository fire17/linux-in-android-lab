package com.fire17.phoneinphone.ondevice

import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal streaming tar extractor for rootfs tarballs (ustar + GNU long names + pax path/linkpath).
 *
 *  - symlinks  -> Os.symlink (kept as-is, absolute targets are resolved later by proot inside the guest)
 *  - hardlinks -> Os.link, falling back to a copy (app storage may refuse link(2) under some policies)
 *  - devices / fifos are skipped (they cannot be created without root; proot binds the host /dev)
 *  - permission bits are applied with Os.chmod (setuid/setgid stripped: meaningless for an app uid)
 *
 * Paths are sanitized: absolute and ".." entries are rejected so a hostile tarball can't escape [dest].
 */
class TarExtractor(private val dest: File, private val onProgress: (Long, String) -> Unit = { _, _ -> }) {

    var files = 0L; private set
    var dirs = 0L; private set
    var symlinks = 0L; private set
    var hardlinks = 0L; private set
    var skipped = 0L; private set
    var bytes = 0L; private set

    fun extractGz(input: InputStream) = extract(GZIPInputStream(BufferedInputStream(input, 1 shl 16), 1 shl 16))

    fun extract(raw: InputStream) {
        dest.mkdirs()
        val destCanon = dest.canonicalPath
        val header = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        val deferredHardlinks = mutableListOf<Pair<File, File>>()

        while (true) {
            if (!readFully(raw, header)) break
            if (header.all { it.toInt() == 0 }) break // end-of-archive block

            val type = header[156].toInt().toChar()
            val size = parseOctal(header, 124, 12)
            val mode = parseOctal(header, 100, 8).toInt()
            val name = longName ?: paxPath ?: buildName(header)
            val link = longLink ?: paxLink ?: cstr(header, 157, 100)

            when (type) {
                'L' -> { longName = readString(raw, size).trimEnd('\u0000'); continue }
                'K' -> { longLink = readString(raw, size).trimEnd('\u0000'); continue }
                'x' -> {
                    val pax = parsePax(readString(raw, size))
                    paxPath = pax["path"]; paxLink = pax["linkpath"]
                    continue
                }
                'g' -> { skipData(raw, size); continue }
            }
            longName = null; longLink = null; paxPath = null; paxLink = null

            val rel = sanitize(name)
            if (rel == null) { skipData(raw, size); skipped++; continue }
            val out = File(dest, rel)
            if (!out.canonicalPath.startsWith(destCanon) && type != '2') {
                // a symlinked parent may point outside dest; refuse to write through it
                skipData(raw, size); skipped++; continue
            }

            when (type) {
                '5' -> { out.mkdirs(); chmod(out, mode or 0b111_000_000); dirs++ }
                '2' -> {
                    out.parentFile?.mkdirs()
                    if (out.exists() || isSymlink(out)) out.delete()
                    try { Os.symlink(link, out.absolutePath); symlinks++ } catch (e: Exception) { skipped++ }
                }
                '1' -> {
                    val target = sanitize(link)?.let { File(dest, it) }
                    if (target != null) deferredHardlinks += target to out else skipped++
                }
                '0', '\u0000', '7' -> {
                    out.parentFile?.mkdirs()
                    if (isSymlink(out)) out.delete()
                    FileOutputStream(out).use { fos -> copyN(raw, fos, size) }
                    chmod(out, mode)
                    files++; bytes += size
                    if (files % 200 == 0L) onProgress(bytes, rel)
                    skipPadding(raw, size)
                    continue
                }
                else -> skipped++ // '3','4' char/block devices, '6' fifo
            }
            skipData(raw, size)
        }

        for ((target, link) in deferredHardlinks) {
            link.parentFile?.mkdirs()
            link.delete()
            try {
                Os.link(target.absolutePath, link.absolutePath)
            } catch (e: Exception) {
                try {
                    if (isSymlink(target)) Os.symlink(Os.readlink(target.absolutePath), link.absolutePath)
                    else { target.copyTo(link, overwrite = true); chmod(link, Os.stat(target.absolutePath).st_mode) }
                } catch (ignored: Exception) { skipped++; continue }
            }
            hardlinks++
        }
    }

    fun summary() = "files=$files dirs=$dirs symlinks=$symlinks hardlinks=$hardlinks skipped=$skipped bytes=$bytes"

    // ---------------------------------------------------------------- helpers

    private fun buildName(h: ByteArray): String {
        val name = cstr(h, 0, 100)
        val magic = cstr(h, 257, 6)
        return if (magic.startsWith("ustar")) {
            val prefix = cstr(h, 345, 155)
            if (prefix.isNotEmpty()) "$prefix/$name" else name
        } else name
    }

    private fun sanitize(p: String): String? {
        var s = p.trimStart('/')
        while (s.startsWith("./")) s = s.substring(2)
        s = s.trimEnd('/')
        if (s.isEmpty() || s == ".") return null
        if (s.split('/').any { it == ".." }) return null
        return s
    }

    private fun parsePax(s: String): Map<String, String> {
        val m = HashMap<String, String>()
        var i = 0
        val bytes = s.toByteArray(Charsets.UTF_8)
        while (i < bytes.size) {
            var sp = i
            while (sp < bytes.size && bytes[sp] != ' '.code.toByte()) sp++
            val len = String(bytes, i, sp - i).toIntOrNull() ?: break
            val rec = String(bytes, sp + 1, (len - (sp - i) - 2).coerceAtLeast(0), Charsets.UTF_8)
            val eq = rec.indexOf('=')
            if (eq > 0) m[rec.substring(0, eq)] = rec.substring(eq + 1)
            i += len
        }
        return m
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
        // GNU base-256 for large sizes
        if (b[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }
        var v = 0L
        for (i in off until off + len) {
            val c = b[i].toInt()
            if (c == 0 || c == ' '.code) { if (v != 0L) break else continue }
            if (c < '0'.code || c > '7'.code) break
            v = (v shl 3) + (c - '0'.code)
        }
        return v
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) { if (off == 0) return false else throw IOException("truncated tar header") }
            off += n
        }
        return true
    }

    private fun readString(input: InputStream, size: Long): String {
        val b = ByteArray(size.toInt())
        if (size > 0 && !readFully(input, b)) throw IOException("truncated tar")
        skipPadding(input, size)
        return String(b, Charsets.UTF_8)
    }

    private fun copyN(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(1 shl 16)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("truncated tar data")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skipData(input: InputStream, size: Long) {
        if (size <= 0) return
        copyN(input, NullOut, size)
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = ((512 - (size % 512)) % 512).toInt()
        if (pad > 0) copyN(input, NullOut, pad.toLong())
    }

    private fun chmod(f: File, mode: Int) {
        try { Os.chmod(f.absolutePath, mode and 0b111_111_111) } catch (ignored: Exception) {}
    }

    private fun isSymlink(f: File): Boolean = try {
        android.system.OsConstants.S_ISLNK(Os.lstat(f.absolutePath).st_mode)
    } catch (ignored: Exception) { false }
}

/** OutputStream.nullOutputStream() is API 33+; minSdk is 26. */
private object NullOut : java.io.OutputStream() {
    override fun write(b: Int) {}
    override fun write(b: ByteArray, off: Int, len: Int) {}
}
