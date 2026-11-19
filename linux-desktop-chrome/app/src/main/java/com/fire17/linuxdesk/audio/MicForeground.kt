package com.fire17.linuxdesk.audio

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Foreground-only microphone (Tami, voice 2026-11-19 21:39: "המיקרופון צריך לעבוד רק אם אני בתוך, עם האפליקציה
 * כאילו בפרונט"). His WhatsApp voice note came out EMPTY while LinuxDesk held the mic from its foreground service
 * (a VOICE_COMMUNICATION capture silences other apps' recordings).
 *
 * Counts this process's started activities (DesktopActivity, LauncherActivity, …). When the count drops to 0,
 * [listener] gets false and SessionService releases AudioRecord at once (consent is kept); when an activity
 * starts again it gets true and the mic resumes. A rotation (stop + start of a recreated activity) is not an edge.
 * The setting "Allow microphone in background" (LinuxDeskSettings.micInBackground, OFF) keeps the old behaviour.
 */
object MicForeground {
    /** The 0↔1 edge detector, pure so MicForegroundTest runs it on the JVM. Not thread-safe by itself. */
    internal class Counter {
        var started = 0
            private set
        /** The last activity stopped only to be recreated (rotation): still "visible", no edge. */
        var configPending = false
            private set

        val visible: Boolean get() = started > 0 || configPending

        /** Returns true when the app just came to the foreground. */
        fun onStarted(): Boolean {
            started += 1
            val edge = started == 1 && !configPending
            configPending = false
            return edge
        }

        /** Returns true when the app just went to the background. */
        fun onStopped(changingConfigurations: Boolean): Boolean {
            started = (started - 1).coerceAtLeast(0)
            if (started > 0) return false
            if (changingConfigurations) { configPending = true; return false }
            return true
        }
    }

    private val counter = Counter()

    /** True while some LinuxDesk activity is on screen. */
    val visible: Boolean get() = synchronized(counter) { counter.visible }

    /** SessionService's hook (main thread): true = came to the foreground, false = went to the background. */
    @Volatile var listener: ((Boolean) -> Unit)? = null

    /** LinuxDeskApp.onCreate, main process only. */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                if (synchronized(counter) { counter.onStarted() }) fire(true)
            }
            override fun onActivityStopped(a: Activity) {
                if (synchronized(counter) { counter.onStopped(a.isChangingConfigurations) }) fire(false)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun fire(fg: Boolean) { try { listener?.invoke(fg) } catch (_: Throwable) {} }

    /** May the phone mic capture right now? Consent always; plus on screen, unless background use is allowed. */
    internal fun mayCapture(consent: Boolean, visible: Boolean, allowBackground: Boolean) =
        consent && (visible || allowBackground)
}
