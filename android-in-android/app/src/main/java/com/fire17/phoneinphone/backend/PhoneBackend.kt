package com.fire17.phoneinphone.backend

import android.view.Surface

/**
 * The swappable "phone engine" behind the PhoneInPhone shell.
 *
 * The shell (PhoneActivity) knows nothing about scrcpy, proot or AVF. It hands a backend a
 * Surface to draw on, forwards touches in NORMALIZED coordinates (0..1 across the guest screen),
 * forwards keys and committed text, and renders whatever stages/logs/stats the backend reports.
 *
 * Implementations:
 *  - [com.fire17.phoneinphone.stream.StreamBackend]   — a real Android running elsewhere, streamed
 *    over the scrcpy protocol (expected to work end-to-end).
 *  - [com.fire17.phoneinphone.ondevice.OnDeviceBackend] — instrumented experiment that tries to boot
 *    an Android userland on this phone (proot track + AVF track) and reports exactly how far it got.
 */
interface PhoneBackend {
    val id: String
    val displayName: String

    /** Begin work on a background thread. Must return quickly. */
    fun start(listener: BackendListener)

    /** Called whenever the shell's surface appears (non-null) or goes away (null). */
    fun setSurface(surface: Surface?)

    /** Tear everything down. Idempotent. */
    fun stop()

    /** [action] is one of [TouchAction]; x/y are normalized 0..1 over the guest display. */
    fun sendTouch(action: TouchAction, pointerId: Long, x: Float, y: Float, pressure: Float)

    /** Android KeyEvent action (0 = down, 1 = up) + KeyEvent keycode + meta state. */
    fun sendKey(action: Int, keyCode: Int, metaState: Int = 0)

    /** Committed text from the host soft keyboard (may be any Unicode, e.g. Hebrew). */
    fun sendText(text: String)

    fun stats(): BackendStats

    /** True if this backend renders frames into the surface (false = status panel only). */
    val rendersVideo: Boolean
}

enum class TouchAction { DOWN, UP, MOVE }

interface BackendListener {
    fun onStage(stage: Stage)
    fun onLog(line: String)
    fun onVideoSize(width: Int, height: Int)
    /** Backend finished or died. [error] null = clean stop. */
    fun onStopped(error: Throwable?)
}

enum class StageStatus(val mark: String) {
    PENDING("·"),
    RUNNING("…"),
    OK("✓"),
    FAIL("✗"),
    SKIPPED("–"),
    NOT_IMPLEMENTED("∅"),
}

/** One instrumented step. [key] is stable so the UI can update a step in place. */
data class Stage(
    val key: String,
    val title: String,
    val status: StageStatus,
    val detail: String = "",
)

data class BackendStats(
    val fps: Float = 0f,
    val kbps: Float = 0f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val framesDecoded: Long = 0,
    val extra: String = "",
)
