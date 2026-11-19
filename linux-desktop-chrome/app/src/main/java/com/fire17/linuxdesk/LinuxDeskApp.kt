package com.fire17.linuxdesk

import android.app.Application
import android.content.Context
import android.os.Build
import com.termux.x11.LorieApp

/**
 * The Application must be (a subclass of) lorie's LorieApp. LorieApp holds lorie's preferences and
 * notification state, and its Receiver routes the X server's ACTION_START binder to the window.
 *
 * Before LorieApp reads its preferences, we write the values that make LinuxDesk "the phone screen IS
 * a Linux display". They live in lorie's default SharedPreferences file (`<package>_preferences`).
 */
class LinuxDeskApp : LorieApp() {

    override fun onCreate() {
        if (isMainProcess()) seedLoriePreferences()
        super.onCreate()
    }

    private fun isMainProcess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P || Application.getProcessName() == packageName

    private fun seedLoriePreferences() {
        val p = getSharedPreferences(packageName + "_preferences", Context.MODE_PRIVATE)
        val e = p.edit()
        // Enforced on every launch. These define the product:
        //  touchMode 3 = "Direct touch". lorie exposes an XI2 XIDirectTouch device ("Lorie touch", 20
        //  points). Chromium's X11 touch factory only registers direct-touch devices, so Chromium does its
        //  own scroll, fling, pinch-zoom and long-press, and lorie turns off its own pinch-zoomer.
        e.putString("touchMode", "3")
        e.putBoolean("fullscreen", true)
        e.putBoolean("hideCutout", true)
        // X screen size = the view size in real pixels. Rotation resizes the screen through RandR.
        e.putString("displayResolutionMode", "native")
        // OFF = the soft keyboard commits Unicode keysyms, so Hebrew works without an XKB layout.
        e.putBoolean("preferScancodes", false)
        // Seeded once. The user can change these later in lorie's preferences screen.
        if (!p.getBoolean(SEED_KEY, false)) {
            e.putString("screenIdleTimeout", "never")
            e.putBoolean("clipboardEnable", true)
            e.putBoolean("PIP", false)
            e.putString("backButtonAction", "toggle soft keyboard")
            e.putBoolean(SEED_KEY, true)
        }
        e.commit()
    }

    private companion object {
        const val SEED_KEY = "linuxdesk_seeded_v1"
    }
}
