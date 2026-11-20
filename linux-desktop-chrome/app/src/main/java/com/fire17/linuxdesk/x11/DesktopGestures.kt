package com.fire17.linuxdesk.x11

import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Desktop touch gestures for the Linux display (Tami 2026-11-19 22:18: "למה אין לי טאץ' סאפורט טוב?").
 *
 * lorie runs in Direct touch mode (touchMode 3): fingers reach X as raw XI2 touches. Chromium handles those
 * itself, but every other X app only gets X's pointer emulation of the FIRST finger. So a 2-finger drag did not
 * scroll the Omarchy terminal, a long-press did nothing (lorie sends its long-press to a no-op strategy), and
 * nothing switched i3 workspaces. This class runs before lorie (from DesktopActivity.dispatchTouchEvent) and adds:
 *  - long-press (no movement, [LONG_PRESS_MS]) = a right click at that point;
 *  - 2-finger drag = X scroll wheel at the fingers (lorie's BUTTON_SCROLL: 120 units = one wheel click, which X
 *    delivers as smooth XI2 scroll and as legacy buttons 4/5/6/7), locked to the axis the drag starts in;
 *  - 3-finger horizontal swipe = the previous/next workspace (Super+Shift+Tab / Super+Tab, the bindings in
 *    omarchy-theme.py and sway-theme.py; Super+Left/Right is "focus left/right" there);
 *  - everything else unchanged: a 1-finger tap or drag, a 2-finger pinch and a 3-finger vertical swipe are
 *    replayed to lorie exactly as they arrived.
 *
 * Sway sessions ([pointerMode], Tami 2026-11-20 01:38: "I still can't click … can't drag windows"): sway runs as
 * an X client of lorie (wlroots x11 backend) and does not act on lorie's XI2 touches, while an X POINTER click on
 * its dock was verified to work. So there one finger is a mouse and never reaches lorie as a touch:
 *  - tap = left click at the finger (pointer moved there, button 1 down/up);
 *  - drag = scroll wheel at the finger, axis-locked like the 2-finger scroll (Chrome pages and the terminal scroll);
 *  - long-press, then release without moving = right click;
 *  - long-press, then drag = move the window: Super + button 1 held while the pointer follows the finger, released
 *    on lift (sway-theme.py: "floating_modifier $mod normal", so this moves floating and tiled windows alike).
 * The cost: no text selection by dragging and no Chromium touch scrolling (fling) on sway.
 *
 * Magnifier ([magnifier]; default ON on sway, OFF on i3, see LinuxDeskSettings.magnifier): a 2-finger pinch zooms
 * the picture like a magnifying glass ([Magnifier], lorie's own renderer zoom; the X resolution is unchanged); while
 * zoomed a 2-finger drag pans instead of scrolling, and a 2-finger double-tap (or a pinch ending at ≤ 105%) goes back
 * to 1:1. Off = a pinch is replayed to lorie as before (on i3 that is Chromium's own page pinch-zoom, which is why it
 * is off there by default). Taps and clicks stay right while zoomed: they are mapped through LorieView's transform,
 * which lorie rebuilds for every zoomed crop.
 *
 * How: the first events of a touch are held back until the gesture is known. A tap, a drag, a pinch or a
 * vertical 3-finger swipe then gets the held events replayed to lorie, followed by the rest; a long-press,
 * a 2-finger scroll or a horizontal 3-finger swipe never reaches lorie at all. So there is never a half gesture
 * to cancel in X. The cost is the delay of a few pixels (the touch slop) before a 1-finger drag starts.
 *
 * Trade-off in Chromium (it cannot be told apart from other X windows here): a 2-finger drag scrolls with wheel
 * events instead of Chromium's own touch scroll (no fling), and a long-press gives Chromium's right-click menu
 * instead of its touch long-press (link menu, but no text selection handles). A 1-finger drag and pinch-zoom
 * stay Chromium's own on i3.
 *
 * lorie is reached only through the injected calls (LorieView.sendMouseEvent / sendMouseWheelEvent /
 * sendKeyEvent and the zoom [Magnifier.Lens]), like DesktopKeyBar, and [Machine] and [Magnifier] are pure, so the
 * gesture logic is unit-tested on the JVM.
 */
class DesktopGestures(
    /** LorieView: its position, density and haptics, and (by reflection) its view → X screen transform. */
    private val display: View,
    /** LorieView.sendMouseEvent(x, y, button, down, relative). */
    private val mouse: (x: Float, y: Float, button: Int, down: Boolean, relative: Boolean) -> Unit,
    /** LorieView.sendMouseWheelEvent(dx, dy), in 1/120 wheel clicks. */
    private val wheel: (dx: Float, dy: Float) -> Unit,
    /** LorieView.sendKeyEvent(0, keyCode, down). */
    private val key: (keyCode: Int, down: Boolean) -> Boolean,
    /** Hands an event on to lorie unchanged (Activity's super.dispatchTouchEvent). */
    private val pass: (MotionEvent) -> Boolean,
    /** LorieView's renderer zoom; null = no magnifier (a pinch always goes to lorie). */
    lens: Magnifier.Lens? = null,
) {

    /** What to do with one touch event. */
    enum class Verdict {
        /** Keep it back: the gesture is not known yet. */
        HOLD,
        /** Replay the held events to lorie, then this one, and pass the rest of the touch. */
        FLUSH,
        /** Hand it to lorie. */
        PASS,
        /** Ours: drop it (and anything held). */
        CONSUME,
    }

    /** What the gestures do, in window pixels (the adapter maps them to the X screen). */
    interface Output {
        fun rightClick(x: Float, y: Float)
        /** Wheel at (x, y): [dx]/[dy] in 1/120 clicks, positive = right/down (content follows the finger). */
        fun scroll(x: Float, y: Float, dx: Float, dy: Float)
        fun workspace(next: Boolean)

        // Pointer mode (sway).
        /** A left click at (x, y). */
        fun click(x: Float, y: Float) {}
        /** The long-press fired in pointer mode: a lift now is a right click, a drag moves the window. */
        fun armed(x: Float, y: Float) {}
        /** Window move starts at (x, y): Super + button 1 down there. */
        fun dragStart(x: Float, y: Float) {}
        fun dragMove(x: Float, y: Float) {}
        /** Button 1 + Super up. Always follows a [dragStart], also when the touch is cancelled. */
        fun dragEnd() {}

        // Magnifier.
        /** True while the picture is zoomed in (a 2-finger drag then pans instead of scrolling). */
        val zoomed: Boolean get() = false
        /** A pinch/pan starts (or its finger count changed) with the fingers' centre at (x, y). */
        fun zoomBegin(x: Float, y: Float) {}
        /** The spread changed by [factor] (1 = pure pan) and the centre is now at (x, y). */
        fun zoom(factor: Float, x: Float, y: Float) {}
        fun zoomEnd() {}
        /** Back to 1:1 (2-finger double-tap). */
        fun zoomReset() {}
    }

    /**
     * Pure gesture state machine. Feed it every event of a touch: [onEvent] with the masked action and the
     * positions of the pointers in the event; [onLongPress] when [LONG_PRESS_MS] passed since ACTION_DOWN.
     */
    class Machine(
        private val out: Output,
        /** Movement below this is not a drag (px). */
        private val slop: Float,
        /** Finger travel for one wheel click (px). */
        private val clickPx: Float,
        /** Horizontal travel of a 3-finger swipe (px). */
        private val swipePx: Float,
    ) {
        enum class State { IDLE, PENDING, PASS, LONG, TWO, SCROLL, THREE, SWIPED, ARMED, WINDOW, ZOOM, DONE }

        /** Sway: one finger is a mouse (tap = click, drag = wheel, long-press = right click / window move). */
        var pointer = false
        /** A pinch zooms the picture (Output.zoom*) instead of going to lorie. */
        var magnify = false

        var state = State.IDLE
            private set
        /** Total wheel units sent in this touch (for tests and logs). */
        var scrolledX = 0f
            private set
        var scrolledY = 0f
            private set

        private var downTime = 0L
        private var x0 = 0f; private var y0 = 0f           // the first finger, at ACTION_DOWN
        private var cx0 = 0f; private var cy0 = 0f         // centroid when the current finger count started
        private var spread0 = 0f
        private var lastX = 0f; private var lastY = 0f     // last centroid while scrolling
        private var lastSpread = 0f                         // last spread while zooming
        private var rebase = false                          // finger count changed while scrolling / zooming
        private var vertical = true
        private var lastTwoTap = NEVER                      // when the last 2-finger tap ended

        fun onEvent(action: Int, time: Long, xs: FloatArray, ys: FloatArray, count: Int): Verdict {
            if (action == MotionEvent.ACTION_DOWN) {
                state = State.PENDING; downTime = time; x0 = xs[0]; y0 = ys[0]
                scrolledX = 0f; scrolledY = 0f; rebase = false
                return Verdict.HOLD
            }
            val end = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
            val verdict = when (state) {
                State.IDLE, State.PASS -> Verdict.PASS
                State.LONG, State.SWIPED, State.DONE -> Verdict.CONSUME
                State.PENDING -> pending(action, xs, ys, count)
                State.TWO -> two(action, time, xs, ys, count)
                State.SCROLL -> scroll(action, xs, ys, count)
                State.THREE -> three(action, xs, ys, count)
                State.ARMED -> armed(action, xs, ys)
                State.WINDOW -> window(action, xs, ys)
                State.ZOOM -> zoom(action, xs, ys, count)
            }
            if (end) state = State.IDLE
            // A cancelled touch that lorie never saw stays unseen.
            return if (action == MotionEvent.ACTION_CANCEL && verdict == Verdict.FLUSH) Verdict.CONSUME else verdict
        }

        /** The long-press timer fired. True = it was a long-press (it is ours; drop what is held). */
        fun onLongPress(time: Long): Boolean {
            if (state != State.PENDING || time - downTime < LONG_PRESS_MS) return false
            if (pointer) {
                state = State.ARMED   // right click or window move: the lift or the next drag decides
                out.armed(x0, y0)
            } else {
                state = State.LONG
                out.rightClick(x0, y0)
            }
            return true
        }

        private fun pending(action: Int, xs: FloatArray, ys: FloatArray, count: Int): Verdict = when (action) {
            MotionEvent.ACTION_MOVE -> when {
                hypot(xs[0] - x0, ys[0] - y0) <= slop -> Verdict.HOLD
                pointer -> {   // 1-finger drag = wheel, like the 2-finger scroll
                    state = State.SCROLL
                    vertical = abs(ys[0] - y0) >= abs(xs[0] - x0)
                    lastX = x0; lastY = y0
                    emitScroll(xs[0], ys[0])
                    Verdict.CONSUME
                }
                else -> { state = State.PASS; Verdict.FLUSH }
            }
            MotionEvent.ACTION_POINTER_DOWN -> { state = State.TWO; baseline(xs, ys, count); Verdict.HOLD }
            MotionEvent.ACTION_UP ->
                if (pointer) { out.click(x0, y0); Verdict.CONSUME }
                else { state = State.PASS; Verdict.FLUSH }   // a tap goes to lorie as before
            else -> if (pointer) Verdict.CONSUME else { state = State.PASS; Verdict.FLUSH }
        }

        private fun armed(action: Int, xs: FloatArray, ys: FloatArray): Verdict {
            when (action) {
                MotionEvent.ACTION_MOVE -> if (hypot(xs[0] - x0, ys[0] - y0) > slop) {
                    state = State.WINDOW
                    out.dragStart(x0, y0)
                    out.dragMove(xs[0], ys[0])
                }
                MotionEvent.ACTION_UP -> out.rightClick(x0, y0)
            }
            return Verdict.CONSUME   // a cancelled long-press sends nothing
        }

        private fun window(action: Int, xs: FloatArray, ys: FloatArray): Verdict {
            when (action) {
                MotionEvent.ACTION_MOVE -> out.dragMove(xs[0], ys[0])
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> out.dragEnd()
            }
            return Verdict.CONSUME
        }

        private fun two(action: Int, time: Long, xs: FloatArray, ys: FloatArray, count: Int): Verdict = when (action) {
            MotionEvent.ACTION_MOVE -> {
                val cx = cx(xs, count); val cy = cy(ys, count)
                val moved = hypot(cx - cx0, cy - cy0)
                val pinch = abs(spread(xs, ys, count) - spread0)
                when {
                    pinch > slop * 2 && pinch > moved ->
                        if (magnify) startZoom(xs, ys, count)
                        else { state = State.PASS; Verdict.FLUSH }   // lorie/Chromium pinch
                    moved > slop ->
                        if (magnify && out.zoomed) startZoom(xs, ys, count)   // zoomed: a drag pans the magnifier
                        else {
                            state = State.SCROLL
                            vertical = abs(cy - cy0) >= abs(cx - cx0)
                            lastX = cx0; lastY = cy0
                            emitScroll(cx, cy)
                            Verdict.CONSUME
                        }
                    else -> Verdict.HOLD
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> { state = State.THREE; baseline(xs, ys, count); Verdict.HOLD }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> twoTap(time)
            else -> { state = State.PASS; Verdict.FLUSH }
        }

        /** A 2-finger tap goes to lorie as before; the second of two quick ones while zoomed resets the zoom. */
        private fun twoTap(time: Long): Verdict {
            val double = magnify && out.zoomed && time - lastTwoTap <= DOUBLE_TAP_MS
            lastTwoTap = if (double) NEVER else time
            if (!double) { state = State.PASS; return Verdict.FLUSH }
            state = State.DONE
            out.zoomReset()
            return Verdict.CONSUME
        }

        /** Pinch or pan: the X point under the fingers' first centre follows the fingers. */
        private fun startZoom(xs: FloatArray, ys: FloatArray, count: Int): Verdict {
            state = State.ZOOM
            val s = spread(xs, ys, count)
            out.zoomBegin(cx0, cy0)
            out.zoom(if (spread0 > 0f) s / spread0 else 1f, cx(xs, count), cy(ys, count))
            lastSpread = s
            return Verdict.CONSUME
        }

        private fun zoom(action: Int, xs: FloatArray, ys: FloatArray, count: Int): Verdict {
            when (action) {
                MotionEvent.ACTION_MOVE -> if (count >= 2) {
                    val cx = cx(xs, count); val cy = cy(ys, count); val s = spread(xs, ys, count)
                    // After a finger came or went the centre jumps: refocus there instead of panning by the jump.
                    if (rebase) { rebase = false; out.zoomBegin(cx, cy) }
                    else out.zoom(if (lastSpread > 0f) s / lastSpread else 1f, cx, cy)
                    lastSpread = s
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> rebase = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> out.zoomEnd()
            }
            return Verdict.CONSUME
        }

        private fun scroll(action: Int, xs: FloatArray, ys: FloatArray, count: Int): Verdict {
            when (action) {
                MotionEvent.ACTION_MOVE -> {
                    val cx = cx(xs, count); val cy = cy(ys, count)
                    if (rebase) { lastX = cx; lastY = cy; rebase = false } else emitScroll(cx, cy)
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> rebase = true
            }
            return Verdict.CONSUME
        }

        private fun three(action: Int, xs: FloatArray, ys: FloatArray, count: Int): Verdict = when (action) {
            MotionEvent.ACTION_MOVE -> {
                val dx = cx(xs, count) - cx0; val dy = cy(ys, count) - cy0
                when {
                    abs(dx) > swipePx && abs(dx) > abs(dy) * 1.5f -> {
                        state = State.SWIPED
                        out.workspace(next = dx < 0)   // swipe left = next, like turning a page
                        Verdict.CONSUME
                    }
                    abs(dy) > slop * 2 && abs(dy) > abs(dx) * 1.5f -> { state = State.PASS; Verdict.FLUSH }   // lorie's own
                    else -> Verdict.HOLD
                }
            }
            else -> { state = State.PASS; Verdict.FLUSH }
        }

        private fun emitScroll(cx: Float, cy: Float) {
            // Finger up = content up = wheel down, as on a phone. Locked to the axis the drag started in.
            val dx = if (vertical) 0f else (lastX - cx) * 120f / clickPx
            val dy = if (vertical) (lastY - cy) * 120f / clickPx else 0f
            lastX = cx; lastY = cy
            if (dx == 0f && dy == 0f) return
            scrolledX += dx; scrolledY += dy
            out.scroll(cx, cy, dx, dy)
        }

        private fun baseline(xs: FloatArray, ys: FloatArray, count: Int) {
            cx0 = cx(xs, count); cy0 = cy(ys, count); spread0 = spread(xs, ys, count)
        }

        private fun cx(xs: FloatArray, n: Int): Float { var s = 0f; for (i in 0 until n) s += xs[i]; return s / n }
        private fun cy(ys: FloatArray, n: Int): Float { var s = 0f; for (i in 0 until n) s += ys[i]; return s / n }
        private fun spread(xs: FloatArray, ys: FloatArray, n: Int): Float =
            if (n < 2) 0f else hypot(xs[1] - xs[0], ys[1] - ys[0])

        private companion object {
            const val NEVER = Long.MIN_VALUE / 2
        }
    }

    /** "Desktop gestures" setting; off = every event goes to lorie unchanged. */
    var enabled = true
    /** True for window points covered by an overlay whose touches are its own (beyond any clickable view there). */
    var covered: (x: Float, y: Float) -> Boolean = { _, _ -> false }
    /** Sway session: one finger is a mouse (see the class comment). */
    var pointerMode: Boolean
        get() = machine.pointer
        set(v) { machine.pointer = v }
    /** "Magnifier" setting (needs a lens); turning it off goes back to 1:1. */
    var magnifier: Boolean
        get() = machine.magnify
        set(v) { machine.magnify = v && glass != null; if (!v && glass?.zoomed == true) glass.reset() }

    private val density = display.resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())
    private val held = ArrayList<MotionEvent>()
    private var tracking = false
    private var dragging = false   // Super + button 1 are down in X (window move)
    private var xs = FloatArray(10); private var ys = FloatArray(10)
    private val loc = IntArray(2)
    private val pt = FloatArray(2)
    private val transform: Matrix? = findTransform(display)
    private val glass: Magnifier? = lens?.let { Magnifier(it) }

    private val machine = Machine(object : Output {
        override fun rightClick(x: Float, y: Float) {
            display.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            moveTo(x, y)
            mouse(0f, 0f, BUTTON_RIGHT, true, false)
            mouse(0f, 0f, BUTTON_RIGHT, false, false)
        }

        override fun scroll(x: Float, y: Float, dx: Float, dy: Float) {
            moveTo(x, y)   // the wheel goes to the window under the fingers
            wheel(dx, dy)
        }

        override fun workspace(next: Boolean) {
            display.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            key(KeyEvent.KEYCODE_META_LEFT, true)
            if (!next) key(KeyEvent.KEYCODE_SHIFT_LEFT, true)
            key(KeyEvent.KEYCODE_TAB, true); key(KeyEvent.KEYCODE_TAB, false)
            if (!next) key(KeyEvent.KEYCODE_SHIFT_LEFT, false)
            key(KeyEvent.KEYCODE_META_LEFT, false)
        }

        override fun click(x: Float, y: Float) {
            moveTo(x, y)
            mouse(0f, 0f, BUTTON_LEFT, true, false)
            mouse(0f, 0f, BUTTON_LEFT, false, false)
        }

        override fun armed(x: Float, y: Float) {
            display.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        override fun dragStart(x: Float, y: Float) {
            if (dragging) return
            dragging = true
            moveTo(x, y)
            key(KeyEvent.KEYCODE_META_LEFT, true)   // sway: floating_modifier $mod
            mouse(0f, 0f, BUTTON_LEFT, true, false)
        }

        override fun dragMove(x: Float, y: Float) {
            if (dragging) moveTo(x, y)
        }

        override fun dragEnd() {
            if (!dragging) return
            dragging = false
            mouse(0f, 0f, BUTTON_LEFT, false, false)
            key(KeyEvent.KEYCODE_META_LEFT, false)
        }

        override val zoomed: Boolean get() = glass?.zoomed == true

        override fun zoomBegin(x: Float, y: Float) { glass?.begin(localX(x), localY(y)) }

        override fun zoom(factor: Float, x: Float, y: Float) { glass?.step(factor, localX(x), localY(y)) }

        override fun zoomEnd() { glass?.end() }

        override fun zoomReset() {
            display.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            glass?.reset()
        }
    }, slop = ViewConfiguration.get(display.context).scaledTouchSlop.toFloat(), clickPx = 40 * density,
        swipePx = 72 * density)

    private val longPress = Runnable {
        if (machine.onLongPress(android.os.SystemClock.uptimeMillis())) drop()
    }

    /** Call from Activity.dispatchTouchEvent for every touch event; returns what that should return. */
    fun dispatch(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            drop()
            tracking = enabled && display.isAttachedToWindow && display.visibility == View.VISIBLE &&
                ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && inside(display, ev.x, ev.y) &&
                !covered(ev.x, ev.y) && !buttonAt(display.rootView, ev.x, ev.y)
        }
        if (!tracking) return pass(ev)

        val n = ev.pointerCount
        if (xs.size < n) { xs = FloatArray(n); ys = FloatArray(n) }
        for (i in 0 until n) { xs[i] = ev.getX(i); ys[i] = ev.getY(i) }
        val verdict = machine.onEvent(action, ev.eventTime, xs, ys, n)
        if (action == MotionEvent.ACTION_DOWN) ui.postDelayed(longPress, LONG_PRESS_MS)
        else if (verdict != Verdict.HOLD) ui.removeCallbacks(longPress)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) tracking = false

        return when (verdict) {
            Verdict.HOLD -> { held.add(MotionEvent.obtain(ev)); true }
            Verdict.FLUSH -> {
                for (e in held) { pass(e); e.recycle() }
                held.clear()
                pass(ev)
            }
            Verdict.PASS -> pass(ev)
            Verdict.CONSUME -> { drop(); true }
        }
    }

    /** The display stops being shown: never leave Super or button 1 held down in X. The touch is dropped. */
    fun release() {
        drop()
        tracking = false
        if (dragging) {
            dragging = false
            mouse(0f, 0f, BUTTON_LEFT, false, false)
            key(KeyEvent.KEYCODE_META_LEFT, false)
        }
    }

    private fun drop() {
        ui.removeCallbacks(longPress)
        for (e in held) e.recycle()
        held.clear()
    }

    private fun inside(v: View, x: Float, y: Float): Boolean {
        v.getLocationInWindow(loc)
        return x >= loc[0] && y >= loc[1] && x < loc[0] + v.width && y < loc[1] + v.height
    }

    /** A visible clickable view at (x, y) that is not the display itself: a button over it (ours or lorie's). */
    private fun buttonAt(v: View, x: Float, y: Float): Boolean {
        if (v.visibility != View.VISIBLE || v === display || !inside(v, x, y)) return false
        if ((v.isClickable || v.isLongClickable) && !holdsDisplay(v)) return true
        if (v is android.view.ViewGroup) for (i in v.childCount - 1 downTo 0) if (buttonAt(v.getChildAt(i), x, y)) return true
        return false
    }

    /** True if [v] is the display's ancestor (a clickable container under it is not a button over it). */
    private fun holdsDisplay(v: View): Boolean {
        var p = display.parent
        while (p != null) { if (p === v) return true; p = p.parent }
        return false
    }

    /** Window → LorieView pixels (what lorie's viewport and zoom API use). */
    private fun localX(x: Float): Float { display.getLocationInWindow(loc); return x - loc[0] }
    private fun localY(y: Float): Float { display.getLocationInWindow(loc); return y - loc[1] }

    /** Moves the X pointer to a window point (absolute), through LorieView's own view → X screen transform. */
    private fun moveTo(x: Float, y: Float) {
        display.getLocationInWindow(loc)
        pt[0] = x - loc[0]; pt[1] = y - loc[1]
        transform?.mapPoints(pt)
        mouse(maxOf(0f, pt[0]), maxOf(0f, pt[1]), BUTTON_UNDEFINED, false, false)
    }

    companion object {
        const val LONG_PRESS_MS = 450L
        /** Two 2-finger taps whose lifts are at most this far apart are a double-tap (ms). */
        const val DOUBLE_TAP_MS = 400L
        // lorie's InputStub button numbers.
        private const val BUTTON_UNDEFINED = 0
        private const val BUTTON_LEFT = 1
        private const val BUTTON_RIGHT = 3

        /**
         * LorieView keeps its view → X screen matrix in a private field (rebuilt in place whenever the viewport
         * or the zoomed crop changes). Without it (renamed upstream), view pixels are used as is: the X screen is
         * the view size.
         */
        private fun findTransform(v: View): Matrix? {
            var c: Class<*>? = v.javaClass
            while (c != null) {
                try {
                    val f = c.getDeclaredField("inputTransform")
                    f.isAccessible = true
                    return f.get(v) as? Matrix
                } catch (_: NoSuchFieldException) {
                    c = c.superclass
                } catch (_: Exception) {
                    return null
                }
            }
            return null
        }
    }
}
