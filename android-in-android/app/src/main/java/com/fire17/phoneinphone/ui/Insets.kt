package com.fire17.phoneinphone.ui

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * targetSdk 35+ forces edge-to-edge on Android 15+ (and the opt-out is gone at targetSdk 36), and
 * `adjustResize` no longer shrinks the window for the IME. Without this, PhoneActivity's
 * Back/Home/Recents row sits under a 3-button navigation bar and the soft keyboard covers inputs.
 *
 * Pads left/right/bottom by system bars + display cutout + IME. Top is left alone: the platform
 * ActionBar (Theme.Material.Light.DarkActionBar) already accounts for the status bar.
 */
internal fun View.padForSystemBars() {
    // Only Android 15+ (API 35) enforces edge-to-edge for targetSdk 35+. Below that the decor still
    // fits system bars and adjustResize still works, so padding there would double up.
    if (Build.VERSION.SDK_INT < 35) return
    val l = paddingLeft
    val t = paddingTop
    val r = paddingRight
    val b = paddingBottom
    setOnApplyWindowInsetsListener { v, insets ->
        val i = insets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
        )
        v.setPadding(l + i.left, t, r + i.right, b + i.bottom)
        insets
    }
    requestApplyInsets()
}
