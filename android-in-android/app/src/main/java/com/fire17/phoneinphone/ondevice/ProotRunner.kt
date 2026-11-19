package com.fire17.phoneinphone.ondevice

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Runs PRoot from the APK's nativeLibraryDir (the req2 winner: "PRoot in jniLibs").
 *
 * Why nativeLibraryDir: since Android 10 (targetSdk 29+) untrusted apps may not execve files
 * from their writable data dir (app_data_file lacks execute_no_trans), but files the installer
 * extracted into nativeLibraryDir (apk_data_file) ARE executable. proot itself then never
 * execve's guest binaries: its loader mmaps them PROT_EXEC, which sepolicy still grants.
 *
 * Binaries come from Termux's proot 5.1.107.102 package (GPL-2.0, fetched + sha256-verified by
 * scripts/fetch-proot.sh, renamed to lib*.so and patchelf'd so their NEEDED entries resolve
 * inside nativeLibraryDir). UNVERIFIED until run on a phone: that the Termux build behaves
 * outside the com.termux prefix with PROOT_TMP_DIR/PROOT_LOADER overrides.
 */
class ProotRunner(private val context: Context) {

    val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val proot = File(nativeDir, "libproot.so")
    val loader = File(nativeDir, "libproot_loader.so")
    val tmpDir = File(context.cacheDir, "proot-tmp").apply { mkdirs() }

    /**
     * Termux's proot has DT_NEEDED "libtalloc.so.2", but jniLibs only packages names matching
     * lib*.so. We ship it as libtalloc.so and add a symlink libtalloc.so.2 -> nativeLibraryDir in
     * app storage, placed first on LD_LIBRARY_PATH. (The symlink target stays apk_data_file.)
     */
    fun libShimDir(): File {
        val dir = File(context.filesDir, "proot-libshim").apply { mkdirs() }
        val link = File(dir, "libtalloc.so.2")
        val target = File(nativeDir, "libtalloc.so").absolutePath
        val ok = try { android.system.Os.readlink(link.absolutePath) == target } catch (ignored: Throwable) { false }
        if (!ok) {
            link.delete()
            try { android.system.Os.symlink(target, link.absolutePath) } catch (ignored: Throwable) {}
        }
        return dir
    }

    data class Result(val exitCode: Int, val output: String, val timedOut: Boolean, val millis: Long)

    fun requiredLibs(): List<File> = listOf(
        proot, loader,
        File(nativeDir, "libtalloc.so"),
        File(nativeDir, "libandroid-shmem.so"),
    )

    /**
     * @param rootfs guest root
     * @param binds  extra "-b host[:guest]" specs
     * @param argv   command to run INSIDE the guest
     */
    fun run(
        rootfs: File,
        argv: List<String>,
        binds: List<String> = DEFAULT_BINDS,
        extraEnv: Map<String, String> = emptyMap(),
        fakeRoot: Boolean = true,
        timeoutSec: Long = 30,
        cwd: String = "/",
    ): Result {
        val cmd = mutableListOf(proot.absolutePath, "--kill-on-exit", "--link2symlink")
        if (fakeRoot) cmd += "-0"
        cmd += listOf("-r", rootfs.absolutePath)
        for (b in binds) { cmd += "-b"; cmd += b }
        cmd += listOf("-w", cwd)
        cmd += argv

        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val env = pb.environment()
        // Keep only what bionic (for proot itself) needs; everything here is also inherited by the guest.
        val keep = listOf("ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT")
            .associateWith { System.getenv(it) }.filterValues { it != null }
        env.clear()
        keep.forEach { (k, v) -> env[k] = v!! }
        env["PROOT_LOADER"] = loader.absolutePath
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        env["LD_LIBRARY_PATH"] = libShimDir().absolutePath + ":" + nativeDir.absolutePath
        env["HOME"] = "/root"
        env["TERM"] = "xterm-256color"
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin"
        env.putAll(extraEnv)

        val t0 = System.currentTimeMillis()
        val p = pb.start()
        val out = StringBuilder()
        val reader = Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(out) { if (out.length < 64_000) out.append(line).append('\n') }
                }
            } catch (ignored: Throwable) {}
        }.apply { isDaemon = true; start() }
        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        reader.join(1000)
        return Result(
            exitCode = if (finished) p.exitValue() else -1,
            output = synchronized(out) { out.toString() },
            timedOut = !finished,
            millis = System.currentTimeMillis() - t0,
        )
    }

    companion object {
        val DEFAULT_BINDS = listOf("/dev", "/proc", "/sys")

        /**
         * Reads PT_LOAD p_align of a 64-bit ELF (16 KB = 0x4000 needed for 16 KB-page devices,
         * mandatory for Play since 2025-11-01 at targetSdk 35+). Returns null if not ELF64.
         */
        fun loadAlignments(f: File): List<Long>? = try {
            RandomAccessFile(f, "r").use { raf -> readAligns(raf) }
        } catch (ignored: Throwable) { null }

        private fun readAligns(raf: RandomAccessFile): List<Long>? {
                val ident = ByteArray(16); raf.readFully(ident)
                if (ident[0] != 0x7F.toByte() || ident[1] != 'E'.code.toByte() || ident[4].toInt() != 2) return null
                fun u16(off: Long): Int { raf.seek(off); val b = ByteArray(2); raf.readFully(b); return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) }
                fun u32(off: Long): Long { raf.seek(off); val b = ByteArray(4); raf.readFully(b); var v = 0L; for (i in 3 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF); return v }
                fun u64(off: Long): Long { raf.seek(off); val b = ByteArray(8); raf.readFully(b); var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF); return v }
                val phoff = u64(0x20)
                val phentsize = u16(0x36)
                val phnum = u16(0x38)
                return (0 until phnum).mapNotNull { i ->
                    val o = phoff + i.toLong() * phentsize
                    if (u32(o) == 1L) u64(o + 48) else null
                }
        }
    }
}
