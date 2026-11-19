package com.fire17.linuxdesk

import android.content.Context
import com.fire17.linuxdesk.bridge.ChromiumFlags

/** LinuxDesk's own settings. lorie's X settings live in lorie's own preferences file. */
class LinuxDeskSettings(context: Context) {
    private val p = context.getSharedPreferences("linuxdesk_settings", Context.MODE_PRIVATE)

    var homeUrl: String
        get() = p.getString("homeUrl", ChromiumFlags.DEFAULT_URL) ?: ChromiumFlags.DEFAULT_URL
        set(v) = p.edit().putString("homeUrl", v).apply()

    var mode: ChromiumFlags.Mode
        get() = runCatching { ChromiumFlags.Mode.valueOf(p.getString("mode", null) ?: "") }
            .getOrDefault(ChromiumFlags.Mode.BROWSER)
        set(v) = p.edit().putString("mode", v.name).apply()

    /** Percent of the Android density used as Chromium's device scale factor (100 = same size as Android UI). */
    var scalePercent: Int
        get() = p.getInt("scalePercent", 100)
        set(v) = p.edit().putInt("scalePercent", v).apply()

    /** Experimental: pop the soft keyboard when a web text field gets focus (bundled Chromium extension). */
    var autoKeyboard: Boolean
        get() = p.getBoolean("autoKeyboard", true)
        set(v) = p.edit().putBoolean("autoKeyboard", v).apply()

    /** Passes -legacy-drawing to lorie (upstream's documented fix for a black screen). */
    var legacyDrawing: Boolean
        get() = p.getBoolean("legacyDrawing", false)
        set(v) = p.edit().putBoolean("legacyDrawing", v).apply()

    /** PROOT_NO_SECCOMP=1: slower ptrace-only mode, for kernels where seccomp acceleration misbehaves. */
    var prootNoSeccomp: Boolean
        get() = p.getBoolean("prootNoSeccomp", false)
        set(v) = p.edit().putBoolean("prootNoSeccomp", v).apply()

    /**
     * Sound out: guest audio (PulseAudio null sink) plays through the phone speaker. Default ON (Tami asked for
     * sound). Applied at the next session start. OFF = PulseAudio is not started for sound, Chromium is muted.
     */
    var sound: Boolean
        get() = p.getBoolean("sound", true)
        set(v) = p.edit().putBoolean("sound", v).apply()

    /** Off by default: inside proot there is no GPU device, so Chromium uses software compositing. */
    var useGpu: Boolean
        get() = p.getBoolean("useGpu", false)
        set(v) = p.edit().putBoolean("useGpu", v).apply()
}
