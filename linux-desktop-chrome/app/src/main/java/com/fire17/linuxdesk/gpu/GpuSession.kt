package com.fire17.linuxdesk.gpu

import android.os.Build
import com.fire17.linuxdesk.SessionBus
import com.fire17.linuxdesk.bridge.ChromiumFlags
import com.fire17.linuxdesk.linux.ProotRuntime
import java.io.File

/**
 * One session's GPU state (experimental GPU acceleration; the launcher setting is OFF by default).
 *
 * SessionService calls, in order, on its worker thread:
 *   prepare(proot, wanted)   before the browser: support check → setup-gpu.sh once (marker etc/linuxdesk/gpu-v1)
 *   selfCheck(proot)         after the X server is up: gpu-check.sh → one "gpu: renderer=… hardware=…" log line
 *                            (that is what sys.logs shows). No hardware renderer → software for this session.
 * and per browser line: onBrowserLine(line). A GPU-process crash pins software for the rest of the session
 * and asks for one automatic browser restart (restartWanted()).
 *
 * With the setting OFF, prepare() returns at once and every getter is "off": the browser starts exactly as
 * before (--disable-gpu, no extra env).
 */
object GpuSession {

    /** GPU mode was requested AND prepared (Mesa installed, device supported). */
    @Volatile var active = false
        private set
    /** Fell back to software this session (self-check found no GPU, or the GPU process crashed). */
    @Volatile var fallback = false
        private set
    @Volatile private var crashes = 0
    @Volatile private var restartPending = false
    @Volatile var lastRenderer: GpuMode.Renderer? = null
        private set

    private fun log(s: String) = SessionBus.log("gpu: $s")

    fun primaryAbi(): String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    fun support(): GpuMode.Support = GpuMode.probe(primaryAbi())

    /** Resets the state for a new session, then installs the GPU Mesa if needed. Never throws. */
    fun prepare(proot: ProotRuntime, wanted: Boolean): Boolean {
        active = false; fallback = false; crashes = 0; restartPending = false; lastRenderer = null
        if (!wanted) return false
        return try {
            val s = support()
            if (s != GpuMode.Support.SUPPORTED) {
                log("GPU acceleration is ON but not available here ($s); using software rendering")
                return false
            }
            val marker = File(proot.rootfsDir, GpuMode.MARKER)
            val kgslDri = File(proot.rootfsDir, "usr/lib/dri/kgsl_dri.so")
            if (!marker.isFile || !kgslDri.exists()) {
                SessionBus.setState(SessionBus.State.INSTALLING_PACKAGES, "GPU acceleration: installing the Adreno driver (Turnip + Zink, ~15 MB) …")
                val r = proot.runOnce(GpuMode.setupArgv(proot.distro.id).joinToString(" "), timeoutSec = 900) { SessionBus.log(it) }
                if (r.exitCode != 0 || !marker.isFile) {
                    log("setup-gpu.sh failed (exit=${r.exitCode}); using software rendering this session")
                    return false
                }
            }
            log("driver ready (${marker.readText().trim()})")
            active = true
            true
        } catch (t: Throwable) {
            log("prepare failed (${t.javaClass.simpleName}); using software rendering")
            false
        }
    }

    /**
     * Runs gpu-check.sh with the GPU env on DISPLAY=:0 (bounded) and logs renderer=… . If the renderer is not the
     * phone's GPU, Chromium is started in software mode instead (a broken GPU stack must not cost a working browser).
     */
    fun selfCheck(proot: ProotRuntime) {
        if (!active) return
        try {
            val env = (listOf("DISPLAY=:0") + ChromiumFlags.GPU_ENV).joinToString(" ")
            val out = StringBuilder()
            val r = proot.runOnce("$env /bin/sh ${GpuMode.CHECK_SCRIPT}", timeoutSec = 60) { line ->
                out.append(line).append('\n')
                SessionBus.log(line)
            }
            val rr = GpuMode.parseRenderer(out.toString())
            lastRenderer = rr
            SessionBus.log(GpuMode.logLine(rr))
            if (!rr.hardware) {
                fallback = true
                log("self-check found no hardware renderer (exit=${r.exitCode}); Chromium uses software rendering this session")
            }
        } catch (t: Throwable) {
            fallback = true
            log("self-check failed (${t.javaClass.simpleName}); Chromium uses software rendering this session")
        }
    }

    /** Guest env for the browser / desktop processes (empty unless GPU mode is active and has not fallen back). */
    fun guestEnv(): List<String> = ChromiumFlags.guestGpuEnv(active, fallback)

    /** Feed every browser stdout/stderr line. Returns true when this line triggered the software fallback. */
    fun onBrowserLine(line: String): Boolean {
        if (!active || fallback || !ChromiumFlags.isGpuCrashLine(line)) return false
        crashes++
        fallback = true
        restartPending = true
        log("Chromium's GPU process crashed ($crashes); falling back to software rendering (the browser restarts once)")
        return true
    }

    /** True once after a GPU crash: the caller restarts the browser, which then gets --disable-gpu. */
    fun restartWanted(): Boolean {
        if (!restartPending) return false
        restartPending = false
        return true
    }
}
