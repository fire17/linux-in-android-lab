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
 *
 * What runs (each gated by its own OFF-by-default switch, spec 01 §4 rule 2):
 *  - Genie access ON  → setup-genie.sh (once, marker etc/linuxdesk/genie-v1) → deskd on 127.0.0.1:8765 with a
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
    private const val GENIE_MARKER = "etc/linuxdesk/genie-v1"
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

    private var router: Router? = null
    private var relay: DeskdClient? = null
    private var iroh: IrohLink? = null
    private var wire: WireServer? = null
    @Volatile private var deskd: Process? = null
    @Volatile private var deskdWanted = false
    private val restarts = ArrayDeque<Long>()
    @Volatile private var lastNoteAt = 0L
    private val listener: () -> Unit = { reconcileAsync() }

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
        synchronized(lock) {
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
        Binding.removeListener(listener)
        synchronized(lock) { stopLocked() }
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

            if (access && !deskdWanted) {
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
        log("installing guest packages for the genie (setup-genie.sh)")
        val r = p.runOnce("/opt/linuxdesk/setup-genie.sh", timeoutSec = 1800) { SessionBus.log(it) }
        val ok = r.exitCode == 0 && File(p.rootfsDir, GENIE_MARKER).isFile
        if (!ok) log("setup-genie.sh failed (exit=${r.exitCode})")
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
        if (BuildConfig.DEBUG) env += "LINUXDESK_SELFTEST=1"      // device self-test batch (spec 02), debug builds only
        val flags = browserFlags
        if (withBrowser && flags != null) {
            env += "LINUXDESK_BROWSER_CMD=$BROWSER_CMD"
            env += "LINUXDESK_BROWSER_FLAGS=" + JSONArray(flags).toString()
        }
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
            proc.inputStream.bufferedReader().forEachLine { line ->
                SessionBus.log(line)
                if (withBrowser && line.startsWith("LINUXDESK_WINDOW_UP") && !SessionBus.browserWindowUp) {
                    SessionBus.browserWindowUp = true
                    SessionBus.setState(SessionBus.State.RUNNING, "Running")
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

    private fun killDeskdLocked() {
        val p = deskd ?: return
        deskd = null
        // proot runs with --kill-on-exit: killing it also kills deskd's Chromium tree.
        try { p.destroy() } catch (_: Exception) {}
        try { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() } catch (_: Exception) {}
    }

    private fun stopLocked() {
        running = false
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
        // Read straight from prefs (pair owns GenieSettings and adds the getter + switch); default OFF.
        override fun wideWindowGate(): Boolean =
            ctx.getSharedPreferences("ld.genie", Context.MODE_PRIVATE).getBoolean("wide_window_gate", false)
        override fun version(): String = BuildConfig.VERSION_NAME

        override fun sessionInfo(): JSONObject {
            val root = File(ctx.filesDir, "rootfs")
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
