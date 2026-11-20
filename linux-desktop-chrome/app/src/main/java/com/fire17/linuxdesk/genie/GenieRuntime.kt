package com.fire17.linuxdesk.genie

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.fire17.linuxdesk.BuildConfig
import com.fire17.linuxdesk.SessionBus
import com.fire17.linuxdesk.SessionService
import com.fire17.linuxdesk.linux.ProotRuntime
import com.fire17.linuxdesk.storage.FsVerbs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * The ONE entry point SessionService calls for the genie link (spec 01 §3 "integrator ↔ all").
 *
 *   start(ctx, proot, browserFlags)  — on the session worker thread, after the X step, INSTEAD of the browser
 *                                       step when it returns true (deskd then is Chromium's parent, CDP over a
 *                                       pipe, spec 01 §2). Blocking: it may run setup-genie.sh first.
 *   stop()                           — in stopSession (kills deskd, which takes the browser with it).
 *   restartBrowser()                 — when [ownsBrowser], replaces SessionService's restart (restarts deskd).
 *   stopGenie(ctx)                   — the notification's "Stop genie" (ACTION_STOP_GENIE); turns Genie access
 *                                       and Developer: local link OFF and closes every genie connection.
 *   startLinkOnly(ctx)/stopLinkOnly() — GenieLinkService (after an APK update or a reboot): the iroh link + Router
 *                                       with no session, no proot, no deskd; Linux verbs answer not_ready. A
 *                                       session's start() takes over (and stops GenieLinkService).
 *
 * What runs (each gated by its own OFF-by-default switch, spec 01 §4 rule 2):
 *  - Genie access ON  → setup-genie.sh (once, marker etc/linuxdesk/genie-v2) → deskd on 127.0.0.1:8765 with a
 *    fresh per-session key → DeskdClient relay → IrohLink (ALPN linuxdesk/1).
 *  - Developer: local link ON → WireServer on 127.0.0.1:8766 (same Router).
 * Switch flips and Forget arrive through Binding.addListener and are applied live.
 *
 * Never logged: the deskd key, auth values, tokens, typed text, stdin, file contents.
 * Honest caveat: ProotRuntime passes guest env on the `env -i` argv, so the deskd key is visible in
 * /proc/<pid>/cmdline to processes of the app's own uid (the guest is that uid anyway, spec 01 §4 rule 5);
 * other apps cannot read another uid's cmdline on Android 7+.
 */
object GenieRuntime {
    const val ACTION_STOP_GENIE = "com.fire17.linuxdesk.action.STOP_GENIE"
    const val DESKD_PORT = 8765
    const val WIRE_PORT = 8766
    private const val GENIE_MARKER = "etc/linuxdesk/genie-v2"   // v2: + pulseaudio (sound out / mic in)
    private const val DESKD = "/opt/linuxdesk/deskd/deskd.py"
    private const val BROWSER_CMD = "/opt/linuxdesk/start-browser.sh"
    private const val RESTART_DELAY_MS = 3_000L
    private const val MAX_RESTARTS_PER_MIN = 5
    private const val NOTE_EVERY_MS = 30_000L

    private val lock = Any()
    @Volatile private var app: Context? = null
    @Volatile private var proot: ProotRuntime? = null
    @Volatile private var running = false
    @Volatile private var browserFlags: List<String>? = null
    @Volatile private var sysvipc = true
    @Volatile var ownsBrowser = false
        private set
    /** True while only the link runs (GenieLinkService, no session); session stop() leaves it alone. */
    @Volatile var linkOnly = false
        private set

    private var router: Router? = null
    private var relay: DeskdClient? = null
    private var iroh: IrohLink? = null
    private var wire: WireServer? = null
    @Volatile private var deskd: Process? = null
    @Volatile private var deskdWanted = false
    private val restarts = ArrayDeque<Long>()
    @Volatile private var lastNoteAt = 0L
    private val listener: () -> Unit = { reconcileAsync() }
    /** OTA v1 health check: deskd's relay is connected (it answered the hello). */
    fun deskdReady(): Boolean = relay?.ready() == true
    /** OTA v1 health check: a real sys.ping round-trip to deskd (blocking, ≤ [timeoutMs]); true only on an ok reply. */
    fun deskdPing(timeoutMs: Long = 5_000): Boolean {
        val r = relay ?: return false
        if (!r.ready()) return false
        val q = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)
        r.call(JSONObject().put("cmd", "sys.ping"), timeoutMs) { q.offer(it.header.optBoolean("ok", false) && !it.header.has("error")) }
        return try { q.poll(timeoutMs + 1_000, java.util.concurrent.TimeUnit.MILLISECONDS) == true } catch (_: InterruptedException) { false }
    }

    private fun log(s: String) = SessionBus.log("genie: $s")

    // ------------------------------------------------------------------------------------ entry points

    /**
     * Starts the genie side of this session. [browserFlags] are the ChromiumFlags SessionService would pass to
     * start-browser.sh; when given and Genie access is ON, deskd launches the browser itself and this returns
     * true (SessionService must then NOT launch it). Returns false in every other case (Genie access OFF,
     * setup failed, no flags), and SessionService keeps its proven browser path.
     */
    fun start(ctx: Context, proot: ProotRuntime, browserFlags: List<String>? = null, sysvipc: Boolean = true): Boolean {
        val a = ctx.applicationContext
        val hadLinkOnly: Boolean
        synchronized(lock) {
            hadLinkOnly = linkOnly
            if (running) stopLocked()
            app = a
            this.proot = proot
            this.browserFlags = browserFlags
            this.sysvipc = sysvipc
            running = true
            ownsBrowser = false
            restarts.clear()
            // One relay + one Router for the whole session. Until deskd is up the relay is not ready, so the
            // Router still serves sys.* and fs.* and Linux verbs answer not_ready.
            val r = DeskdClient(DESKD_PORT) { log(it) }
            relay = r
            router = Router(AndroidEnv(a), r, Audit(File(a.filesDir, "genie-audit.jsonl")))
        }
        Binding.removeListener(listener)
        Binding.addListener(listener)
        // The session now hosts the link (its FGS keeps the process alive): the link-only service steps aside.
        if (hadLinkOnly) try { GenieLinkService.stop(a) } catch (_: Throwable) {}

        // The rootfs may have been replaced by RootfsInstaller: re-sync genie.hash before deskd reads it.
        try { Binding.syncHashFile(a) } catch (_: Throwable) {}

        var takeover = false
        if (GenieSettings.genieAccess(a)) {
            if (ensureGeniePackages(a, proot)) {
                takeover = browserFlags != null
                synchronized(lock) { if (running) startDeskdLocked(withBrowser = takeover) }
            } else {
                log("Genie access is ON but setup-genie.sh failed; Linux verbs stay unavailable this session")
            }
        }
        ownsBrowser = takeover && running && deskd != null     // deskd failed to start → the app keeps the browser
        reconcile()
        return ownsBrowser
    }

    fun stop() {
        synchronized(lock) {
            if (linkOnly) return             // GenieLinkService's link, not a session's: only stopLinkOnly() ends it
            Binding.removeListener(listener)
            stopLocked()
        }
    }

    /**
     * Tami 15:05: after an APK update or a reboot the genie could not reach the phone until he opened the app.
     * GenieLinkService calls this: the same relay + Router + IrohLink (and WireServer if that switch is ON) as a
     * session, but no proot and no deskd, so sys.* and fs.* answer and Linux verbs say not_ready. Never touches the
     * mic, the camera, X or Chromium. Returns false when a session (or this) already runs the link.
     */
    fun startLinkOnly(ctx: Context): Boolean {
        val a = ctx.applicationContext
        synchronized(lock) {
            if (running) return false
            app = a
            proot = null
            browserFlags = null
            running = true
            linkOnly = true
            ownsBrowser = false
            restarts.clear()
            val r = DeskdClient(DESKD_PORT) { log(it) }    // never started: no deskd in link-only mode
            relay = r
            router = Router(AndroidEnv(a), r, Audit(File(a.filesDir, "genie-audit.jsonl")))
        }
        Binding.removeListener(listener)
        Binding.addListener(listener)
        log("link only (no session): genie link starting")
        reconcile()
        return true
    }

    /** GenieLinkService ending: stops the link only if it is still the link-only one (a session may own it now). */
    fun stopLinkOnly() {
        synchronized(lock) {
            if (!linkOnly) return
            Binding.removeListener(listener)
            stopLocked()
        }
    }

    /** SessionService's "restart browser" while deskd is Chromium's parent: restart deskd (and so the browser). */
    fun restartBrowser() {
        Thread(restart@{
            synchronized(lock) {
                if (!running || !ownsBrowser) return@restart
                killDeskdLocked()
                restarts.clear()
                startDeskdLocked(withBrowser = true)
            }
        }, "genie-restart").apply { isDaemon = true; start() }
    }

    /** "Stop genie" from the notification: the genie can no longer reach the phone until she turns it on again. */
    fun stopGenie(ctx: Context) {
        val a = ctx.applicationContext
        GenieSettings.setGenieAccess(a, false)
        GenieSettings.setLocalLink(a, false)
        synchronized(lock) { router?.closeAll("stopped by the person") }
        try { GenieNotes.clearConnected(a) } catch (_: Throwable) {}
        reconcileAsync()
    }

    fun stopPendingIntent(ctx: Context): PendingIntent =
        PendingIntent.getService(ctx, 1932, Intent(ctx, SessionService::class.java).setAction(ACTION_STOP_GENIE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    // ------------------------------------------------------------------------------------ reconcile

    private fun reconcileAsync() {
        Thread({ reconcile() }, "genie-reconcile").apply { isDaemon = true; start() }
    }

    /** Makes the running parts match the switches. Cheap and idempotent. */
    private fun reconcile() {
        synchronized(lock) {
            val a = app ?: return
            if (!running) return
            val access = GenieSettings.genieAccess(a)
            val local = GenieSettings.localLink(a)

            if (access && !deskdWanted && !linkOnly) {
                // Turned ON mid-session: SessionService already launched the browser, so deskd starts without
                // it (no second Chromium). browser.* appears after the next session start.
                if (File(proot?.rootfsDir ?: return, GENIE_MARKER).isFile) {
                    log("Genie access ON mid-session: deskd starts without the browser; restart the session to give the genie the browser")
                    startDeskdLocked(withBrowser = false)
                } else {
                    log("Genie access ON mid-session: guest packages missing; restart the session to install them")
                }
            } else if (!access && deskdWanted) {
                // deskd also accepts the paired token directly (adb forward 8765), so OFF must stop it, even when
                // it is the browser's parent (that browser closes too; the Restart button then uses the app's own
                // proven browser path, because ownsBrowser is false from here on).
                deskdWanted = false
                relay?.stop()
                killDeskdLocked()
                if (ownsBrowser) {
                    ownsBrowser = false
                    SessionBus.browserWindowUp = false
                    SessionBus.setState(SessionBus.State.BROWSER_EXITED,
                        "Genie access is off, so the genie's browser was closed. Restart the browser to continue.")
                }
                log("Genie access OFF: deskd stopped")
            }
            val r = router ?: return

            if (access) {
                if (iroh == null) {
                    iroh = IrohLink({ Binding.secretKey(a) }, r, { log(it) }).also { it.start() }
                }
            } else if (iroh != null) {
                iroh?.stop(); iroh = null
                r.closeAll("Genie access OFF", via = "iroh")
            }

            if (local) {
                if (wire == null) wire = WireServer(r, WIRE_PORT) { log(it) }.also { it.start() }
            } else if (wire != null) {
                wire?.stop(); wire = null
                r.closeAll("local link OFF", via = "tcp")
            }
        }
    }

    // ------------------------------------------------------------------------------------ deskd

    private fun ensureGeniePackages(a: Context, p: ProotRuntime): Boolean {
        if (File(p.rootfsDir, GENIE_MARKER).isFile) return true
        // Per distro: setup-genie.sh (Alpine, apk) or setup-genie-arch.sh (Omarchy, pacman); same marker.
        val script = p.distro.genieScript
        log("installing guest packages for the genie (${script.substringAfterLast('/')})")
        val r = p.runOnce(script, timeoutSec = 1800) { SessionBus.log(it) }
        val ok = r.exitCode == 0 && File(p.rootfsDir, GENIE_MARKER).isFile
        if (!ok) log("${script.substringAfterLast('/')} failed (exit=${r.exitCode})")
        return ok
    }

    private fun newKey(): String {
        val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /**
     * Starts deskd under the lock, with a fresh 32-byte key each start (the relay is re-keyed in place, so live
     * genie connections survive a deskd restart). The supervisor restarts it after 3 s, max 5 per minute.
     */
    private fun startDeskdLocked(withBrowser: Boolean) {
        val p = proot ?: return
        val r = relay ?: return
        val key = newKey()
        val env = mutableListOf("DISPLAY=:0", "LINUXDESK_KEY=$key", "LINUXDESK_PORT=$DESKD_PORT")
        // PULSE_SERVER/PULSE_SINK when the guest PulseAudio runs (empty otherwise); cdp.py's _env passes them on to
        // the Chromium deskd launches (it strips only LINUXDESK_*).
        env += com.fire17.linuxdesk.audio.PulseHost.guestEnv()
        // GPU v2 (experimental, OFF by default): Mesa zink/Turnip env; empty when off or after a software fallback.
        env += com.fire17.linuxdesk.gpu.GpuSession.guestEnv()
        if (BuildConfig.DEBUG) env += "LINUXDESK_SELFTEST=1"      // device self-test batch (spec 02), debug builds only
        val flags = browserFlags
        if (withBrowser && flags != null) {
            // start-browser.sh (Alpine), start-omarchy.sh (Omarchy: i3 + Chrome) or start-sway.sh (Omarchy, Sway
            // desktop); same argv contract.
            env += "LINUXDESK_BROWSER_CMD=${p.startScript}"
            env += "LINUXDESK_BROWSER_FLAGS=" + JSONArray(flags).toString()
        }
        killStaleDeskd()
        val proc = try {
            p.start(listOf("/usr/bin/python3", DESKD), env, sysvipc = withBrowser && sysvipc)
        } catch (t: Throwable) {
            log("deskd failed to start: ${t.javaClass.simpleName}")
            deskdWanted = false
            return
        }
        deskdWanted = true
        deskd = proc
        log("deskd started${if (withBrowser) " (browser parent, CDP pipe)" else ""}")
        if (withBrowser) {
            SessionBus.browserWindowUp = false
            SessionBus.setState(SessionBus.State.STARTING_BROWSER, "Starting the browser (genie access on)")
        }
        r.start(key)
        Thread({ supervise(proc, withBrowser) }, "genie-deskd-log").apply { isDaemon = true; start() }
    }

    private fun supervise(proc: Process, withBrowser: Boolean) {
        try {
            val st = com.fire17.linuxdesk.SessionStatus()
            proc.inputStream.bufferedReader().forEachLine { line ->
                SessionBus.log(line)
                // The start script deskd runs reports its components and errors the same way (SessionStatus).
                when (val e = st.onLine(line)) {
                    is com.fire17.linuxdesk.SessionStatus.Event.Stage -> if (withBrowser && !SessionBus.browserWindowUp)
                        SessionBus.setState(SessionBus.State.STARTING_BROWSER, "Starting ${e.component} …")
                    is com.fire17.linuxdesk.SessionStatus.Event.Error -> if (running) com.fire17.linuxdesk.SessionErrors.report(e.reason)
                    else -> {}
                }
                // GPU v2: Chromium's GPU process crashed → software flags for this session, restart the browser once.
                if (withBrowser && com.fire17.linuxdesk.gpu.GpuSession.onBrowserLine(line) &&
                    com.fire17.linuxdesk.gpu.GpuSession.restartWanted()) {
                    browserFlags = browserFlags?.let { com.fire17.linuxdesk.bridge.ChromiumFlags.withSoftwareFallback(it) }
                    restartBrowser()
                }
                if (withBrowser && line.startsWith("LINUXDESK_WINDOW_UP") && !SessionBus.browserWindowUp) {
                    SessionBus.browserWindowUp = true
                    SessionBus.setState(SessionBus.State.RUNNING, st.runningName()?.let { "$it running" } ?: "Running")
                }
            }
        } catch (_: Exception) {
        }
        val code = try { proc.waitFor() } catch (_: Exception) { -1 }
        synchronized(lock) {
            if (deskd !== proc) return          // replaced or stopped on purpose
            deskd = null
            if (!running || !deskdWanted) return
            val now = System.currentTimeMillis()
            while (restarts.isNotEmpty() && now - restarts.first() > 60_000) restarts.removeFirst()
            if (restarts.size >= MAX_RESTARTS_PER_MIN) {
                log("deskd exited (code $code) 5 times in a minute; giving up until the next session")
                deskdWanted = false
                if (withBrowser) SessionBus.setState(SessionBus.State.BROWSER_EXITED, "deskd stopped (code $code)")
                return
            }
            restarts.addLast(now)
            log("deskd exited (code $code); restarting in 3 s")
        }
        try { Thread.sleep(RESTART_DELAY_MS) } catch (_: InterruptedException) { return }
        synchronized(lock) {
            if (running && deskdWanted && deskd == null) startDeskdLocked(withBrowser)
        }
    }

    /**
     * 0.2.29: a deskd from an earlier session (often the other distro, whose pidfile we cannot see) still holding
     * 8765 made the new deskd loop on "port busy" until Force stop. Called with no deskd of ours running, so every
     * deskd process visible here (own uid only, hidepid) is stale: TERM, then KILL what is left after 2 s.
     */
    private fun killStaleDeskd() {
        val stale = try { StaleDeskd.find() } catch (_: Throwable) { emptyList() }
        if (stale.isEmpty()) return
        log("clearing ${stale.size} leftover deskd process(es) from an earlier session: $stale")
        stale.forEach { try { android.os.Process.sendSignal(it, 15) } catch (_: Throwable) {} }
        val until = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < until && stale.any { File("/proc/$it").exists() }) Thread.sleep(100)
        stale.filter { File("/proc/$it").exists() }.forEach { try { android.os.Process.killProcess(it) } catch (_: Throwable) {} }
    }

    private fun killDeskdLocked() {
        val p = deskd ?: return
        deskd = null
        // proot runs with --kill-on-exit: killing it also kills deskd's Chromium tree.
        try { p.destroy() } catch (_: Exception) {}
        try { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() } catch (_: Exception) {}
    }

    private fun stopLocked() {
        running = false
        linkOnly = false
        deskdWanted = false
        ownsBrowser = false
        iroh?.stop(); iroh = null
        wire?.stop(); wire = null
        router?.shutdown(); router = null
        relay?.close(); relay = null
        killDeskdLocked()
        app?.let { try { GenieNotes.clearConnected(it) } catch (_: Throwable) {} }
    }

    // ------------------------------------------------------------------------------------ Android env

    private class AndroidEnv(private val ctx: Context) : RouterEnv {
        override fun authenticate(given: String, genie: String): HelloResult =
            when (val r = Binding.authenticate(ctx, given, genie)) {
                is Binding.Auth.Ok -> HelloResult.Ok(r.genie, r.newToken)
                is Binding.Auth.Refused -> HelloResult.Refused(r.error)
            }
        override fun isBound() = Binding.isBound(ctx)
        override fun generation() = Binding.generation(ctx)
        override fun windowOpen() = GenieWindow.isOpen(ctx)
        override fun lockedRefusal() = GenieWindow.lockedRefusal(ctx)
        override fun xorBlocks() = GenieSettings.xorBlocksNow(ctx)
        override fun fs(cmd: JSONObject): Reply = FsVerbs.handle(ctx, cmd)
        override fun fsInfo(): JSONObject = com.fire17.linuxdesk.storage.StoragePrefs.capsInfo(ctx)
        override fun hwInfo(): JSONObject = com.fire17.linuxdesk.hw.HwProbe.cachedOrWarm(ctx)   // never blocks the Router
        // Cameras v4 sys.cameras: read-only inventory + generic default (no openCamera, no CAMERA permission).
        override fun camerasInfo(): JSONObject = com.fire17.linuxdesk.camera.CameraDiag
            .json(com.fire17.linuxdesk.camera.CameraDiag.read(ctx)).put("ok", true)
        // ld-perf sys.perf: read-only /proc sample (1 s) + MemAvailable + AudioOut counters.
        override fun perfInfo(): JSONObject {
            com.fire17.linuxdesk.hw.PerfStat.soundLine = { com.fire17.linuxdesk.audio.AudioOut.statsLine() }
            return com.fire17.linuxdesk.hw.PerfStat.json(1_000).put("ok", true)
        }
        // Read straight from prefs (pair owns GenieSettings and adds the getter + switch); default OFF.
        override fun wideWindowGate(): Boolean =
            ctx.getSharedPreferences("ld.genie", Context.MODE_PRIVATE).getBoolean("wide_window_gate", false)
        override fun version(): String = BuildConfig.VERSION_NAME
        override fun otaStatus(tail: Int): JSONObject = otaStore().statusJson(tail, com.fire17.linuxdesk.ota.OtaSettings(ctx).autoApply)
        override fun otaCheck(): JSONObject = otaStore().checkJson(com.fire17.linuxdesk.ota.OtaSettings(ctx).baseUrl + "manifest.json")
        private fun otaStore() = com.fire17.linuxdesk.ota.BundleStore(ctx.filesDir, BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME)

        override fun sessionInfo(): JSONObject {
            val root = com.fire17.linuxdesk.linux.Distro.currentRootfs(ctx)   // "rootfs" for Alpine, as before
            return JSONObject()
                .put("state", SessionBus.snapshot.state.name)
                .put("rootfs", File(root, "etc").isDirectory)
                .put("packages", File(root, "etc/linuxdesk/packages-v1").isFile)
                .put("genie_packages", File(root, GENIE_MARKER).isFile)
                .put("x11", SessionBus.desktopConnected)
                .put("browser", SessionBus.browserWindowUp)
                .put("browser_parent", if (ownsBrowser) "deskd" else "app")
        }

        override fun xInfo(): JSONObject {
            val dm = ctx.resources.displayMetrics
            return JSONObject()
                .put("w", if (SessionBus.viewWidth > 0) SessionBus.viewWidth else dm.widthPixels)
                .put("h", if (SessionBus.viewHeight > 0) SessionBus.viewHeight else dm.heightPixels)
                .put("dpi", dm.densityDpi)
                .put("density", dm.density.toDouble())
        }

        override fun onAppClosed() { try { GenieNotes.appClosed(ctx) } catch (_: Throwable) {} }

        override fun onConnected(genie: String, paired: Boolean) {
            lastNoteAt = System.currentTimeMillis()
            // Cameras v4: the first genie hello of this process logs the camera inventory (read-only).
            if (com.fire17.linuxdesk.camera.CameraLog.firstHelloOnce())
                com.fire17.linuxdesk.camera.CameraLog.logInventory(ctx, "first-genie-hello")
            try { GenieNotes.connected(ctx, genie.ifBlank { "Your genie" }, 0L, stopPendingIntent(ctx)) } catch (_: Throwable) {}
        }

        override fun onCommand(genie: String, at: Long) {
            if (at - lastNoteAt < NOTE_EVERY_MS) return
            lastNoteAt = at
            try { GenieNotes.connected(ctx, genie.ifBlank { "Your genie" }, at, stopPendingIntent(ctx)) } catch (_: Throwable) {}
        }

        override fun onDisconnected(liveSessions: Int) {
            if (liveSessions == 0) try { GenieNotes.clearConnected(ctx) } catch (_: Throwable) {}
        }

        override fun log(line: String) = SessionBus.log("genie: $line")
    }
}
