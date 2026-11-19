package com.fire17.linuxdesk

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fire17.linuxdesk.genie.SessionLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-process state shared by SessionService, LauncherActivity and DesktopActivity (all three run in the
 * main app process; only the X server runs in ":x11"). Listeners are always called on the main thread.
 */
object SessionBus {

    enum class State {
        IDLE,
        INSTALLING_ROOTFS,
        INSTALLING_PACKAGES,
        STARTING_X,
        WAITING_FOR_DISPLAY,
        STARTING_BROWSER,
        RUNNING,
        BROWSER_EXITED,
        STOPPING,
        STOPPED,
        ERROR,
    }

    data class Snapshot(val state: State, val message: String)

    interface Listener {
        fun onState(s: Snapshot) {}
        fun onLog(line: String) {}
        fun onKeyboardRequest(show: Boolean) {}
    }

    /** Lines shown in the launcher / shared by LogShare. The ring itself keeps more, for `sys.logs`. */
    private const val MAX_LINES = 600
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    /**
     * Bounded, with a monotonically increasing line counter (genie `sys.logs` cursors survive old lines
     * dropping out). Lines logged here must never contain the pairing token, the session key or iroh secrets.
     */
    private val lines = SessionLog.ring

    @Volatile var snapshot = Snapshot(State.IDLE, "")
        private set

    /** Set by DesktopActivity: LorieView is connected to the X server. */
    @Volatile var desktopConnected = false

    /** Set when fit-window.sh reports the first Chromium top-level window. */
    @Volatile var browserWindowUp = false

    /** Last LorieView size in real pixels, which is also the X screen size in "native" resolution mode. */
    @Volatile var viewWidth = 0
    @Volatile var viewHeight = 0

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun setState(state: State, message: String) {
        val s = Snapshot(state, message)
        snapshot = s
        log("[$state] $message")
        main.post { listeners.forEach { it.onState(s) } }
    }

    fun log(line: String) {
        // Mirrored to logcat, so adb and the CI emulator job can follow a session.
        Log.i("LinuxDeskSession", line)
        lines.add(line)
        main.post { listeners.forEach { it.onLog(line) } }
    }

    fun logText(): String = lines.lastLines(MAX_LINES).joinToString("\n")

    fun lastLogLine(): String = lines.lastLines(1).lastOrNull().orEmpty()

    fun requestKeyboard(show: Boolean) {
        main.post { listeners.forEach { it.onKeyboardRequest(show) } }
    }
}
