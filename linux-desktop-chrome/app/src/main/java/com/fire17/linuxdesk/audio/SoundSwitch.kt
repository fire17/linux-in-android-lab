package com.fire17.linuxdesk.audio

import android.content.Context
import com.fire17.linuxdesk.LinuxDeskSettings
import com.fire17.linuxdesk.SessionBus
import com.fire17.linuxdesk.SessionBus.State

/**
 * The ONE place the Sound switch is flipped (ld-perf; Tami, voice 21:36): the launcher checkbox and the desktop's
 * keyboard-button menu both call [set]. It saves `LinuxDeskSettings.sound` (still the stored value, default ON) and,
 * while a session runs, applies it at once through [AudioOut.setLive] (no restart). Main process only: the launcher,
 * the desktop activity and SessionService all live there (only the X server runs in :x11).
 */
object SoundSwitch {
    private val LIVE = setOf(State.STARTING_BROWSER, State.RUNNING, State.BROWSER_EXITED)

    fun isOn(ctx: Context): Boolean = LinuxDeskSettings(ctx).sound

    fun set(ctx: Context, on: Boolean) {
        val s = LinuxDeskSettings(ctx)
        if (s.sound == on) return   // the session already runs with the stored value (AudioOut.prepare read it)
        s.sound = on
        if (live()) AudioOut.setLive(ctx, on)
        else SessionBus.log("sound: ${if (on) "ON" else "OFF"} (applies at the next Start)")
    }

    fun toggle(ctx: Context): Boolean = (!isOn(ctx)).also { set(ctx, it) }

    /** "Smoother sound" (OFF by default, ld-perf recommendation). Saved, and live on a playing stream. */
    fun setSmooth(ctx: Context, on: Boolean) {
        LinuxDeskSettings(ctx).soundSmooth = on
        AudioOut.setSmooth(on)
    }

    private fun live() = SessionBus.snapshot.state in LIVE
}
