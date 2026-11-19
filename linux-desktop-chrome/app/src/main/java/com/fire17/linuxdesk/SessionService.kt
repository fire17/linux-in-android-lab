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
import com.fire17.linuxdesk.audio.AudioOut
import com.fire17.linuxdesk.audio.MicGuest
import com.fire17.linuxdesk.audio.PulseHost
import com.fire17.linuxdesk.audio.MicIn
import com.fire17.linuxdesk.bridge.ChromiumFlags
import com.fire17.linuxdesk.bridge.FocusBridge
import com.fire17.linuxdesk.camera.PhoneCamera
import com.fire17.linuxdesk.genie.GenieRuntime
import com.fire17.linuxdesk.genie.GenieSettings
import com.fire17.linuxdesk.gpu.GpuSession
import com.fire17.linuxdesk.linux.Distro
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
        /** Re-read MicIn.consent and start/stop capture to match. Carries no on/off value: ON exists only as
         *  MicIn.consent, which only the in-app switch (same process) can set. Not exported. */
        const val ACTION_MIC_APPLY = "com.fire17.linuxdesk.action.MIC_APPLY"
        /** Notification "Stop" for the microphone: always turns it OFF. */
        const val ACTION_MIC_OFF = "com.fire17.linuxdesk.action.MIC_OFF"
        /** Re-read PhoneCamera.consent: ON starts the loopback camera server and restarts the browser so the
         *  phone-camera extension loads; OFF stops the server at once. Carries no on/off value. Not exported. */
        const val ACTION_CAMERA_APPLY = "com.fire17.linuxdesk.action.CAMERA_APPLY"
        /** Notification "Stop camera": always turns it OFF. */
        const val ACTION_CAMERA_OFF = "com.fire17.linuxdesk.action.CAMERA_OFF"
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
    /** The distro this session runs (set at the start of runSession; ALPINE until then). */
    @Volatile private var sessionDistro: Distro = Distro.ALPINE
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
            ACTION_MIC_OFF -> { MicIn.setConsent(false); micOff(); SessionBus.notifyMicChanged() }
            ACTION_MIC_APPLY -> if (MicIn.consent) Thread({ micOn() }, "LinuxDeskMicOn").start() else micOff()
            ACTION_CAMERA_OFF -> { PhoneCamera.setConsent(false); cameraOff(); SessionBus.notifyMicChanged() }
            ACTION_CAMERA_APPLY -> if (PhoneCamera.consent) Thread({ cameraOn(restart = true) }, "LinuxDeskCamOn").start() else cameraOff()
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
            // The distro is fixed for the whole session (the launcher's choice when Start was pressed).
            val distro = Distro.current(this)
            sessionDistro = distro
            val installer = RootfsInstaller(this, distro)

            SessionBus.setState(State.INSTALLING_ROOTFS,
                if (distro == Distro.ALPINE) getString(R.string.ld_state_rootfs) else "Preparing Omarchy (Arch Linux ARM) …")
            var lastPct = -2
            SessionBus.log(installer.ensureInstalled({ SessionBus.log(it) }) { pct ->
                // First Omarchy run: the 829 MB download drives the state line (= the launcher's progress bar).
                if (pct != lastPct) {
                    lastPct = pct
                    SessionBus.downloadPercent = pct
                    if (pct in 0..100) SessionBus.setState(State.INSTALLING_ROOTFS, "Downloading Arch Linux ARM … $pct%")
                }
            })
            SessionBus.downloadPercent = -1

            focusBridge?.stop()
            focusBridge = if (settings.autoKeyboard) FocusBridge().start() else null
            installer.installHelpers(focusBridge?.configJs())
            // Phone camera: start the loopback MJPEG server only if the switch is ON, then write the extension's
            // config.js (port + token, or port 0 = off) before Chromium starts.
            if (PhoneCamera.consent) PhoneCamera.start(this)
            PhoneCamera.writeConfig(installer.rootfsDir)
            if (PhoneCamera.running) {
                SessionBus.log("camera: phone camera available to Linux")
                try { goForeground() } catch (t: Throwable) { SessionBus.log("camera: foreground type not granted (${t.javaClass.simpleName})") }
            }
            // installHelpers copies assets/linuxdesk recursively (deskd/ included) but marks only its own three
            // scripts executable; GenieRuntime runs setup-genie.sh by path, so it needs the bit too.
            File(installer.rootfsDir, "opt/linuxdesk/setup-genie.sh").takeIf { it.isFile }?.setExecutable(true, false)

            val proot = ProotRuntime(this, settings.prootNoSeccomp, distro)
            proot.preflight().forEach { SessionBus.log(it) }

            if (!installer.packagesInstalled()) {
                SessionBus.setState(State.INSTALLING_PACKAGES,
                    if (distro == Distro.ALPINE) getString(R.string.ld_state_packages)
                    else "First run: pacman installs Chrome, i3 and the Omarchy themes (~0.75 GB download, ~2.4 GB on disk). This takes a while …")
                val r = proot.runOnce(distro.setupScript, timeoutSec = 3600) { SessionBus.log(it) }
                if (r.exitCode != 0 || !installer.packagesInstalled()) {
                    SessionBus.setState(State.ERROR, "Package setup failed (exit=${r.exitCode}). Check network, then Start again.\n${r.tail.takeLast(600)}")
                    return
                }
            }
            if (stopping) return
            // GPU v2 (experimental, setting OFF by default): Adreno only; installs the kgsl Mesa once. Never fails the session.
            GpuSession.prepare(proot, settings.useGpu)
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
            GpuSession.selfCheck(proot)   // GPU v2: logs "gpu: renderer=… hardware=…"; no-op when GPU mode is off

            // Audio (spec 02 #1 v2): the ONE guest PulseAudio (audio/PulseHost) starts before the browser, so
            // Chromium gets PULSE_SERVER in its env. Any failure is logged and the session continues silently.
            try {
                AudioOut.prepare(this)             // Sound ON (default): loopback TCP record module, random port
                // Mic always on (Tami, AC132525): the saved setting re-arms consent each session, if permission exists.
                if (settings.micAlwaysOn && MicIn.hasPermission(this)) MicIn.setConsent(true)
                MicGuest.prepare()                 // mic switch already ON: PulseAudio must start even if Sound is OFF
                if (PulseHost.start(proot)) AudioOut.start()
            } catch (t: Throwable) {
                SessionBus.log("pulse: audio setup failed (${t.javaClass.simpleName}); continuing without sound")
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
            if (MicIn.consent && !stopping) micOn()   // switch was turned ON before the session came up
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
            .putExtra(X11ServerService.EXTRA_ROOTFS, sessionDistro.dirName)
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
            extraExtensionDirs = if (PhoneCamera.running) listOf(PhoneCamera.EXTENSION_DIR) else emptyList(),
            useGpu = GpuSession.active,          // GPU v2: true only if the setting is ON and the device/driver are ready
            gpuFallback = GpuSession.fallback,
            acceptLanguages = langs,
        )
        return ChromiumFlags.build(params)
    }

    private fun launchBrowser(proot: ProotRuntime, flags: List<String> = browserFlags()) {
        SessionBus.setState(State.STARTING_BROWSER, getString(R.string.ld_state_browser))
        SessionBus.log("chromium " + flags.joinToString(" "))
        SessionBus.browserWindowUp = false

        // PulseHost.guestEnv() is empty when PulseAudio is not running, so Chromium then starts exactly as before.
        val p = proot.start(listOf(proot.distro.startScript) + flags, listOf("DISPLAY=:0") + PulseHost.guestEnv() + GpuSession.guestEnv(), sysvipc = USE_SYSVIPC)
        browser = p
        Thread({
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    SessionBus.log(line)
                    if (line.startsWith("LINUXDESK_WINDOW_UP") && !SessionBus.browserWindowUp) {
                        SessionBus.browserWindowUp = true
                        SessionBus.setState(State.RUNNING, getString(R.string.ld_state_running))
                    }
                    // GPU v2: a GPU-process crash pins software rendering and restarts the browser once.
                    if (GpuSession.onBrowserLine(line) && GpuSession.restartWanted() && browser === p && !stopping) {
                        Thread({ restartBrowser() }, "LinuxDeskGpuFallback").start()
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
            launchBrowser(ProotRuntime(this, settings.prootNoSeccomp, sessionDistro))
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
        endMic()   // at once, on the caller's thread: the mic is released before anything else is torn down
        endCamera()
        Thread {
            try { GenieRuntime.stop() } catch (_: Throwable) {}
            killBrowser()
            try { AudioOut.stop(); PulseHost.stop() } catch (_: Throwable) {}
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
        endMic()
        endCamera()
        if (!stopping) {
            stopping = true
            try { GenieRuntime.stop() } catch (_: Throwable) {}
            killBrowser()
            try { AudioOut.stop(); PulseHost.stop() } catch (_: Throwable) {}
            focusBridge?.stop()
            if (xBound) try { unbindService(xConnection) } catch (_: Exception) {}
            releaseWakeLock()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------------

    // ------------------------------------------------------------------------------------------ microphone

    @Volatile private var micPort = 0

    // ------------------------------------------------------------------------------------------ phone camera

    /** Switch turned ON mid-session: start the server, write config.js, restart Chromium to load the extension. */
    private fun cameraOn(restart: Boolean) {
        if (!PhoneCamera.consent || stopping) return
        val wasRunning = PhoneCamera.running
        if (PhoneCamera.start(this) == null) { SessionBus.log("camera: not started (permission missing or no back camera)"); return }
        PhoneCamera.writeConfig(sessionDistro.rootfsDir(this))
        SessionBus.log("camera: phone camera available to Linux")
        try { goForeground() } catch (t: Throwable) { SessionBus.log("camera: foreground type not granted (${t.javaClass.simpleName})") }
        updateNotification()
        if (restart && !wasRunning && SessionBus.snapshot.state in setOf(State.RUNNING, State.BROWSER_EXITED)) restartBrowser()
    }

    /** OFF: the server stops at once (camera closed, port gone). The extension then has nothing to read. */
    private fun cameraOff() {
        PhoneCamera.stop()
        try { PhoneCamera.writeConfig(sessionDistro.rootfsDir(this)) } catch (_: Throwable) {}
        updateNotification()
    }

    /** Session ending: consent does not outlive the session. */
    private fun endCamera() {
        PhoneCamera.setConsent(false)
        SessionBus.notifyMicChanged()
    }

    /** Session ending: consent does not outlive the session. */
    private fun endMic() {
        MicIn.setConsent(false)   // stops AudioRecord synchronously
        micPort = 0
        SessionBus.notifyMicChanged()
    }

    /** Loads the guest source (once per session port), then starts capture. Runs off the main thread. */
    @Synchronized private fun micOn() {
        if (!MicIn.consent || stopping || MicIn.active) return
        val live = SessionBus.snapshot.state in setOf(State.STARTING_BROWSER, State.RUNNING, State.BROWSER_EXITED)
        if (!live) { SessionBus.log("mic: will start when the session is running"); return }
        if (!PulseHost.running) {
            // PulseAudio starts once per session (PulseHost), before the browser. If neither Sound nor the mic was ON
            // at Start, there is no sound server in this session; starting one now would not reach Chromium's env.
            SessionBus.log("mic: no PulseAudio in this session (turn Sound or the mic on, then Restart the session)")
            return
        }
        // Android 14+: capturing from the background needs the FGS to carry the microphone type. Promoting is
        // allowed here because the person just flipped the switch with the app on screen.
        goForeground(withMic = true)
        try {
            if (micPort == 0) {
                // Up to 3 tries: on Tami's phone the first load failed with no reason while the same pactl steps
                // run one by one succeeded, so a transient failure (PulseAudio still settling) is the likely cause.
                var port = 0
                for (attempt in 1..3) {
                    val p = MicGuest.pickPort()
                    if (runPactl(MicGuest.loadScript(p))) { port = p; break }
                    SessionBus.log("mic: adding the Linux microphone source failed (try $attempt/3)")
                    if (attempt < 3) Thread.sleep(1500L * attempt)
                }
                if (port == 0) {
                    SessionBus.log("mic: could not add the Linux microphone source after 3 tries")
                    goForeground(withMic = false)
                    return
                }
                micPort = port
            }
            if (!MicIn.consent) { micOff(); return }   // switched OFF while the source was being added
            MicIn.onState = { SessionBus.log(it) }
            if (!MicIn.start(this, micPort)) SessionBus.log("mic: not started (permission missing?)")
        } catch (t: Throwable) {
            SessionBus.log("mic: start failed (${t.javaClass.simpleName})")
            MicIn.stop()
        }
        updateNotification()
    }

    private fun micOff() {
        MicIn.stop()   // synchronous: AudioRecord is released before this returns
        val hadPort = micPort != 0
        micPort = 0
        if (!stopping && hadPort) Thread({ runPactl(MicGuest.unloadScript()) }, "LinuxDeskMicOff").start()
        if (!stopping) { goForeground(withMic = false); updateNotification() }
    }

    /**
     * Runs a multi-line pactl script (MicGuest; it exports PULSE_SERVER=PulseHost's socket) against the ONE guest
     * PulseAudio. A script, not PulseHost.pactl(), because unloading must match OUR modules by args and never touch
     * audio-out's module-simple-protocol-tcp. On failure the exit code and the last output lines are logged:
     * Tami's phone (2026-11-19 21:01) showed only "could not add the Linux microphone source" with no reason, while the
     * same pactl steps run one by one through the genie all succeeded.
     */
    private fun runPactl(script: String): Boolean {
        if (!PulseHost.running) { SessionBus.log("mic: pactl skipped, PulseAudio is not running"); return false }
        val r = ProotRuntime(this, settings.prootNoSeccomp, sessionDistro).runOnce(script, timeoutSec = 15)
        if (r.exitCode != 0) {
            SessionBus.log("mic: pactl script failed, exit=${r.exitCode ?: "timeout"} after ${r.millis} ms")
            r.tail.lines().filter { it.isNotBlank() }.takeLast(8).forEach { SessionBus.log("mic: pactl> ${it.take(200)}") }
        }
        return r.exitCode == 0
    }

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

    private fun goForeground(withMic: Boolean = MicIn.active) {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (withMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (PhoneCamera.running) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            try {
                startForeground(NOTIFICATION_ID, n, type)
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException when promoting from the background
                if (!withMic) throw e
                SessionBus.log("mic: Android did not allow microphone use from the background; open the app and try again")
                MicIn.setConsent(false)
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
        } else if (Build.VERSION.SDK_INT >= 30 && withMic) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
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
        // While the phone microphone feeds Linux, the session notification says so, with a one-tap Stop.
        if (MicIn.active || MicIn.consent) {
            val micStop = PendingIntent.getService(this, 3,
                Intent(this, SessionService::class.java).setAction(ACTION_MIC_OFF), flags)
            b.setContentText(getString(R.string.ld_mic_on_text))
            b.addAction(Notification.Action.Builder(null, getString(R.string.ld_mic_stop), micStop).build())
        }
        // Spec 01 §4 rule 2: while a genie can connect, the session notification offers "Stop genie".
        if (PhoneCamera.running) {
            val camStop = PendingIntent.getService(this, 4,
                Intent(this, SessionService::class.java).setAction(ACTION_CAMERA_OFF), flags)
            b.setSubText(getString(R.string.ld_camera_on_text))
            b.addAction(Notification.Action.Builder(null, getString(R.string.ld_camera_stop), camStop).build())
        }
        if (GenieSettings.genieAccess(this) || GenieSettings.localLink(this)) {
            b.addAction(Notification.Action.Builder(null, getString(R.string.ld_genie_stop),
                GenieRuntime.stopPendingIntent(this)).build())
        }
        return b.build()
    }
}
