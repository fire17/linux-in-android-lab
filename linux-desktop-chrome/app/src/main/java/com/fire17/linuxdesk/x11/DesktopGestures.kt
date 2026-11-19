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
 *  - 3-finger horizontal swipe = the previous/next i3 workspace (Super+Shift+Tab / Super+Tab, the bindings in
 *    omarchy-theme.py; Super+Left/Right is "focus left/right" there);
 *  - everything else unchanged: a 1-finger tap or drag, a 2-finger pinch and a 3-finger vertical swipe are
 *    replayed to lorie exactly as they arrived.
 *
 * How: the first events of a touch are held back until the gesture is known. A tap, a drag, a pinch or a
 * vertical 3-finger swipe then gets the held events replayed to lorie, followed by the rest; a long-press,
 * a 2-finger scroll or a horizontal 3-finger swipe never reaches lorie at all. So there is never a half gesture
 * to cancel in X. The cost is the delay of a few pixels (the touch slop) before a 1-finger drag starts.
 *
 * Trade-off in Chromium (it cannot be told apart from other X windows here): a 2-finger drag scrolls with wheel
 * events instead of Chromium's own touch scroll (no fling), and a long-press gives Chromium's right-click menu
 * instead of its touch long-press (link menu, but no text selection handles). A 1-finger drag and pinch-zoom
 * stay Chromium's own.
 *
 * lorie is reached only through the injected calls (LorieView.sendMouseEvent / sendMouseWheelEvent /
 * sendKeyEvent), like DesktopKeyBar, and [Machine] is pure, so the gesture logic is unit-tested on the JVM.
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
        enum class State { IDLE, PENDING, PASS, LONG, TWO, SCROLL, THREE, SWIPED }

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
        private var rebase = false                          // finger count changed while scrolling
        private var vertical = true

        fun onEvent(action: Int, time: Long, xs: FloatArray, ys: FloatArray, count: Int): Verdict {
            if (action == MotionEvent.ACTION_DOWN) {
                state = State.PENDING; downTime = time; x0 = xs[0]; y0 = ys[0]
                scrolledX = 0f; scrolledY = 0f
                return Verdict.HOLD
            }
            val end = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
            val verdict = when (state) {
                State.IDLE, State.PASS -> Verdict.PASS
                State.LONG, State.SWIPED -> Verdict.CONSUME
                State.PENDING -> pending(action, xs, ys, count)
                State.TWO -> two(action, xs, ys, count)
                State.SCROLL -> scroll(action, xs, ys, count)
                State.THREE -> three(action, xs, ys, count)
            }
            if (end) state = State.IDLE
            // A cancelled touch that lorie never saw stays unseen.
            return if (action == MotionEvent.ACTION_CANCEL && verdict == Verdict.FLUSH) Verdict.CONSUME else verdict
        }

        /** The long-press timer fired. True = it was a long-press (a right click was sent; drop what is held). */
        fun onLongPress(time: Long): Boolean {
            if (state != State.PENDING || time - downTime < LONG_PRESS_MS) return false
            state = State.LONG
            out.rightClick(x0, y0)
            return true
        }

        private fun pending(action: Int, xs: FloatArray, ys: FloatArray, count: Int): Verdict = when (action) {
            MotionEvent.ACTION_MOVE ->
                if (hypot(xs[0] - x0, ys[0] - y0) > slop) { state = State.PASS; Verdict.FLUSH } else Verdict.HOLD
            MotionEvent.ACTION_POINTER_DOWN -> { state = State.TWO; baseline(xs, ys, count); Verdict.HOLD }
            else -> { state = State.PASS; Verdict.FLUSH }   // a tap (UP) goes to lorie as before
        }

        private fun two(action: Int, xs: FloatArray, ys: FloatArray, count: Int): Verdict = when (action) {
            MotionEvent.ACTION_MOVE -> {
                val cx = cx(xs, count); val cy = cy(ys, count)
                val moved = hypot(cx - cx0, cy - cy0)
                val pinch = abs(spread(xs, ys, count) - spread0)
                when {
                    pinch > slop * 2 && pinch > moved -> { state = State.PASS; Verdict.FLUSH }   // lorie/Chromium pinch
                    moved > slop -> {
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
            else -> { state = State.PASS; Verdict.FLUSH }   // a 2-finger tap goes to lorie as before
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
    }

    /** "Desktop gestures" setting; off = every event goes to lorie unchanged. */
    var enabled = true
    /** True for window points covered by an overlay whose touches are its own (beyond any clickable view there). */
    var covered: (x: Float, y: Float) -> Boolean = { _, _ -> false }

    private val density = display.resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())
    private val held = ArrayList<MotionEvent>()
    private var tracking = false
    private var xs = FloatArray(10); private var ys = FloatArray(10)
    private val loc = IntArray(2)
    private val pt = FloatArray(2)
    private val transform: Matrix? = findTransform(display)

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

    /** Moves the X pointer to a window point (absolute), through LorieView's own view → X screen transform. */
    private fun moveTo(x: Float, y: Float) {
        display.getLocationInWindow(loc)
        pt[0] = x - loc[0]; pt[1] = y - loc[1]
        transform?.mapPoints(pt)
        mouse(maxOf(0f, pt[0]), maxOf(0f, pt[1]), BUTTON_UNDEFINED, false, false)
    }

    companion object {
        const val LONG_PRESS_MS = 450L
        // lorie's InputStub button numbers.
        private const val BUTTON_UNDEFINED = 0
        private const val BUTTON_RIGHT = 3

        /**
         * LorieView keeps its view → X screen matrix in a private field (rebuilt in place whenever the viewport
         * changes). Without it (renamed upstream), view pixels are used as is: the X screen is the view size.
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
