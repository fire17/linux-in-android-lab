package com.fire17.linuxdesk.x11

import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_POINTER_UP
import android.view.MotionEvent.ACTION_UP
import com.fire17.linuxdesk.x11.DesktopGestures.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure gesture state machine: long-press timing, 2-finger scroll deltas, 3-finger swipe direction. */
class DesktopGesturesTest {

    private val calls = ArrayList<String>()
    private val out = object : DesktopGestures.Output {
        override fun rightClick(x: Float, y: Float) { calls += "right ${x.toInt()},${y.toInt()}" }
        override fun scroll(x: Float, y: Float, dx: Float, dy: Float) { calls += "scroll ${dx.toInt()},${dy.toInt()}" }
        override fun workspace(next: Boolean) { calls += if (next) "ws next" else "ws prev" }
    }
    // slop 10 px, one wheel click per 40 px of finger travel, 3-finger swipe after 100 px.
    private val m = DesktopGestures.Machine(out, slop = 10f, clickPx = 40f, swipePx = 100f)

    private fun ev(action: Int, t: Long, vararg p: Pair<Float, Float>): Verdict =
        m.onEvent(action, t, FloatArray(p.size) { p[it].first }, FloatArray(p.size) { p[it].second }, p.size)

    @Test fun tapGoesToLorieAsBefore() {
        assertEquals(Verdict.HOLD, ev(ACTION_DOWN, 0, 100f to 100f))
        assertEquals(Verdict.FLUSH, ev(ACTION_UP, 120, 100f to 100f))
        assertTrue(calls.isEmpty())
    }

    @Test fun dragPastSlopIsFlushedThenPassed() {
        ev(ACTION_DOWN, 0, 100f to 100f)
        assertEquals(Verdict.HOLD, ev(ACTION_MOVE, 10, 104f to 103f))     // inside the slop
        assertEquals(Verdict.FLUSH, ev(ACTION_MOVE, 20, 130f to 100f))
        assertEquals(Verdict.PASS, ev(ACTION_MOVE, 30, 160f to 100f))
        assertEquals(Verdict.PASS, ev(ACTION_UP, 40, 160f to 100f))
        assertFalse(m.onLongPress(1000))                                // a drag is never a long-press
        assertTrue(calls.isEmpty())
    }

    @Test fun longPressIsARightClickAtTheTouchPoint() {
        ev(ACTION_DOWN, 1000, 200f to 300f)
        ev(ACTION_MOVE, 1100, 203f to 302f)                              // a small tremble is fine
        assertFalse(m.onLongPress(1000 + DesktopGestures.LONG_PRESS_MS - 1))   // too early
        assertTrue(m.onLongPress(1000 + DesktopGestures.LONG_PRESS_MS))
        assertEquals(listOf("right 200,300"), calls)
        assertEquals(Verdict.CONSUME, ev(ACTION_MOVE, 1500, 260f to 300f))     // the rest is dropped
        assertEquals(Verdict.CONSUME, ev(ACTION_UP, 1600, 260f to 300f))
        assertFalse(m.onLongPress(2000))
        assertEquals(1, calls.size)
    }

    @Test fun releaseBeforeLongPressIsATap() {
        ev(ACTION_DOWN, 0, 50f to 50f)
        assertEquals(Verdict.FLUSH, ev(ACTION_UP, DesktopGestures.LONG_PRESS_MS - 50, 50f to 50f))
        assertFalse(m.onLongPress(DesktopGestures.LONG_PRESS_MS))
        assertTrue(calls.isEmpty())
    }

    @Test fun twoFingerVerticalDragScrollsWithAccumulatedDistance() {
        ev(ACTION_DOWN, 0, 100f to 500f)
        assertEquals(Verdict.HOLD, ev(ACTION_POINTER_DOWN, 10, 100f to 500f, 200f to 500f))
        // Both fingers up 20 px: past the slop, axis = vertical, 20 px = half a click (60 units), wheel down.
        assertEquals(Verdict.CONSUME, ev(ACTION_MOVE, 20, 100f to 480f, 200f to 480f))
        assertEquals(Verdict.CONSUME, ev(ACTION_MOVE, 30, 102f to 420f, 202f to 420f))   // +60 px = 180 units
        assertEquals(listOf("scroll 0,60", "scroll 0,180"), calls)
        assertEquals(240f, m.scrolledY, 0.01f)                           // 80 px = 2 clicks
        assertEquals(0f, m.scrolledX, 0.01f)                             // locked to the vertical axis
        assertFalse(m.onLongPress(5000))
        ev(ACTION_POINTER_UP, 40, 102f to 420f, 202f to 420f)
        assertEquals(Verdict.CONSUME, ev(ACTION_UP, 50, 102f to 420f))
    }

    @Test fun twoFingerDownDragScrollsUpAndHorizontalDragScrollsSideways() {
        ev(ACTION_DOWN, 0, 100f to 100f); ev(ACTION_POINTER_DOWN, 5, 100f to 100f, 200f to 100f)
        ev(ACTION_MOVE, 10, 100f to 140f, 200f to 140f)
        assertEquals(listOf("scroll 0,-120"), calls)                     // fingers down = content down = wheel up
        ev(ACTION_UP, 20, 100f to 140f)

        calls.clear()
        ev(ACTION_DOWN, 100, 100f to 100f); ev(ACTION_POINTER_DOWN, 105, 100f to 100f, 200f to 100f)
        ev(ACTION_MOVE, 110, 60f to 102f, 160f to 102f)
        assertEquals(listOf("scroll 120,0"), calls)                      // fingers left = wheel right (button 7)
    }

    @Test fun liftingOneFingerMidScrollDoesNotJump() {
        ev(ACTION_DOWN, 0, 100f to 500f); ev(ACTION_POINTER_DOWN, 5, 100f to 500f, 200f to 500f)
        ev(ACTION_MOVE, 10, 100f to 460f, 200f to 460f)                  // 120 units
        ev(ACTION_POINTER_UP, 15, 100f to 460f, 200f to 460f)
        ev(ACTION_MOVE, 20, 100f to 460f)                                // centroid jumps 50 px: rebased, no scroll
        ev(ACTION_MOVE, 30, 100f to 420f)                                // 40 px more = 120 units
        assertEquals(listOf("scroll 0,120", "scroll 0,120"), calls)
    }

    @Test fun pinchIsLeftToLorieAndChromium() {
        ev(ACTION_DOWN, 0, 200f to 400f); ev(ACTION_POINTER_DOWN, 5, 200f to 400f, 300f to 400f)
        assertEquals(Verdict.FLUSH, ev(ACTION_MOVE, 10, 170f to 400f, 330f to 400f))     // spread +60, centroid fixed
        assertEquals(Verdict.PASS, ev(ACTION_MOVE, 20, 150f to 400f, 350f to 400f))
        assertTrue(calls.isEmpty())
    }

    @Test fun threeFingerSwipeLeftIsNextWorkspaceAndRightIsPrevious() {
        ev(ACTION_DOWN, 0, 300f to 300f)
        ev(ACTION_POINTER_DOWN, 5, 300f to 300f, 350f to 300f)
        assertEquals(Verdict.HOLD, ev(ACTION_POINTER_DOWN, 8, 300f to 300f, 350f to 300f, 400f to 300f))
        assertEquals(Verdict.HOLD, ev(ACTION_MOVE, 10, 250f to 305f, 300f to 305f, 350f to 305f))   // 50 px: not yet
        assertEquals(Verdict.CONSUME, ev(ACTION_MOVE, 20, 180f to 310f, 230f to 310f, 280f to 310f))
        assertEquals(Verdict.CONSUME, ev(ACTION_MOVE, 30, 50f to 310f, 100f to 310f, 150f to 310f))  // once per swipe
        assertEquals(Verdict.CONSUME, ev(ACTION_UP, 40, 50f to 310f))
        assertEquals(listOf("ws next"), calls)

        calls.clear()
        ev(ACTION_DOWN, 100, 100f to 300f); ev(ACTION_POINTER_DOWN, 105, 100f to 300f, 150f to 300f)
        ev(ACTION_POINTER_DOWN, 108, 100f to 300f, 150f to 300f, 200f to 300f)
        ev(ACTION_MOVE, 110, 220f to 300f, 270f to 300f, 320f to 300f)
        assertEquals(listOf("ws prev"), calls)
    }

    @Test fun threeFingerVerticalSwipeStaysLoriesKeyBarToggle() {
        ev(ACTION_DOWN, 0, 300f to 300f); ev(ACTION_POINTER_DOWN, 5, 300f to 300f, 350f to 300f)
        ev(ACTION_POINTER_DOWN, 8, 300f to 300f, 350f to 300f, 400f to 300f)
        assertEquals(Verdict.FLUSH, ev(ACTION_MOVE, 10, 302f to 340f, 352f to 340f, 402f to 340f))
        assertEquals(Verdict.PASS, ev(ACTION_MOVE, 20, 302f to 400f, 352f to 400f, 402f to 400f))
        assertTrue(calls.isEmpty())
    }

    @Test fun cancelBeforeTheGestureIsKnownNeverReachesLorie() {
        ev(ACTION_DOWN, 0, 10f to 10f)
        assertEquals(Verdict.CONSUME, ev(ACTION_CANCEL, 5, 10f to 10f))
        assertEquals(DesktopGestures.Machine.State.IDLE, m.state)
    }
}
