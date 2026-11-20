package com.fire17.linuxdesk.x11

import com.fire17.linuxdesk.x11.Magnifier.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The magnifier's zoom/pan mapping against a fake lorie that renders like renderer.cpp: zoom = the X screen divided by
 * the zoom, cropped at the pinned anchor and clamped to the screen. The point under the fingers must stay under them.
 */
class MagnifierTest {

    /** 1000×600 view filled by a 2000×1200 X screen (native resolution at 2× density, say). */
    private inner class FakeLorie : Magnifier.Lens {
        var zoom = 100f
        var left = 0f; var top = 0f
        var pins = 0; var resets = 0
        val sw = 2000f; val sh = 1200f
        override fun viewport() = Box(0f, 0f, 1000f, 600f)
        override fun shown() = Box(left, top, sw * 100f / zoom, sh * 100f / zoom)
        override fun zoomBy(factor: Float): Int { zoom = (zoom * factor).coerceIn(100f, 400f); return Math.round(zoom) }
        override fun pin(sourceX: Float, sourceY: Float, fracX: Float, fracY: Float) {
            pins++
            val w = sw * 100f / zoom; val h = sh * 100f / zoom
            left = (sourceX - fracX * w).coerceIn(0f, sw - w)   // renderer.cpp computePan with an anchor
            top = (sourceY - fracY * h).coerceIn(0f, sh - h)
        }
        override fun reset() { resets++; zoom = 100f; left = 0f; top = 0f }
        /** The X point lorie draws at view point (x, y). */
        fun at(x: Float, y: Float): Pair<Float, Float> {
            val s = shown(); return Pair(s.left + x / 1000f * s.width, s.top + y / 600f * s.height)
        }
    }

    private val lorie = FakeLorie()
    private val mag = Magnifier(lorie)

    private fun near(e: Pair<Float, Float>, a: Pair<Float, Float>) {
        assertEquals(e.first, a.first, 0.5f); assertEquals(e.second, a.second, 0.5f)
    }

    @Test fun pinchKeepsThePointUnderTheFingers() {
        val under = lorie.at(300f, 200f)                       // (600, 400) at 1:1
        mag.begin(300f, 200f)
        mag.step(1.5f, 300f, 200f); mag.step(4f / 3f, 300f, 200f)   // 150%, then 200%
        assertEquals(200, mag.percent)
        assertTrue(mag.zoomed)
        near(under, lorie.at(300f, 200f))                      // the renderer agrees...
        near(under, mag.sourceAt(300f, 200f))                  // ...and so does our own tracking
        assertEquals(1000f, lorie.shown().width, 0.01f)        // X screen untouched: half of it is shown
    }

    @Test fun panMovesTheMagnifiedPictureWithTheFingers() {
        mag.begin(500f, 300f); mag.step(2f, 500f, 300f); mag.end()
        val before = lorie.at(500f, 300f)
        mag.begin(500f, 300f)
        mag.step(1f, 400f, 300f)                               // fingers 100 view px left
        near(before, lorie.at(400f, 300f))                     // the same X point followed them
        assertEquals(200, mag.percent)                         // a pan does not zoom
    }

    @Test fun panIsClampedAtTheScreenEdge() {
        mag.begin(0f, 0f); mag.step(2f, 0f, 0f)                // zoom at the top-left corner
        assertEquals(0f, lorie.left, 0.01f); assertEquals(0f, lorie.top, 0.01f)
        mag.step(1f, 900f, 500f)                               // drag right/down past the edge
        assertEquals(0f, lorie.left, 0.01f); assertEquals(0f, lorie.top, 0.01f)
        near(Pair(0f, 0f), mag.sourceAt(0f, 0f))               // tracking clamps like the renderer
    }

    @Test fun zoomIsLimitedLikeLorie() {
        mag.begin(500f, 300f); mag.step(10f, 500f, 300f)
        assertEquals(400, mag.percent)
        mag.step(0.01f, 500f, 300f)
        assertEquals(100, mag.percent)
        assertFalse(mag.zoomed)
    }

    @Test fun aPinchEndingNearOneToOneSnapsBack() {
        mag.begin(500f, 300f); mag.step(1.04f, 500f, 300f); mag.end()
        assertEquals(1, lorie.resets)
        assertEquals(100, mag.percent)

        mag.begin(500f, 300f); mag.step(1.5f, 500f, 300f); mag.end()
        assertEquals(1, lorie.resets)                          // 150% stays
        assertEquals(150, mag.percent)
    }

    @Test fun resetGoesBackToOneToOne() {
        mag.begin(500f, 300f); mag.step(3f, 500f, 300f)
        mag.reset()
        assertEquals(100, mag.percent); assertFalse(mag.zoomed)
        assertEquals(100f, lorie.zoom, 0.01f)
        mag.step(2f, 500f, 300f)                               // no step without a new begin
        assertEquals(100f, lorie.zoom, 0.01f)
    }

    @Test fun beginReadsAZoomChangedElsewhere() {
        lorie.zoom = 250f; lorie.left = 400f; lorie.top = 100f  // e.g. lorie's own zoom keys
        mag.begin(500f, 300f)
        assertEquals(250, mag.percent)
        val under = lorie.at(500f, 300f)
        mag.step(1f, 500f, 300f)
        near(under, lorie.at(500f, 300f))                      // starting from lorie's crop, not from 1:1
    }

    @Test fun nothingHappensBeforeLorieKnowsTheScreen() {
        val empty = object : Magnifier.Lens {
            override fun viewport() = Box(0f, 0f, 0f, 0f)
            override fun shown() = Box(0f, 0f, 0f, 0f)
            override fun zoomBy(factor: Float): Int = error("must not zoom")
            override fun pin(sourceX: Float, sourceY: Float, fracX: Float, fracY: Float) = error("must not pin")
            override fun reset() {}
        }
        val m = Magnifier(empty)
        m.begin(1f, 1f); m.step(2f, 1f, 1f); m.end()
        assertEquals(100, m.percent)
    }
}
