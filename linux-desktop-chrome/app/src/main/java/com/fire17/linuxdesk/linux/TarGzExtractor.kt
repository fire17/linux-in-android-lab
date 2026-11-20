package com.fire17.linuxdesk.linux

// Copied unchanged (apart from the package name) from the sibling project
// ideas-1911-lab/apps/linux-in-app (LinuxInApp), app/src/main/java/com/fire17/linuxinapp/rootfs/TarGzExtractor.kt.

import android.system.Os
import android.system.OsConstants
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal, dependency-free streaming tar.gz extractor for Linux rootfs tarballs on Android.
 *
 * Handles ustar + GNU long names ('L'/'K') + PAX extended headers ('x'), regular files,
 * directories, symlinks (via Os.symlink) and HARD LINKS. Android SELinux forbids link(2) for
 * untrusted apps, so hard links are materialised as byte copies of the already-extracted target
 * (the research recommended "hard links -> symlinks"; a copy is semantically safer because a
 * relative symlink would have to be recomputed per directory depth). Device nodes and FIFOs are
 * skipped (proot binds the host /dev instead).
 *
 * Permissions: mode & 0777, plus owner rw(x) so the app can always update/delete its own tree.
 * setuid/setgid bits are dropped (meaningless under proot's fake root).
 */
class TarGzExtractor(
    private val dest: File,
    /**
     * Guest paths (no leading "/", e.g. "boot/") whose entries are skipped without writing anything. Default:
     * none, which is the unchanged Alpine behaviour. The Arch Linux ARM tarball uses it to drop the kernel,
     * firmware and modules (about 1.4 GB of 2.1 GB unpacked), which proot never uses.
     */
    private val skipPrefixes: List<String> = emptyList(),
    /** false = [extract]'s input is already a plain tar (PrebuiltRootfs decodes zstd itself). */
    private val gzip: Boolean = true,
) {

    data class Stats(var files: Int = 0, var dirs: Int = 0, var symlinks: Int = 0, var hardlinks: Int = 0, var skipped: Int = 0, var bytes: Long = 0)

    private val destCanonical: String = dest.canonicalPath

    fun extract(input: InputStream, onProgress: (String) -> Unit = {}): Stats {
        val stats = Stats()
        val dirModes = ArrayList<Pair<File, Int>>()
        val tin = if (gzip) BufferedInputStream(GZIPInputStream(BufferedInputStream(input, 64 * 1024), 64 * 1024), 64 * 1024)
            else BufferedInputStream(input, 256 * 1024)
        val header = ByteArray(BLOCK)

        var paxPath: String? = null
        var paxLink: String? = null
        var paxSize: Long? = null
        var gnuLongName: String? = null
        var gnuLongLink: String? = null

        while (true) {
            if (!readFully(tin, header)) break
            if (header.all { it.toInt() == 0 }) break // end-of-archive marker

            val type = header[156].toInt().toChar()
            var size = parseNumeric(header, 124, 12)
            val name = paxPath ?: gnuLongName ?: joinUstarName(header)
            val link = paxLink ?: gnuLongLink ?: cString(header, 157, 100)
            if (paxSize != null) size = paxSize!!
            val mode = parseNumeric(header, 100, 8).toInt()

            when (type) {
                'x' -> { // PAX per-file header
                    val data = readData(tin, size)
                    val pax = parsePax(data)
                    paxPath = pax["path"]; paxLink = pax["linkpath"]; paxSize = pax["size"]?.toLongOrNull()
                    continue
                }
                'g' -> { skipData(tin, size); continue } // PAX global header: ignore
                'L' -> { gnuLongName = String(readData(tin, size), Charsets.UTF_8).trimEnd('\u0000'); continue }
                'K' -> { gnuLongLink = String(readData(tin, size), Charsets.UTF_8).trimEnd('\u0000'); continue }
            }
            // A real entry consumes any pending long-name / PAX overrides.
            paxPath = null; paxLink = null; paxSize = null; gnuLongName = null; gnuLongLink = null

            val out = if (skipPrefixes.isNotEmpty() && isSkipped(name)) null else safeResolve(name)
            if (out == null) { // empty name, "./", path traversal attempt, or a skipPrefixes match
                skipData(tin, size); stats.skipped++; continue
            }

            when (type) {
                '5' -> {
                    out.mkdirs(); stats.dirs++
                    dirModes.add(out to mode)
                    skipData(tin, size)
                }
                '2' -> {
                    out.parentFile?.mkdirs()
                    deleteNoFollow(out)
                    Os.symlink(link, out.path)
                    stats.symlinks++
                    skipData(tin, size)
                }
                '1' -> {
                    val target = safeResolve(link)
                    out.parentFile?.mkdirs()
                    deleteNoFollow(out)
                    if (target != null && target.isFile) {
                        target.inputStream().use { src -> FileOutputStream(out).use { src.copyTo(it) } }
                        chmod(out, fileModeOf(target), isDir = false)
                        stats.hardlinks++
                    } else {
                        stats.skipped++
                    }
                    skipData(tin, size)
                }
                '0', '\u0000', '7' -> {
                    out.parentFile?.mkdirs()
                    deleteNoFollow(out)
                    FileOutputStream(out).use { fos -> copyExactly(tin, fos, size) }
                    skipPadding(tin, size)
                    chmod(out, mode, isDir = false)
                    stats.files++; stats.bytes += size
                    if (stats.files % 200 == 0) onProgress("${stats.files} files…")
                }
                else -> { // '3' char dev, '4' block dev, '6' fifo, unknown → skip
                    stats.skipped++
                    skipData(tin, size)
                }
            }
        }
        // Apply directory modes last so read-only dirs don't block their own children.
        for ((dir, m) in dirModes.asReversed()) chmod(dir, m, isDir = true)
        return stats
    }

    // ---------------------------------------------------------------------------------------

    private fun isSkipped(rawName: String): Boolean {
        var n = rawName.replace('\\', '/')
        while (n.startsWith("./")) n = n.substring(2)
        n = n.trimStart('/')
        return skipPrefixes.any { n.startsWith(it) || n == it.trimEnd('/') }
    }

    private fun safeResolve(rawName: String): File? {
        var n = rawName.replace('\\', '/')
        while (n.startsWith("./")) n = n.substring(2)
        n = n.trimStart('/').trimEnd('/')
        if (n.isEmpty() || n == ".") return null
        if (n.split('/').any { it == ".." }) return null
        val f = File(dest, n)
        // Parent must stay inside dest. Use the *parent's* absolute path, not canonical, so we
        // never follow a guest symlink (absolute guest symlinks point at host paths).
        val abs = f.absolutePath
        return if (abs.startsWith(destCanonical + "/") || abs.startsWith(dest.absolutePath + "/")) f else null
    }

    private fun chmod(f: File, mode: Int, isDir: Boolean) {
        val base = mode and 0x1FF // 0777
        val m = if (isDir) base or 0x1C0 /*0700*/ else base or 0x180 /*0600*/
        try { Os.chmod(f.path, m) } catch (_: Exception) { /* best effort */ }
    }

    private fun fileModeOf(f: File): Int = try { Os.stat(f.path).st_mode and 0xFFF } catch (_: Exception) { 0x1A4 /*0644*/ }

    private fun deleteNoFollow(f: File) {
        try {
            Os.lstat(f.path)
        } catch (_: Exception) {
            return // does not exist
        }
        // lstat succeeded: it's a file or symlink (or dir). delete() on a symlink removes the link itself.
        if (!f.delete()) throw IOException("cannot replace ${f.path}")
    }

    private fun joinUstarName(h: ByteArray): String {
        val name = cString(h, 0, 100)
        // Only POSIX ustar ("ustar\0") has a prefix field at 345. GNU tar ("ustar  \0", which is
        // what Alpine's minirootfs uses) stores atime/ctime there instead, so it must be ignored.
        val magic = cString(h, 257, 6)
        val prefix = if (magic == "ustar") cString(h, 345, 155) else ""
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun parseNumeric(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) { // GNU base-256
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }
        var v = 0L
        for (i in off until off + len) {
            val c = b[i].toInt().toChar()
            if (c == ' ' || c == '\u0000') { if (v != 0L) break else continue }
            if (c !in '0'..'7') break
            v = (v shl 3) + (c - '0')
        }
        return v
    }

    private fun parsePax(data: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < data.size) {
            var sp = i
            while (sp < data.size && data[sp] != ' '.code.toByte()) sp++
            if (sp >= data.size) break
            val len = String(data, i, sp - i, Charsets.US_ASCII).toIntOrNull() ?: break
            if (len <= 0 || i + len > data.size) break
            val rec = String(data, sp + 1, i + len - sp - 1, Charsets.UTF_8).trimEnd('\n')
            val eq = rec.indexOf('=')
            if (eq > 0) out[rec.substring(0, eq)] = rec.substring(eq + 1)
            i += len
        }
        return out
    }

    private fun readData(ins: InputStream, size: Long): ByteArray {
        require(size in 0..(16L shl 20)) { "tar meta entry too large: $size" }
        val buf = ByteArray(size.toInt())
        if (!readFully(ins, buf)) throw IOException("truncated tar meta entry")
        skipPadding(ins, size)
        return buf
    }

    private fun skipData(ins: InputStream, size: Long) {
        if (size <= 0) return
        skipExactly(ins, size + padding(size))
    }

    private fun skipPadding(ins: InputStream, size: Long) = skipExactly(ins, padding(size))

    private fun padding(size: Long): Long = (BLOCK - (size % BLOCK)) % BLOCK

    private fun skipExactly(ins: InputStream, n: Long) {
        var left = n
        val tmp = ByteArray(8192)
        while (left > 0) {
            val r = ins.read(tmp, 0, minOf(tmp.size.toLong(), left).toInt())
            if (r < 0) throw IOException("truncated tar")
            left -= r
        }
    }

    private fun copyExactly(ins: InputStream, out: FileOutputStream, n: Long) {
        var left = n
        val tmp = ByteArray(64 * 1024)
        while (left > 0) {
            val r = ins.read(tmp, 0, minOf(tmp.size.toLong(), left).toInt())
            if (r < 0) throw IOException("truncated tar file data")
            out.write(tmp, 0, r)
            left -= r
        }
    }

    private fun readFully(ins: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val r = ins.read(buf, off, buf.size - off)
            if (r < 0) return if (off == 0) false else throw IOException("truncated tar header")
            off += r
        }
        return true
    }

    companion object {
        private const val BLOCK = 512

        /**
         * Recursive delete that NEVER follows symlinks. Kotlin's File.deleteRecursively() walks with
         * File.isDirectory (which follows links), and a guest rootfs is full of absolute symlinks
         * that resolve against the HOST filesystem, including the app's own data dir.
         */
        fun deleteTreeNoFollow(f: File) {
            val st = try { Os.lstat(f.path) } catch (_: Exception) { return }
            if (OsConstants.S_ISDIR(st.st_mode)) {
                // Guest chmod may have left a dir without owner rwx; restore so children can be removed.
                try { Os.chmod(f.path, (st.st_mode and 0x1FF) or 0x1C0) } catch (_: Exception) {}
                f.list()?.forEach { deleteTreeNoFollow(File(f, it)) }
            }
            f.delete()
        }
    }
}
