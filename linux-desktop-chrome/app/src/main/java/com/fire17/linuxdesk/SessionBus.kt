package com.fire17.linuxdesk

import android.os.Handler
import android.os.Looper
import android.util.Log
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

    private const val MAX_LINES = 600
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val lines = ArrayDeque<String>()

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
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        main.post { listeners.forEach { it.onLog(line) } }
    }

    fun logText(): String = synchronized(lines) { lines.joinToString("\n") }

    fun lastLogLine(): String = synchronized(lines) { lines.lastOrNull().orEmpty() }

    fun requestKeyboard(show: Boolean) {
        main.post { listeners.forEach { it.onKeyboardRequest(show) } }
    }
}
