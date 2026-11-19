package com.fire17.linuxdesk.linux

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds the PRoot command line and environment, and starts guest processes.
 * Adapted from the sibling project ideas-1911-lab/apps/linux-in-app (ProotLauncher.kt).
 *
 * Why this works at targetSdk 36 without root:
 *  - libproot.so is a real PIE executable named like a library. The installer extracts it to
 *    applicationInfo.nativeLibraryDir (SELinux label apk_data_file, which apps may execute) because the
 *    APK sets useLegacyPackaging = true.
 *  - Guest ELFs in filesDir/rootfs (app_data_file) are NEVER execve()'d. proot execs its loader
 *    (libproot_loader.so, given by PROOT_LOADER), and the loader mmaps the guest ELF with PROT_EXEC.
 *    That needs only `execute`, which untrusted apps still have. Only execve (`execute_no_trans`) was
 *    removed at API 29.
 *
 * Differences from linux-in-app:
 *  - /dev/shm is bound to rootfs/tmp/shm. Android has no /dev/shm, and Chromium's shared memory wants it
 *    (we also pass --disable-dev-shm-usage).
 *  - seccomp acceleration is ON by default (PROOT_NO_SECCOMP unset). Chromium is a heavy multi-process
 *    program, and ptrace-only interception would make it far slower. The toggle in LinuxDeskSettings
 *    restores linux-in-app's safer PROOT_NO_SECCOMP=1 mode.
 *  - The X11 socket needs no bind: lorie creates it at <rootfs>/tmp/.X11-unix/X0, which the guest already
 *    sees as /tmp/.X11-unix/X0.
 */
class ProotRuntime(private val context: Context, private val noSeccomp: Boolean = false) {

    val nativeLibDir: File get() = File(context.applicationInfo.nativeLibraryDir)
    val prootBinary: File get() = File(nativeLibDir, "libproot.so")
    val rootfsDir: File get() = File(context.filesDir, "rootfs")
    private val tmpDir: File get() = File(context.filesDir, "proot-tmp").apply { mkdirs() }
    private val l2sDir: File get() = File(context.filesDir, "proot-l2s").apply { mkdirs() }
    private val shmDir: File get() = File(rootfsDir, "tmp/shm").apply { mkdirs() }

    private val baseGuestEnv = listOf(
        "HOME=/root",
        "USER=root",
        "LOGNAME=root",
        "SHELL=/bin/sh",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "TMPDIR=/tmp",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    )

    /** Host-side environment for the proot process itself. */
    fun hostEnv(): Map<String, String> {
        val env = linkedMapOf(
            // libproot.so needs libtalloc.so and libandroid-shmem.so, which sit next to it.
            "LD_LIBRARY_PATH" to nativeLibDir.absolutePath,
            "PROOT_LOADER" to File(nativeLibDir, "libproot_loader.so").absolutePath,
            "PROOT_TMP_DIR" to tmpDir.absolutePath,
            "PROOT_L2S_DIR" to l2sDir.absolutePath,
            "HOME" to "/root",
            "LANG" to "C.UTF-8",
            "PATH" to "/system/bin",
            "ANDROID_ROOT" to (System.getenv("ANDROID_ROOT") ?: "/system"),
            "ANDROID_DATA" to (System.getenv("ANDROID_DATA") ?: "/data"),
        )
        if (noSeccomp) env["PROOT_NO_SECCOMP"] = "1"
        val loader32 = File(nativeLibDir, "libproot_loader32.so")
        if (loader32.isFile) env["PROOT_LOADER_32"] = loader32.absolutePath
        return env
    }

    /** Full argv (argv[0] = absolute path of libproot.so) that runs [guestArgv] inside the rootfs. */
    fun command(guestArgv: List<String>, extraGuestEnv: List<String> = emptyList(), sysvipc: Boolean = false): List<String> {
        val cmd = mutableListOf(prootBinary.absolutePath)
        // termux/proot's System V IPC emulation (Android kernels have no SysV shm). Used only for the
        // browser, which may try MIT-SHM. UNVERIFIED that the pinned build accepts it; if the browser
        // log shows "unknown option", turn it off in SessionService.
        if (sysvipc) cmd += "--sysvipc"
        cmd += listOf(
            "--kill-on-exit",
            "--link2symlink",   // Android SELinux forbids link(2); emulate hard links (apk needs them)
            "-0",               // fake root, so `apk add` works
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "${shmDir.absolutePath}:/dev/shm",
            "-b", l2sDir.absolutePath,
            "-w", "/root",
        )
        cmd += listOf("/usr/bin/env", "-i") + baseGuestEnv + extraGuestEnv + guestArgv
        return cmd
    }

    /** Starts a long-running guest process (stdout and stderr merged). */
    fun start(guestArgv: List<String>, extraGuestEnv: List<String> = emptyList(), sysvipc: Boolean = false): Process {
        val pb = ProcessBuilder(command(guestArgv, extraGuestEnv, sysvipc))
            .redirectErrorStream(true)
            .directory(context.filesDir)
        pb.environment().clear()
        pb.environment().putAll(hostEnv())
        return pb.start()
    }

    data class RunResult(val exitCode: Int?, val tail: String, val millis: Long)

    /** Blocking one-shot shell snippet inside the guest. Streams each output line to [onLine]. */
    fun runOnce(script: String, timeoutSec: Long, onLine: (String) -> Unit = {}): RunResult {
        val t0 = System.currentTimeMillis()
        val p = start(listOf("/bin/sh", "-c", script))
        val tail = ArrayDeque<String>()
        val reader = Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    onLine(line)
                    synchronized(tail) { tail.addLast(line); while (tail.size > 40) tail.removeFirst() }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            onLine("[timeout after ${timeoutSec}s]")
        }
        reader.join(2000)
        val text = synchronized(tail) { tail.joinToString("\n") }
        return RunResult(if (finished) p.exitValue() else null, text, System.currentTimeMillis() - t0)
    }

    /** Diagnostics that explain the usual failures before proot even starts. */
    fun preflight(): List<String> {
        val lines = mutableListOf<String>()
        lines += "nativeLibraryDir = ${nativeLibDir.absolutePath}"
        for (n in listOf("libproot.so", "libproot_loader.so", "libtalloc.so", "libandroid-shmem.so", "libXlorie.so")) {
            val f = File(nativeLibDir, n)
            lines += "  $n: " + if (f.isFile) "${f.length()} bytes, canExecute=${f.canExecute()}" else "MISSING"
        }
        lines += "rootfs = ${rootfsDir.absolutePath} (etc exists: ${File(rootfsDir, "etc").isDirectory})"
        lines += "seccomp acceleration: ${if (noSeccomp) "OFF (PROOT_NO_SECCOMP=1)" else "on"}"
        return lines
    }
}
