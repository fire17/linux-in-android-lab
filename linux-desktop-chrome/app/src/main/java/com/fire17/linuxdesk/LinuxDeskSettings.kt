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
     * sound). LIVE since ld-perf (Tami, voice 21:36, "בהגדרות יהיה אפשר להחליט לעשות טוגל אם הספיקרים יוצאים או
     * לא"): flipping it in a running session stops/starts AudioOut at once and mutes/unmutes the "ld" sink
     * (SessionService.ACTION_SOUND_APPLY); no restart. The stored value also decides the next session's start.
     */
    var sound: Boolean
        get() = p.getBoolean("sound", true)
        set(v) = p.edit().putBoolean("sound", v).apply()

    /**
     * ld-perf recommendation, OFF until Tami approves: a larger AudioTrack buffer (~200 ms instead of ~40 ms) and
     * normal (not low-latency) mode. Fewer clicks when proot/Chromium steal the CPU, at the cost of ~150 ms more
     * delay between video and sound. Applied when the sound stream (re)connects.
     */
    var soundSmooth: Boolean
        get() = p.getBoolean("sound_smooth", false)
        set(v) = p.edit().putBoolean("sound_smooth", v).apply()

    /**
     * Microphone to Linux, kept ON across sessions. Tami (voice AC132525, 2026-11-19 21:01): "אפשר שהמיקרופון יהיה
     * דלוק כל הזמן, עד שנפתור את זה". So the default is ON and the session turns the mic on by itself at Start, once
     * RECORD_AUDIO was granted once. The switch still turns it OFF, and the genie still cannot turn it on.
     */
    var micAlwaysOn: Boolean
        get() = p.getBoolean("mic_always_on", true)
        set(v) = p.edit().putBoolean("mic_always_on", v).apply()

    /**
     * "Allow microphone in background". OFF by default = foreground-only (Tami, voice 2026-11-19 21:39): the phone
     * mic feeds Linux only while a LinuxDesk screen is on, and is released at once when the app leaves the screen,
     * so WhatsApp and other apps can record. ON = the old behaviour (capture continues from the foreground service).
     */
    var micInBackground: Boolean
        get() = p.getBoolean("mic_in_background", false)
        set(v) = p.edit().putBoolean("mic_in_background", v).apply()

    /**
     * Phone camera "relay-only" privacy mode (cameras v3). OFF by default (a recommendation; Tami decides): pages
     * get frames straight from 127.0.0.1 and can see the port and token. ON: pages never see port/token; every frame
     * goes through the extension's service worker (slower: one base64 hop per frame). Live, no restart.
     */
    var cameraRelayOnly: Boolean
        get() = p.getBoolean("camera_relay_only", false)
        set(v) = p.edit().putBoolean("camera_relay_only", v).apply()

    /** Off by default: inside proot there is no GPU device, so Chromium uses software compositing. */
    var useGpu: Boolean
        get() = p.getBoolean("useGpu", false)
        set(v) = p.edit().putBoolean("useGpu", v).apply()
}
