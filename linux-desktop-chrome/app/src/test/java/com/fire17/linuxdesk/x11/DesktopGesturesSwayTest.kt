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

/** The state machine's sway paths (one finger = mouse) and magnifier paths (pinch/pan/double-tap reset). */
class DesktopGesturesSwayTest {

    private val calls = ArrayList<String>()
    private var zoomedNow = false
    private val out = object : DesktopGestures.Output {
        override fun rightClick(x: Float, y: Float) { calls += "right ${x.toInt()},${y.toInt()}" }
        override fun scroll(x: Float, y: Float, dx: Float, dy: Float) { calls += "scroll ${dx.toInt()},${dy.toInt()}" }
        override fun workspace(next: Boolean) { calls += if (next) "ws next" else "ws prev" }
        override fun click(x: Float, y: Float) { calls += "click ${x.toInt()},${y.toInt()}" }
        override fun armed(x: Float, y: Float) { calls += "armed" }
        override fun dragStart(x: Float, y: Float) { calls += "dragStart ${x.toInt()},${y.toInt()}" }
        override fun dragMove(x: Float, y: Float) { calls += "dragMove ${x.toInt()},${y.toInt()}" }
        override fun dragEnd() { calls += "dragEnd" }
        override val zoomed: Boolean get() = zoomedNow
        override fun zoomBegin(x: Float, y: Float) { calls += "zoomBegin ${x.toInt()},${y.toInt()}" }
        override fun zoom(factor: Float, x: Float, y: Float) { calls += "zoom %.2f %d,%d".format(factor, x.toInt(), y.toInt()) }
        override fun zoomEnd() { calls += "zoomEnd" }
        override fun zoomReset() { calls += "zoomReset" }
    }
    private val m = DesktopGestures.Machine(out, slop = 10f, clickPx = 40f, swipePx = 100f)

    private fun ev(action: Int, t: Long, vararg p: Pair<Float, Float>): Verdict =
        m.onEvent(action, t, FloatArray(p.size) { p[it].first }, FloatArray(p.size) { p[it].second }, p.size)

    private fun sway() { m.pointer = true; m.magnify = true }

    // ---- sway: one finger = mouse ----

    @Test fun swayTapIsALeftClickAtTheFingerAndNeverReachesLorie() {
        sway()
        assertEquals(Verdict.HOLD, ev(ACTION_DOWN, 0, 120f to 80f))
        assertEquals(Verdict.HOLD, ev(ACTION_MOVE, 30, 124f to 83f))          // a tremble inside the slop
        assertEquals(Verdict.CONSUME, ev(ACTION_UP, 90, 124f to 83f))
        assertEquals(listOf("click 120,80"), calls)
        assertFalse(m.onLongPress(DesktopGestures.LONG_PRESS_MS))
    }

    @Test fun swayDragIsAWheelLockedToItsAxis() {
        sway()
        ev(ACTION_DOWN, 0, 100f to 500f)
        assertEquals(Verdict.CONSUME, ev(ACTION_MOVE, 10, 102f to 480f))     // up 20 px: past the slop, vertical
        assertEquals(Verdict.CONSUME, ev(ACTION_MOVE, 20, 110f to 440f))     // up 40 more = one click
        assertEquals(Verdict.CONSUME, ev(ACTION_UP, 30, 110f to 440f))
        assertEquals(listOf("scroll 0,60", "scroll 0,120"), calls)           // finger up = wheel down
        assertEquals(0f, m.scrolledX, 0.01f)
        assertFalse(m.onLongPress(5000))                                     // a drag is never a long-press
    }

    @Test fun swayLongPressThenLiftIsARightClick() {
        sway()
        ev(ACTION_DOWN, 1000, 200f to 300f)
        assertTrue(m.onLongPress(1000 + DesktopGestures.LONG_PRESS_MS))
        assertEquals(listOf("armed"), calls)                                 // nothing sent to X yet
        assertEquals(Verdict.CONSUME, ev(ACTION_MOVE, 1500, 204f to 303f))   // inside the slop: still a right click
        assertEquals(Verdict.CONSUME, ev(ACTION_UP, 1600, 204f to 303f))
        assertEquals(listOf("armed", "right 200,300"), calls)
    }

    @Test fun swayLongPressThenDragMovesTheWindowAndAlwaysReleases() {
        sway()
        ev(ACTION_DOWN, 0, 200f to 300f)
        m.onLongPress(DesktopGestures.LONG_PRESS_MS)
        assertEquals(Verdict.CONSUME, ev(ACTION_MOVE, 500, 230f to 300f))
        assertEquals(Verdict.CONSUME, ev(ACTION_MOVE, 520, 300f to 350f))
        assertEquals(Verdict.CONSUME, ev(ACTION_UP, 540, 300f to 350f))
        assertEquals(listOf("armed", "dragStart 200,300", "dragMove 230,300", "dragMove 300,350", "dragEnd"), calls)

        calls.clear()                                                        // a cancelled move still lets go
        ev(ACTION_DOWN, 1000, 50f to 50f)
        m.onLongPress(1000 + DesktopGestures.LONG_PRESS_MS)
        ev(ACTION_MOVE, 1500, 90f to 50f)
        assertEquals(Verdict.CONSUME, ev(ACTION_CANCEL, 1510, 90f to 50f))
        assertEquals("dragEnd", calls.last())
        assertEquals(1, calls.count { it == "dragEnd" })
    }

    @Test fun swayCancelledLongPressSendsNothing() {
        sway()
        ev(ACTION_DOWN, 0, 10f to 10f)
        m.onLongPress(DesktopGestures.LONG_PRESS_MS)
        assertEquals(Verdict.CONSUME, ev(ACTION_CANCEL, 600, 10f to 10f))
        assertEquals(listOf("armed"), calls)
    }

    @Test fun swayKeepsTheTwoAndThreeFingerGestures() {
        sway()
        ev(ACTION_DOWN, 0, 100f to 500f); ev(ACTION_POINTER_DOWN, 5, 100f to 500f, 200f to 500f)
        ev(ACTION_MOVE, 10, 100f to 460f, 200f to 460f)
        assertEquals(listOf("scroll 0,120"), calls)                          // not zoomed: 2-finger drag still scrolls
        ev(ACTION_UP, 20, 100f to 460f)

        calls.clear()
        ev(ACTION_DOWN, 100, 300f to 300f); ev(ACTION_POINTER_DOWN, 105, 300f to 300f, 350f to 300f)
        ev(ACTION_POINTER_DOWN, 108, 300f to 300f, 350f to 300f, 400f to 300f)
        ev(ACTION_MOVE, 110, 180f to 300f, 230f to 300f, 280f to 300f)
        assertEquals(listOf("ws next"), calls)
    }

    @Test fun i3TapStillGoesToLorie() {
        m.magnify = true                                                     // magnifier on i3 does not change taps
        ev(ACTION_DOWN, 0, 100f to 100f)
        assertEquals(Verdict.FLUSH, ev(ACTION_UP, 100, 100f to 100f))
        assertTrue(calls.isEmpty())
    }

    // ---- magnifier ----

    @Test fun pinchZoomsWithTheSpreadRatioWhenTheMagnifierIsOn() {
        m.magnify = true
        ev(ACTION_DOWN, 0, 200f to 400f); ev(ACTION_POINTER_DOWN, 5, 200f to 400f, 300f to 400f)   // spread 100
        assertEquals(Verdict.CONSUME, ev(ACTION_MOVE, 10, 170f to 400f, 330f to 400f))             // spread 160
        assertEquals(Verdict.CONSUME, ev(ACTION_MOVE, 20, 150f to 400f, 350f to 400f))             // 200
        assertEquals(Verdict.CONSUME, ev(ACTION_UP, 30, 150f to 400f))
        assertEquals(listOf("zoomBegin 250,400", "zoom 1.60 250,400", "zoom 1.25 250,400", "zoomEnd"), calls)
    }

    @Test fun pinchStillGoesToLorieWhenTheMagnifierIsOff() {
        ev(ACTION_DOWN, 0, 200f to 400f); ev(ACTION_POINTER_DOWN, 5, 200f to 400f, 300f to 400f)
        assertEquals(Verdict.FLUSH, ev(ACTION_MOVE, 10, 170f to 400f, 330f to 400f))
        assertTrue(calls.isEmpty())
    }

    @Test fun twoFingerDragPansWhileZoomed() {
        m.magnify = true; zoomedNow = true
        ev(ACTION_DOWN, 0, 100f to 500f); ev(ACTION_POINTER_DOWN, 5, 100f to 500f, 200f to 500f)
        assertEquals(Verdict.CONSUME, ev(ACTION_MOVE, 10, 100f to 460f, 200f to 460f))
        ev(ACTION_MOVE, 20, 130f to 420f, 230f to 420f)
        assertEquals(listOf("zoomBegin 150,500", "zoom 1.00 150,460", "zoom 1.00 180,420"), calls)   // no wheel
    }

    @Test fun liftingOneFingerMidPanRefocusesInsteadOfJumping() {
        m.magnify = true; zoomedNow = true
        ev(ACTION_DOWN, 0, 100f to 500f); ev(ACTION_POINTER_DOWN, 5, 100f to 500f, 200f to 500f)
        ev(ACTION_MOVE, 10, 100f to 460f, 200f to 460f)
        ev(ACTION_POINTER_DOWN, 15, 100f to 460f, 200f to 460f, 300f to 460f)
        ev(ACTION_MOVE, 20, 100f to 460f, 200f to 460f, 300f to 460f)       // centre jumped 50 px: refocus there
        ev(ACTION_MOVE, 30, 100f to 450f, 200f to 450f, 300f to 450f)
        assertEquals(listOf("zoomBegin 150,500", "zoom 1.00 150,460", "zoomBegin 200,460", "zoom 1.00 200,450"), calls)
    }

    @Test fun twoFingerDoubleTapResetsOnlyWhileZoomed() {
        m.magnify = true; zoomedNow = true
        ev(ACTION_DOWN, 0, 100f to 100f); ev(ACTION_POINTER_DOWN, 5, 100f to 100f, 200f to 100f)
        assertEquals(Verdict.FLUSH, ev(ACTION_POINTER_UP, 80, 100f to 100f, 200f to 100f))   // 1st tap: lorie's
        ev(ACTION_UP, 90, 100f to 100f)
        ev(ACTION_DOWN, 250, 100f to 100f); ev(ACTION_POINTER_DOWN, 255, 100f to 100f, 200f to 100f)
        assertEquals(Verdict.CONSUME, ev(ACTION_POINTER_UP, 330, 100f to 100f, 200f to 100f))
        assertEquals(Verdict.CONSUME, ev(ACTION_UP, 340, 100f to 100f))
        assertEquals(listOf("zoomReset"), calls)

        calls.clear(); zoomedNow = false                                     // not zoomed: both taps are lorie's
        ev(ACTION_DOWN, 1000, 100f to 100f); ev(ACTION_POINTER_DOWN, 1005, 100f to 100f, 200f to 100f)
        ev(ACTION_POINTER_UP, 1080, 100f to 100f, 200f to 100f); ev(ACTION_UP, 1090, 100f to 100f)
        ev(ACTION_DOWN, 1200, 100f to 100f); ev(ACTION_POINTER_DOWN, 1205, 100f to 100f, 200f to 100f)
        assertEquals(Verdict.FLUSH, ev(ACTION_POINTER_UP, 1280, 100f to 100f, 200f to 100f))
        assertTrue(calls.isEmpty())
    }

    @Test fun slowTwoFingerTapsAreNotADoubleTap() {
        m.magnify = true; zoomedNow = true
        ev(ACTION_DOWN, 0, 100f to 100f); ev(ACTION_POINTER_DOWN, 5, 100f to 100f, 200f to 100f)
        ev(ACTION_POINTER_UP, 80, 100f to 100f, 200f to 100f); ev(ACTION_UP, 90, 100f to 100f)
        ev(ACTION_DOWN, 900, 100f to 100f); ev(ACTION_POINTER_DOWN, 905, 100f to 100f, 200f to 100f)
        assertEquals(Verdict.FLUSH, ev(ACTION_POINTER_UP, 80 + DesktopGestures.DOUBLE_TAP_MS + 1, 100f to 100f, 200f to 100f))
        assertTrue(calls.isEmpty())
    }
}
