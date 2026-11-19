package com.fire17.linuxinapp.proot

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds the PRoot command line + environment, and runs one-shot guest commands.
 *
 * Execution model (why this works at targetSdk 36 without root):
 *  - libproot.so is a real PIE executable disguised as a library. The package installer
 *    extracts it to applicationInfo.nativeLibraryDir (SELinux label apk_data_file, which apps may
 *    execute) because the APK sets useLegacyPackaging = true.
 *  - Guest ELFs inside filesDir/rootfs (app_data_file) are NEVER execve()'d. proot's loader
 *    (libproot_loader.so, named by PROOT_LOADER) is what gets exec'd for every guest program; it
 *    mmaps the guest ELF PROT_EXEC — apps still have `app_data_file:file execute` (mmap), only
 *    `execute_no_trans` (execve) was removed at API 29.
 */
class ProotLauncher(private val context: Context) {

    val nativeLibDir: File get() = File(context.applicationInfo.nativeLibraryDir)
    val prootBinary: File get() = File(nativeLibDir, "libproot.so")
    val rootfsDir: File get() = File(context.filesDir, "rootfs")
    private val tmpDir: File get() = File(context.filesDir, "proot-tmp").apply { mkdirs() }
    private val l2sDir: File get() = File(context.filesDir, "proot-l2s").apply { mkdirs() }

    /** Clean environment for the guest shell (applied via /usr/bin/env -i inside the guest). */
    private val guestEnv = listOf(
        "HOME=/root",
        "USER=root",
        "LOGNAME=root",
        "SHELL=/bin/sh",
        "TERM=xterm-256color",
        "COLORTERM=truecolor",
        "LANG=C.UTF-8",
        "TMPDIR=/tmp",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    )

    /** Host-side environment for the proot process itself. */
    fun hostEnv(): Map<String, String> {
        val env = linkedMapOf(
            // libproot.so DT_NEEDs libtalloc.so + libandroid-shmem.so, which live next to it.
            "LD_LIBRARY_PATH" to nativeLibDir.absolutePath,
            "PROOT_LOADER" to File(nativeLibDir, "libproot_loader.so").absolutePath,
            "PROOT_TMP_DIR" to tmpDir.absolutePath,
            // link2symlink keeps its hard-link bookkeeping here (outside the rootfs).
            "PROOT_L2S_DIR" to l2sDir.absolutePath,
            // Same choice as LinuxOnAndroid (live on Play): ptrace-only syscall interception is
            // slower than seccomp-accelerated mode but avoids seccomp-filter incompatibilities
            // seen on some vendor kernels. Flip to "0"/remove to benchmark the faster path.
            "PROOT_NO_SECCOMP" to "1",
            "HOME" to "/root",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "PATH" to "/system/bin",
            "ANDROID_ROOT" to (System.getenv("ANDROID_ROOT") ?: "/system"),
            "ANDROID_DATA" to (System.getenv("ANDROID_DATA") ?: "/data"),
        )
        val loader32 = File(nativeLibDir, "libproot_loader32.so")
        // termux/proot reads PROOT_LOADER_32 (src/execve/enter.c) for 32-bit guest binaries.
        if (loader32.isFile) env["PROOT_LOADER_32"] = loader32.absolutePath
        return env
    }

    /** Full argv (argv[0] = absolute path of libproot.so) to run [guestArgv] inside the rootfs. */
    fun command(guestArgv: List<String>): List<String> {
        val cmd = mutableListOf(
            prootBinary.absolutePath,
            "--kill-on-exit",
            "--link2symlink",   // Android SELinux forbids link(2): emulate hard links (apk/dpkg need them)
            "-0",               // fake root: uid/gid 0 so `apk add` works
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            // link2symlink's backing files live here; guest symlinks point at this host path,
            // so it must be visible at the same path inside the guest (LinuxOnAndroid does the same).
            "-b", l2sDir.absolutePath,
            // LinuxOnAndroid (the live engine these exact binaries come from) also binds
            // nativeLibraryDir at its own path, so the loader/libs are reachable from inside the
            // guest view too. Harmless if unneeded; mirrors the proven configuration.
            "-b", nativeLibDir.absolutePath,
            "-w", "/root",
        )
        // Start the guest with a scrubbed environment so host vars (LD_LIBRARY_PATH, ANDROID_*)
        // don't leak into musl binaries.
        cmd += listOf("/usr/bin/env", "-i") + guestEnv + guestArgv
        return cmd
    }

    fun loginShellCommand(): List<String> = command(listOf("/bin/sh", "-l"))

    /** Host env as "K=V" array for Termux's TerminalSession (which clearenv()s first). */
    fun hostEnvArray(): Array<String> = hostEnv().map { (k, v) -> "$k=$v" }.toTypedArray()

    data class RunResult(val exitCode: Int?, val output: String, val millis: Long, val command: List<String>)

    /** Blocking one-shot run of a shell snippet inside the guest (stdout+stderr merged). */
    fun runOnce(script: String, timeoutSec: Long = 60): RunResult {
        val cmd = command(listOf("/bin/sh", "-c", script))
        val t0 = System.currentTimeMillis()
        val pb = ProcessBuilder(cmd).redirectErrorStream(true).directory(context.filesDir)
        pb.environment().clear()
        pb.environment().putAll(hostEnv())
        val p = pb.start()
        val out = StringBuilder()
        val reader = Thread {
            try { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            out.appendLine("[timeout after ${timeoutSec}s]")
        }
        reader.join(2000)
        return RunResult(if (finished) p.exitValue() else null, out.toString(), System.currentTimeMillis() - t0, cmd)
    }

    /** Pre-flight diagnostics that explain the usual failure modes before proot even starts. */
    fun preflight(): List<String> {
        val lines = mutableListOf<String>()
        lines += "nativeLibraryDir = ${nativeLibDir.absolutePath}"
        for (n in listOf("libproot.so", "libproot_loader.so", "libtalloc.so", "libandroid-shmem.so")) {
            val f = File(nativeLibDir, n)
            lines += "  $n: " + if (f.isFile) "${f.length()} bytes, canExecute=${f.canExecute()}" else "MISSING (useLegacyPackaging off? wrong ABI?)"
        }
        lines += "rootfs = ${rootfsDir.absolutePath} (bin/busybox exists: ${File(rootfsDir, "bin/busybox").exists()})"
        return lines
    }

    /** One self-test step: the guest shell snippet, its own exit code and its own output. */
    data class StepResult(val name: String, val script: String, val result: RunResult) {
        val ok: Boolean get() = result.exitCode == 0
        /** The single most useful line to quote in a CI log / bug report. */
        val firstLine: String get() = result.output.lineSequence().firstOrNull { it.isNotBlank() } ?: "(no output)"
    }

    /**
     * Runs every SELF_TEST_STEPS entry as its OWN proot invocation, so one failure (e.g. fork
     * ENOSYS) can't hide whether the others work. Steps are ordered from "needs nothing" to
     * "needs fork": `echo` and `read` are sh builtins (no fork); `uname`/`cat`/`id` are external
     * busybox applets, so `sh -c "a; b"` must fork for them.
     */
    fun runSelfTestSteps(timeoutSec: Long = 60): List<StepResult> =
        SELF_TEST_STEPS.map { (name, script) -> StepResult(name, script, runOnce(script, timeoutSec)) }

    companion object {
        /** Kept for callers/docs: the original one-shot snippet (needs fork for every applet). */
        const val SELF_TEST_SCRIPT = "uname -a; echo ---; cat /etc/os-release; echo ---; id"

        /**
         * name -> guest sh snippet. `exec` makes a lone external command run WITHOUT fork, so
         * `exec uname -m` proves exec+loader work even where fork is broken; `fork-probe` then
         * isolates fork itself (busybox sh forks for an external command that isn't the last
         * one). The x86_64 regression this catches: "/bin/sh: can't fork: Function not implemented".
         */
        val SELF_TEST_STEPS: List<Pair<String, String>> = listOf(
            "builtin-echo" to "echo builtin-ok",
            "exec-uname-m" to "exec /bin/uname -m",
            "fork-probe" to "/bin/true; echo fork-ok",
            "uname-a" to "uname -a",
            "os-release" to "cat /etc/os-release",
            "id" to "id",
        )
    }
}
