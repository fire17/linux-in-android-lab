package com.fire17.linuxdesk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.fire17.linuxdesk.SessionBus.State
import com.fire17.linuxdesk.bridge.ChromiumFlags
import com.fire17.linuxdesk.bridge.FocusBridge
import com.fire17.linuxdesk.genie.GenieRuntime
import com.fire17.linuxdesk.genie.GenieSettings
import com.fire17.linuxdesk.linux.ProotRuntime
import com.fire17.linuxdesk.linux.RootfsInstaller
import com.fire17.linuxdesk.x11.X11ServerService
import java.io.File

/**
 * Foreground service that owns one LinuxDesk session:
 *
 *   1. rootfs     extract the bundled Alpine minirootfs (first run only)
 *   2. packages   apk add xkeyboard-config, fonts (incl. Hebrew), xdotool, chromium (first run only, needs network)
 *   3. X server   bind X11ServerService, which starts lorie in the ":x11" process; wait for the socket
 *   4. display    LauncherActivity opens DesktopActivity, whose LorieView connects to the server
 *   5. browser    proot → /opt/linuxdesk/start-browser.sh <flags from DisplayMetrics> → chromium on DISPLAY=:0
 *                 Genie access ON (default OFF): GenieRuntime runs proot → python3 deskd.py instead, and deskd
 *                 launches the same start-browser.sh + flags as Chromium's parent (CDP over a pipe).
 *
 * The foreground service, plus a partial wake lock, keeps the X server and Chromium alive while the app is
 * in the background. Android 12+'s phantom-process killer is a separate, system-wide limit; see README.
 */
class SessionService : Service() {

    companion object {
        const val ACTION_START = "com.fire17.linuxdesk.action.START"
        const val ACTION_RESTART_BROWSER = "com.fire17.linuxdesk.action.RESTART_BROWSER"
        const val ACTION_STOP = "com.fire17.linuxdesk.action.STOP"
        private const val CHANNEL = "linuxdesk_session"
        private const val NOTIFICATION_ID = 1917
        private const val TAG = "LinuxDesk"
        /** proot --sysvipc for the browser. Set to false if the pinned proot rejects the option (see README). */
        private const val USE_SYSVIPC = true

        fun start(ctx: Context, action: String = ACTION_START) {
            val i = Intent(ctx, SessionService::class.java).setAction(action)
            if (action == ACTION_START && Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    @Volatile private var worker: Thread? = null
    @Volatile private var browser: Process? = null
    @Volatile private var stopping = false
    @Volatile private var xBound = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusBridge: FocusBridge? = null
    private lateinit var settings: LinuxDeskSettings

    private val xConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            SessionBus.log("X server process is up (lorie in :x11)")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            xBound = false
            // Unbind so that BIND_AUTO_CREATE does not keep restarting a crashing X server.
            try { unbindService(this) } catch (_: Exception) {}
            if (!stopping) {
                SessionBus.setState(State.ERROR,
                    "The X server process died. Try Settings → 'Legacy drawing', then Start again. (logcat tag: LinuxDeskX11, LorieNative)")
                killBrowser()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = LinuxDeskSettings(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.ld_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                goForeground()
                if (worker?.isAlive != true && SessionBus.snapshot.state !in setOf(State.RUNNING, State.BROWSER_EXITED)) {
                    stopping = false
                    worker = Thread({ runSession() }, "LinuxDeskSession").also { it.start() }
                } else {
                    SessionBus.setState(SessionBus.snapshot.state, SessionBus.snapshot.message)
                }
            }
            ACTION_RESTART_BROWSER -> Thread({ restartBrowser() }, "LinuxDeskRestart").start()
            ACTION_STOP -> stopSession()
            GenieRuntime.ACTION_STOP_GENIE -> {
                GenieRuntime.stopGenie(this)
                if (worker?.isAlive == true || SessionBus.snapshot.state !in setOf(State.IDLE, State.STOPPED, State.ERROR)) {
                    updateNotification()
                } else {
                    stopSelf()   // no session: the service was only created to handle this tap
                }
            }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------------------------------

    private fun runSession() {
        try {
            acquireWakeLock()
            val installer = RootfsInstaller(this)

            SessionBus.setState(State.INSTALLING_ROOTFS, getString(R.string.ld_state_rootfs))
            SessionBus.log(installer.ensureInstalled { SessionBus.log(it) })

            focusBridge?.stop()
            focusBridge = if (settings.autoKeyboard) FocusBridge().start() else null
            installer.installHelpers(focusBridge?.configJs())
            // installHelpers copies assets/linuxdesk recursively (deskd/ included) but marks only its own three
            // scripts executable; GenieRuntime runs setup-genie.sh by path, so it needs the bit too.
            File(installer.rootfsDir, "opt/linuxdesk/setup-genie.sh").takeIf { it.isFile }?.setExecutable(true, false)

            val proot = ProotRuntime(this, settings.prootNoSeccomp)
            proot.preflight().forEach { SessionBus.log(it) }

            if (!installer.packagesInstalled()) {
                SessionBus.setState(State.INSTALLING_PACKAGES, getString(R.string.ld_state_packages))
                val r = proot.runOnce("/opt/linuxdesk/setup-packages.sh", timeoutSec = 3600) { SessionBus.log(it) }
                if (r.exitCode != 0 || !installer.packagesInstalled()) {
                    SessionBus.setState(State.ERROR, "Package setup failed (exit=${r.exitCode}). Check network, then Start again.\n${r.tail.takeLast(600)}")
                    return
                }
            }
            if (stopping) return

            SessionBus.setState(State.STARTING_X, getString(R.string.ld_state_x))
            startXServer()
            val socket = File(installer.rootfsDir, "tmp/.X11-unix/X0")
            if (!waitFor(20_000) { socket.exists() || stopping }) {
                SessionBus.log("warning: X socket $socket did not appear within 20 s; continuing (an abstract socket may still work)")
            }
            if (stopping) return

            SessionBus.setState(State.WAITING_FOR_DISPLAY, getString(R.string.ld_state_display))
            if (!waitFor(30_000) { (SessionBus.desktopConnected && SessionBus.viewWidth > 0) || stopping }) {
                SessionBus.log("warning: the display view did not connect within 30 s; starting the browser anyway")
            }
            if (stopping) return

            // Genie access ON: GenieRuntime runs setup-genie.sh once, then deskd (fresh per-session key) becomes
            // Chromium's parent with the SAME flags (CDP over a pipe), and returns true. Genie access OFF, or any
            // genie failure: false, and the proven start-browser.sh path below runs exactly as before.
            val flags = browserFlags()
            val genieOwnsBrowser = try {
                GenieRuntime.start(this, proot, flags, USE_SYSVIPC)
            } catch (t: Throwable) {
                Log.e(TAG, "genie start failed", t)
                SessionBus.log("genie: start failed (${t.javaClass.simpleName}); using the normal browser path")
                try { GenieRuntime.stop() } catch (_: Throwable) {}
                false
            }
            if (stopping) return
            if (genieOwnsBrowser) updateNotification() else launchBrowser(proot, flags)
        } catch (t: Throwable) {
            Log.e(TAG, "session failed", t)
            SessionBus.setState(State.ERROR, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun startXServer() {
        if (xBound) return
        val i = Intent(this, X11ServerService::class.java)
            .putExtra(X11ServerService.EXTRA_DPI, resources.displayMetrics.densityDpi)
            .putExtra(X11ServerService.EXTRA_LEGACY_DRAWING, settings.legacyDrawing)
        xBound = bindService(i, xConnection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        if (!xBound) error("bindService(X11ServerService) returned false")
    }

    private fun browserFlags(): List<String> {
        val dm = resources.displayMetrics
        val locales = resources.configuration.locales
        val langs = (0 until locales.size()).joinToString(",") { locales[it].toLanguageTag() }
        val params = ChromiumFlags.Params(
            mode = settings.mode,
            url = settings.homeUrl,
            density = dm.density,
            scalePercent = settings.scalePercent,
            screenWidthPx = if (SessionBus.viewWidth > 0) SessionBus.viewWidth else dm.widthPixels,
            screenHeightPx = if (SessionBus.viewHeight > 0) SessionBus.viewHeight else dm.heightPixels,
            extensionDir = if (focusBridge != null) "/opt/linuxdesk/focus-bridge" else null,
            useGpu = settings.useGpu,
            acceptLanguages = langs,
        )
        return ChromiumFlags.build(params)
    }

    private fun launchBrowser(proot: ProotRuntime, flags: List<String> = browserFlags()) {
        SessionBus.setState(State.STARTING_BROWSER, getString(R.string.ld_state_browser))
        SessionBus.log("chromium " + flags.joinToString(" "))
        SessionBus.browserWindowUp = false

        val p = proot.start(listOf("/opt/linuxdesk/start-browser.sh") + flags, listOf("DISPLAY=:0"), sysvipc = USE_SYSVIPC)
        browser = p
        Thread({
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    SessionBus.log(line)
                    if (line.startsWith("LINUXDESK_WINDOW_UP") && !SessionBus.browserWindowUp) {
                        SessionBus.browserWindowUp = true
                        SessionBus.setState(State.RUNNING, getString(R.string.ld_state_running))
                    }
                }
            } catch (_: Exception) {
            }
            val code = try { p.waitFor() } catch (_: Exception) { -1 }
            if (browser === p && !stopping) {
                browser = null
                SessionBus.setState(State.BROWSER_EXITED, getString(R.string.ld_state_browser_exited, code))
            }
        }, "LinuxDeskBrowserLog").apply { isDaemon = true; start() }
        updateNotification()
    }

    private fun restartBrowser() {
        if (stopping) return
        if (GenieRuntime.ownsBrowser && xBound) {
            // deskd is Chromium's parent (Genie access ON): restart deskd, which restarts the browser.
            GenieRuntime.restartBrowser()
            return
        }
        killBrowser()
        if (!xBound) {
            try { GenieRuntime.stop() } catch (_: Throwable) {}
            SessionService.start(this, ACTION_START)
            return
        }
        try {
            launchBrowser(ProotRuntime(this, settings.prootNoSeccomp))
        } catch (t: Throwable) {
            SessionBus.setState(State.ERROR, "restart failed: ${t.message}")
        }
    }

    private fun killBrowser() {
        val p = browser ?: return
        browser = null
        // proot runs with --kill-on-exit, so killing it also kills the traced Chromium tree.
        try { p.destroy() } catch (_: Exception) {}
        try { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() } catch (_: Exception) {}
    }

    private fun stopSession() {
        stopping = true
        SessionBus.setState(State.STOPPING, getString(R.string.ld_state_stopping))
        Thread {
            try { GenieRuntime.stop() } catch (_: Throwable) {}
            killBrowser()
            focusBridge?.stop()
            focusBridge = null
            if (xBound) {
                try { unbindService(xConnection) } catch (_: Exception) {}
                xBound = false
            }
            releaseWakeLock()
            SessionBus.setState(State.STOPPED, getString(R.string.ld_state_stopped))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
    }

    override fun onDestroy() {
        if (!stopping) {
            stopping = true
            try { GenieRuntime.stop() } catch (_: Throwable) {}
            killBrowser()
            focusBridge?.stop()
            if (xBound) try { unbindService(xConnection) } catch (_: Exception) {}
            releaseWakeLock()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------------

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(200)
        }
        return cond()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinuxDesk:session").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, LauncherActivity::class.java), flags)
        val restart = PendingIntent.getService(this, 1,
            Intent(this, SessionService::class.java).setAction(ACTION_RESTART_BROWSER), flags)
        val stop = PendingIntent.getService(this, 2,
            Intent(this, SessionService::class.java).setAction(ACTION_STOP), flags)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_desk)
            .setContentTitle(getString(R.string.ld_app_name))
            .setContentText(getString(R.string.ld_notification_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.ld_restart_browser), restart).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.ld_stop), stop).build())
        // Spec 01 §4 rule 2: while a genie can connect, the session notification offers "Stop genie".
        if (GenieSettings.genieAccess(this) || GenieSettings.localLink(this)) {
            b.addAction(Notification.Action.Builder(null, getString(R.string.ld_genie_stop),
                GenieRuntime.stopPendingIntent(this)).build())
        }
        return b.build()
    }
}
