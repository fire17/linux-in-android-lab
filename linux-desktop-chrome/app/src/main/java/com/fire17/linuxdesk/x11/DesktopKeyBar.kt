package com.fire17.linuxdesk.x11

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The Linux display's bottom key bar (Tami 2026-11-19 21:36: "Super and Space buttons at the bottom, so Super and
 * Space are easy to press together"). Gboard has no Super key, and i3 + Omarchy's bindings are all Super+…
 *
 * Keys go to X as REAL key events through lorie's own path, `LorieView.sendKeyEvent(scanCode, keyCode, down)`, the
 * same call termux-x11's extra-keys bar uses for its META/CTRL/ALT/SHIFT keys. [send] is that call, injected, so
 * this class does not depend on lorie and its behavior is unit-testable.
 *
 * Keys:
 *  - "Super+Space": one tap = Super down, Space down, Space up, Super up (opens rofi = Omarchy's menu).
 *  - Super / Ctrl / Alt / Shift are STICKY modifiers: tap = held (highlighted) until the next normal key is sent,
 *    then released; double-tap = locked until tapped again. Long-press = held while the finger is down.
 *  - Space, Esc, Tab, Enter and the arrows are normal keys: sent with the held modifiers around them.
 */
class DesktopKeyBar(
    context: Context,
    private val send: (keyCode: Int, down: Boolean) -> Boolean,
) : HorizontalScrollView(context) {

    /** One modifier's state: off, held for the next key only, or locked until tapped again. */
    enum class Mod { OFF, ONCE, LOCKED }

    /** Pure state machine for the modifiers (no views), so the key sequences are unit-tested on the JVM. */
    class Keys(private val send: (keyCode: Int, down: Boolean) -> Boolean) {
        val mods = linkedMapOf(
            KeyEvent.KEYCODE_META_LEFT to Mod.OFF,
            KeyEvent.KEYCODE_CTRL_LEFT to Mod.OFF,
            KeyEvent.KEYCODE_ALT_LEFT to Mod.OFF,
            KeyEvent.KEYCODE_SHIFT_LEFT to Mod.OFF,
        )
        private var lastTap = 0L to 0L   // keyCode to uptime, for double-tap = lock

        /** Taps a modifier: OFF → ONCE (pressed in X now) → OFF; a second tap within [DOUBLE_TAP_MS] → LOCKED. */
        fun tapModifier(code: Int, now: Long) {
            val cur = mods[code] ?: return
            val doubleTap = lastTap.first == code.toLong() && now - lastTap.second < DOUBLE_TAP_MS
            lastTap = code.toLong() to now
            mods[code] = when {
                cur == Mod.OFF -> { send(code, true); Mod.ONCE }
                cur == Mod.ONCE && doubleTap -> Mod.LOCKED
                else -> { send(code, false); Mod.OFF }
            }
        }

        /** Press-and-hold: [code] goes down now (if it is not already) and stays down until [release]. */
        fun hold(code: Int) {
            if (mods[code] == Mod.OFF) { send(code, true); mods[code] = Mod.ONCE }
        }

        /** Ends a press-and-hold (or any held state) of [code]. */
        fun release(code: Int) {
            if (mods[code] != null && mods[code] != Mod.OFF) { send(code, false); mods[code] = Mod.OFF }
        }

        /** Sends [code] (down+up) with the held modifiers; then releases the ONCE ones (LOCKED stay down). */
        fun tapKey(code: Int) {
            send(code, true); send(code, false)
            releaseOnce()
        }

        /** Super+Space as one gesture, whatever the modifiers' state (Super is restored to how it was). */
        fun superSpace() {
            val superHeld = mods[KeyEvent.KEYCODE_META_LEFT] != Mod.OFF
            if (!superHeld) send(KeyEvent.KEYCODE_META_LEFT, true)
            send(KeyEvent.KEYCODE_SPACE, true); send(KeyEvent.KEYCODE_SPACE, false)
            if (!superHeld) send(KeyEvent.KEYCODE_META_LEFT, false)
            releaseOnce()
        }

        /** Releases every held modifier in X (e.g. when the display goes away), so no key stays stuck down. */
        fun releaseAll() {
            for ((code, m) in mods) if (m != Mod.OFF) { send(code, false); mods[code] = Mod.OFF }
        }

        private fun releaseOnce() {
            for ((code, m) in mods) if (m == Mod.ONCE) { send(code, false); mods[code] = Mod.OFF }
        }
    }

    val keys = Keys(send)
    private val modViews = HashMap<Int, TextView>()
    private val ui = Handler(Looper.getMainLooper())

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(BAR_BG)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
        row.addView(key("Super+Space", wide = true, accent = true) { keys.superSpace() })
        row.addView(modifier("Super", KeyEvent.KEYCODE_META_LEFT))
        row.addView(key("Space", wide = true) { keys.tapKey(KeyEvent.KEYCODE_SPACE) })
        row.addView(key("Esc") { keys.tapKey(KeyEvent.KEYCODE_ESCAPE) })
        row.addView(key("Tab") { keys.tapKey(KeyEvent.KEYCODE_TAB) })
        row.addView(modifier("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT))
        row.addView(modifier("Alt", KeyEvent.KEYCODE_ALT_LEFT))
        row.addView(modifier("Shift", KeyEvent.KEYCODE_SHIFT_LEFT))
        row.addView(key("Enter") { keys.tapKey(KeyEvent.KEYCODE_ENTER) })
        row.addView(key("←") { keys.tapKey(KeyEvent.KEYCODE_DPAD_LEFT) })
        row.addView(key("↑") { keys.tapKey(KeyEvent.KEYCODE_DPAD_UP) })
        row.addView(key("↓") { keys.tapKey(KeyEvent.KEYCODE_DPAD_DOWN) })
        row.addView(key("→") { keys.tapKey(KeyEvent.KEYCODE_DPAD_RIGHT) })
        addView(row)
    }

    override fun onDetachedFromWindow() {
        keys.releaseAll()
        super.onDetachedFromWindow()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun button(label: String, wide: Boolean, accent: Boolean): TextView = TextView(context).apply {
        text = label
        setTextColor(if (accent) BG else FG)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = dp(if (wide) 96 else 52)
        minHeight = dp(44)
        setPadding(dp(10), 0, dp(10), 0)
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(if (accent) ACCENT else KEY_BG) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            marginStart = dp(3); marginEnd = dp(3)
        }
        isHapticFeedbackEnabled = true
    }

    private fun key(label: String, wide: Boolean = false, accent: Boolean = false, onTap: () -> Unit): View =
        button(label, wide, accent).apply {
            setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); onTap(); refresh() }
        }

    /** Tap = sticky (next key), double-tap = lock; press-and-hold = held while the finger is down (chords). */
    private fun modifier(label: String, code: Int): View = button(label, wide = false, accent = false).apply {
        modViews[code] = this
        var holding = false
        val hold = Runnable { holding = true; keys.hold(code); refresh() }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { holding = false; ui.postDelayed(hold, HOLD_MS); true }
                MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(hold)
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    if (holding) keys.release(code) else keys.tapModifier(code, e.eventTime)
                    refresh(); true
                }
                MotionEvent.ACTION_CANCEL -> { ui.removeCallbacks(hold); true }
                else -> true
            }
        }
    }

    private fun refresh() {
        for ((code, v) in modViews) {
            val m = keys.mods[code] ?: Mod.OFF
            (v.background as? GradientDrawable)?.setColor(when (m) { Mod.OFF -> KEY_BG; Mod.ONCE -> ACCENT_DIM; Mod.LOCKED -> ACCENT })
            v.setTextColor(if (m == Mod.OFF) FG else BG)
        }
    }

    companion object {
        const val DOUBLE_TAP_MS = 350L
        const val HOLD_MS = 400L
        // Omarchy tokyo-night, like the setup screen.
        private val BAR_BG = Color.parseColor("#E61a1b26")
        private val KEY_BG = Color.parseColor("#24283b")
        private val FG = Color.parseColor("#c0caf5")
        private val BG = Color.parseColor("#1a1b26")
        private val ACCENT = Color.parseColor("#7aa2f7")
        private val ACCENT_DIM = Color.parseColor("#9ece6a")
    }
}
