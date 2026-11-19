package com.fire17.linuxinapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.fire17.linuxinapp.proot.ProotLauncher
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Foreground service that OWNS the terminal session, so the proot shell survives the activity
 * being destroyed (rotation, app switch). Holds a partial wakelock while a shell is alive.
 *
 * The activity binds, gets [session], and swaps in itself as the [TerminalSessionClient]
 * (via [clientDelegate]) while visible.
 */
class LinuxService : Service() {

    inner class LocalBinder : Binder() { val service: LinuxService get() = this@LinuxService }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null

    var session: TerminalSession? = null
        private set

    /** Set by the activity while it is attached; null otherwise. */
    var clientDelegate: TerminalSessionClient? = null

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { clientDelegate?.onTextChanged(changedSession) }
        override fun onTitleChanged(changedSession: TerminalSession) { clientDelegate?.onTitleChanged(changedSession) }
        override fun onSessionFinished(finishedSession: TerminalSession) {
            // Ignore the late callback of a session we already replaced via restartSession().
            if (finishedSession !== session) return
            clientDelegate?.onSessionFinished(finishedSession)
            releaseWakeLock()
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) { clientDelegate?.onCopyTextToClipboard(session, text) }
        override fun onPasteTextFromClipboard(session: TerminalSession?) { clientDelegate?.onPasteTextFromClipboard(session) }
        override fun onBell(session: TerminalSession) { clientDelegate?.onBell(session) }
        override fun onColorsChanged(session: TerminalSession) { clientDelegate?.onColorsChanged(session) }
        override fun onTerminalCursorStateChange(state: Boolean) { clientDelegate?.onTerminalCursorStateChange(state) }
        override fun getTerminalCursorStyle(): Int? = clientDelegate?.terminalCursorStyle
        override fun logError(tag: String?, message: String?) { android.util.Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { android.util.Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { android.util.Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { android.util.Log.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { android.util.Log.v(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { android.util.Log.e(tag ?: TAG, message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { android.util.Log.e(tag ?: TAG, "error", e) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_NOT_STICKY
    }

    /**
     * Creates (once) the proot login-shell session. MUST be called on the main thread
     * (TerminalSession builds its Handler on the calling thread's Looper). The process itself
     * starts when a TerminalView first lays out and calls session.updateSize().
     */
    fun getOrCreateSession(): TerminalSession {
        session?.let { if (it.isRunning || it.emulator == null) return it }
        val launcher = ProotLauncher(this)
        val argv = launcher.loginShellCommand()
        val s = TerminalSession(
            argv[0],                         // execvp path: <nativeLibraryDir>/libproot.so
            filesDir.absolutePath,           // host cwd
            argv.toTypedArray(),             // full argv incl. argv[0]
            launcher.hostEnvArray(),         // TerminalSession clearenv()s, then applies this
            5000,                            // transcript rows
            client,
        )
        session = s
        acquireWakeLock()
        return s
    }

    /** Kill the current shell (if any) and create a fresh one. Main thread. */
    fun restartSession(): TerminalSession {
        session?.finishIfRunning()
        session = null
        return getOrCreateSession()
    }

    private fun goForeground() {
        // getSystemService(Class) is @Nullable in the SDK stubs -> cast via the String overload.
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinuxInApp::shell").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onDestroy() {
        session?.finishIfRunning()
        session = null
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "linux_session"
        private const val NOTIF_ID = 1917
        private const val TAG = "LinuxService"
    }
}
