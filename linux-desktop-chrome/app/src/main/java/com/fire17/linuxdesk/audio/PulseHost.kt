package com.fire17.linuxdesk.audio

import com.fire17.linuxdesk.SessionBus
import com.fire17.linuxdesk.linux.ProotRuntime
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The ONE PulseAudio inside the guest (spec 02 #1 v2). Every audio feature (sound out: [AudioOut]; mic in: the
 * ld-mic-in lane) registers what it needs here, and SessionService starts PulseAudio once per session.
 *
 * Lifecycle (SessionService, session worker thread):
 *   1. each feature that is ON calls [require] (owner id, `--load` module lines, extra guest env)
 *   2. [start] (blocking: one-time `apk add pulseaudio`, then up to ~8 s for the socket). Starts only when at least
 *      one owner registered (Sound ON by default, or the mic). Modules can also be added live with [pactl] /
 *      [PULSE_SERVER]. Returns false and logs when PulseAudio fails: the session continues SILENTLY.
 *   3. the browser env (SessionService.launchBrowser and deskd's env in GenieRuntime) gets [guestEnv]
 *   4. [stop] in stopSession / onDestroy; it also clears every registration (next session registers again).
 *
 * Fixed parts, always loaded when PulseAudio runs:
 *   - module-native-protocol-unix on [SOCKET_GUEST] (guest path; host path = rootfs/[SOCKET_GUEST]), anonymous
 *     auth: only processes that can reach the app's private files dir (= the app's own uid) can connect.
 *   - module-null-sink sink_name=[SINK] — Chromium plays into it (PULSE_SINK=ld in [guestEnv]).
 *   - module-null-sink [NO_MIC_SINK] + module-remap-source [NO_MIC_SOURCE] — a silent source, made the default
 *     at start (and again by [MicGuest] when the phone mic goes OFF). Without it the only source is ld.monitor,
 *     so a page recording "the microphone" would hear the speakers: a feedback loop. Default sink stays [SINK].
 *
 * Security caveat (same as spec 02 #1 / #5): modules that listen on TCP (module-simple-protocol-tcp) have NO
 * auth. They must bind 127.0.0.1 only, on a port from [freeLoopbackPort] (random 20000–60000, fresh each
 * session), and exist only while the session runs. Any app on the phone could connect to that port while it is
 * open; the port is not advertised anywhere.
 *
 * Under proot: no --system, no shm (--disable-shm), no daemonize (proot --kill-on-exit would kill a forked
 * daemon when its parent exits, so PulseAudio runs in the foreground as a proot child we own), no pid file
 * (a stale one from a killed app process must never block start), no realtime/dbus.
 */
object PulseHost {
    const val SINK = "ld"
    /** Silent placeholder: the default source while the phone mic is off, so nothing ever records [SINK].monitor. */
    const val NO_MIC_SINK = "nomic"
    const val NO_MIC_SOURCE = "no-mic"
    const val SOCKET_DIR_GUEST = "/tmp/linuxdesk-pulse"
    const val SOCKET_GUEST = "$SOCKET_DIR_GUEST/native"
    const val PULSE_SERVER = "unix:$SOCKET_GUEST"
    private const val MARKER = "etc/linuxdesk/audio-v1"
    private const val PKGS = "pulseaudio pulseaudio-utils"
    private const val MAX_RESTARTS_PER_MIN = 3

    private class Need(val modules: List<String>, val env: List<String>)

    private val needs = ConcurrentHashMap<String, Need>()
    private val lock = Any()
    @Volatile private var proc: Process? = null
    @Volatile private var proot: ProotRuntime? = null
    @Volatile private var wanted = false
    private val restarts = ArrayDeque<Long>()

    /** True while PulseAudio is running and its socket exists. */
    @Volatile var running = false
        private set

    private fun log(s: String) = SessionBus.log("pulse: $s")

    /** Register (or replace) what [owner] needs. Call BEFORE [start]; later calls apply at the next start. */
    fun require(owner: String, modules: List<String>, env: List<String> = emptyList()) {
        needs[owner] = Need(modules, env)
    }

    fun release(owner: String) { needs.remove(owner) }

    /**
     * Env for guest processes that should use PulseAudio (Chromium, deskd). Empty when PulseAudio is not running,
     * so Chromium behaves exactly as before (no sound server, muted).
     */
    fun guestEnv(): List<String> {
        if (!running) return emptyList()
        val env = mutableListOf("PULSE_SERVER=$PULSE_SERVER", "PULSE_SINK=$SINK")
        needs.values.forEach { env += it.env }
        return env
    }

    /** Random free 127.0.0.1 port in 20000..60000 (guest and app share the loopback interface). */
    fun freeLoopbackPort(): Int {
        val rnd = SecureRandom()
        repeat(30) {
            val port = 20000 + rnd.nextInt(40001)
            try {
                ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { return port }
            } catch (_: Exception) {
            }
        }
        return 20000 + rnd.nextInt(40001)
    }

    /** Blocking. Returns true when PulseAudio runs. Never throws; failure = log + silent session. */
    fun start(proot: ProotRuntime): Boolean {
        synchronized(lock) { stopLocked(clearNeeds = false) }
        if (needs.isEmpty()) { log("not started (Sound and microphone are OFF)"); return false }
        return try {
            if (!ensureInstalled(proot)) return false
            synchronized(lock) {
                this.proot = proot
                wanted = true
                restarts.clear()
                launchLocked()
            }
            val sock = File(proot.rootfsDir, SOCKET_GUEST.removePrefix("/"))
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 8_000 && !sock.exists() && proc?.isAlive == true) Thread.sleep(100)
            running = sock.exists() && proc?.isAlive == true
            if (running) {
                log("up (${System.currentTimeMillis() - t0} ms), owners=${needs.keys.sorted()}")
                pactl("set-default-source", NO_MIC_SOURCE)
            } else log("did not come up within 8 s; continuing without sound")
            running
        } catch (t: Throwable) {
            log("start failed (${t.javaClass.simpleName}: ${t.message}); continuing without sound")
            false
        }
    }

    fun stop() = synchronized(lock) { stopLocked(clearNeeds = true) }

    /** Runs `pactl <args>` in the guest against this PulseAudio (e.g. live load-module). Returns the exit code. */
    fun pactl(vararg args: String, timeoutSec: Long = 10): Int? {
        val p = proot ?: return null
        if (!running) return null
        val quoted = args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        return p.runOnce("PULSE_SERVER=$PULSE_SERVER pactl $quoted", timeoutSec) { log(it) }.exitCode
    }

    // ------------------------------------------------------------------------------------------

    private fun ensureInstalled(p: ProotRuntime): Boolean {
        if (File(p.rootfsDir, MARKER).isFile && File(p.rootfsDir, "usr/bin/pulseaudio").exists()) return true
        log("installing guest packages: $PKGS (one time)")
        val script = "apk update >/dev/null 2>&1 || echo '[audio-setup] apk update failed; trying the cached index'; " +
            "apk add --no-progress $PKGS || echo '[audio-setup] apk reported errors; verifying'; " +
            "command -v pulseaudio >/dev/null 2>&1 || { echo '[audio-setup] pulseaudio missing'; exit 3; }; " +
            "mkdir -p /etc/linuxdesk && echo '$PKGS' > /$MARKER"
        val r = p.runOnce(script, timeoutSec = 900) { SessionBus.log(it) }
        val ok = r.exitCode == 0 && File(p.rootfsDir, MARKER).isFile
        if (!ok) log("install failed (exit=${r.exitCode}); continuing without sound")
        return ok
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** The modules every PulseAudio start loads, before any owner's. */
    internal fun fixedLoads(): List<String> = listOf(
        "module-native-protocol-unix socket=$SOCKET_GUEST auth-anonymous=1",
        "module-null-sink sink_name=$SINK rate=48000 channels=2 sink_properties=device.description=LinuxDesk",
        "module-null-sink sink_name=$NO_MIC_SINK rate=48000 channels=1 sink_properties=device.description=No-Microphone-Sink",
        "module-remap-source master=$NO_MIC_SINK.monitor source_name=$NO_MIC_SOURCE source_properties=device.description=No-Microphone",
    )

    private fun launchLocked() {
        val p = proot ?: return
        val loads = fixedLoads().toMutableList()
        needs.values.forEach { loads += it.modules }
        val script = buildString {
            append("mkdir -p /tmp/runtime-root $SOCKET_DIR_GUEST && chmod 700 /tmp/runtime-root $SOCKET_DIR_GUEST; ")
            append("rm -f $SOCKET_GUEST; ")
            append("exec pulseaudio --daemonize=no --use-pid-file=no --system=no --exit-idle-time=-1 ")
            append("--disable-shm=yes --realtime=no --high-priority=no -n --log-target=stderr --log-level=notice")
            loads.forEach { append(" --load=").append(q(it)) }
        }
        val pr = try {
            p.start(listOf("/bin/sh", "-c", script), listOf("XDG_RUNTIME_DIR=/tmp/runtime-root", "HOME=/root"))
        } catch (t: Throwable) {
            log("launch failed (${t.javaClass.simpleName})"); wanted = false; return
        }
        proc = pr
        Thread({ supervise(pr) }, "pulse-log").apply { isDaemon = true; start() }
    }

    private fun supervise(pr: Process) {
        try { pr.inputStream.bufferedReader().forEachLine { log(it) } } catch (_: Exception) {}
        val code = try { pr.waitFor() } catch (_: Exception) { -1 }
        synchronized(lock) {
            if (proc !== pr) return
            proc = null
            running = false
            if (!wanted) return
            val now = System.currentTimeMillis()
            while (restarts.isNotEmpty() && now - restarts.first() > 60_000) restarts.removeFirst()
            if (restarts.size >= MAX_RESTARTS_PER_MIN) {
                log("exited (code $code) $MAX_RESTARTS_PER_MIN times in a minute; sound off until the next session")
                wanted = false
                return
            }
            restarts.addLast(now)
            log("exited (code $code); restarting in 2 s")
        }
        try { Thread.sleep(2_000) } catch (_: InterruptedException) { return }
        val relaunched = synchronized(lock) {
            if (wanted && proc == null) {
                launchLocked()
                // Same socket path and ports, so the browser and AudioOut reconnect on their own.
                running = proc?.isAlive == true
                running
            } else false
        }
        // Live-loaded mic modules died with the old daemon: the silent source is the right default again.
        if (relaunched) {
            val sock = File(proot?.rootfsDir ?: return, SOCKET_GUEST.removePrefix("/"))
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 8_000 && !sock.exists()) Thread.sleep(100)
            pactl("set-default-source", NO_MIC_SOURCE)
        }
    }

    private fun stopLocked(clearNeeds: Boolean) {
        wanted = false
        running = false
        val pr = proc
        proc = null
        if (pr != null) {
            try { pr.destroy() } catch (_: Exception) {}
            try { if (!pr.waitFor(3, TimeUnit.SECONDS)) pr.destroyForcibly() } catch (_: Exception) {}
        }
        if (clearNeeds) needs.clear()
    }
}
