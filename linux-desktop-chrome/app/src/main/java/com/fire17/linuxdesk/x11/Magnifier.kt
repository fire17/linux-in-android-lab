package com.fire17.linuxdesk.x11

/**
 * The desktop magnifier (Tami 2026-11-20 01:38: "zoom like a magnifying glass, not a resolution change").
 *
 * It drives lorie's own renderer zoom: the picture is cropped and enlarged on the GPU (renderer.cpp draws a source
 * rect of the X screen into the same viewport), the X screen keeps its size, so no window is resized or re-laid
 * out. lorie rebuilds LorieView's view → X screen transform in place for every new crop, so a tap still lands on the
 * X point under the finger (DesktopGestures maps through that same transform).
 *
 * The math is lorie's own pinch handler (TouchInputHandler.onScale @a00942e), so a pinch feels the same as in
 * upstream's trackpad mode: the X point that was under the fingers stays under the fingers while the zoom changes,
 * and a 2-finger drag (factor 1) pans by moving that point with the fingers. It is pinned with setPinchZoomFocus and
 * stays pinned after the fingers lift, so the magnified spot stays where it was left (it does not chase the cursor).
 * Zoom ends below [RESET_PERCENT] = back to 1:1.
 *
 * Pure (no Android types), so the mapping is unit-tested on the JVM; lorie is reached only through [Lens].
 */
class Magnifier(private val lens: Lens) {

    /** A rect as left, top, width, height. */
    class Box(val left: Float, val top: Float, val width: Float, val height: Float)

    /** LorieView's renderer zoom API (public in lorie @a00942e). */
    interface Lens {
        /** LorieView.getInputViewport(): where the picture is drawn, in LorieView pixels (stable across zoom). */
        fun viewport(): Box
        /** LorieView.getInputSourceRect(): the part of the X screen shown now, in X pixels. */
        fun shown(): Box
        /** The X screen size (left/top 0), or null if unknown: then it is derived from [shown] and the zoom. */
        fun screen(): Box? = null
        /** LorieView.adjustRendererZoomByFactor(factor): multiplies the zoom, returns the new percent (100–400). */
        fun zoomBy(factor: Float): Int
        /** LorieView.setPinchZoomFocus: keeps X point (sourceX, sourceY) at this fraction of the viewport. */
        fun pin(sourceX: Float, sourceY: Float, fracX: Float, fracY: Float)
        /** LorieView.resetRendererZoom() + clearPinchZoomFocus(): back to 1:1. */
        fun reset()
    }

    /** Current zoom in percent, as lorie last reported it. */
    var percent = 100
        private set
    val zoomed: Boolean get() = percent > 100

    // The shown X rect, tracked here rather than re-read from lorie each step: lorie reports a new rect only after the
    // renderer drew it, and steps come faster than frames (the same reason upstream tracks it in onScale).
    private var panLeft = 0f; private var panTop = 0f
    private var width = 0f; private var height = 0f
    private var lastFx = 0f; private var lastFy = 0f
    private var screenW = 0f; private var screenH = 0f   // the X screen size
    private var active = false

    /** Starts a pinch or pan with the fingers' centre at (x, y), in LorieView pixels. */
    fun begin(x: Float, y: Float) {
        val s = lens.shown()
        active = s.width > 0f && s.height > 0f
        if (!active) return
        panLeft = s.left; panTop = s.top; width = s.width; height = s.height
        lastFx = fracX(x); lastFy = fracY(y)
        percent = lens.zoomBy(1f)   // reads lorie's zoom (it may have been changed elsewhere, e.g. its zoom keys)
        // Without the X screen size (LorieView has no public getter for it), the shown rect is the screen divided by
        // the zoom (renderer.cpp, with the viewport filling the view as here; not while the keyboard hides the bottom).
        val screen = lens.screen()
        screenW = screen?.width ?: (s.width * percent / 100f)
        screenH = screen?.height ?: (s.height * percent / 100f)
    }

    /**
     * One step: the fingers' spread changed by [factor] (1 = a pure pan) and their centre is now at (x, y). The X point
     * that was under the previous centre is pinned under the new one.
     */
    fun step(factor: Float, x: Float, y: Float) {
        if (!active) return
        val fx = fracX(x); val fy = fracY(y)
        val anchorX = panLeft + lastFx * width
        val anchorY = panTop + lastFy * height
        percent = lens.zoomBy(factor)
        width = screenW * 100f / percent
        height = screenH * 100f / percent
        panLeft = clamp(anchorX - fx * width, screenW - width)
        panTop = clamp(anchorY - fy * height, screenH - height)
        lastFx = fx; lastFy = fy
        lens.pin(anchorX, anchorY, fx, fy)
    }

    /** The fingers lifted. A zoom left at (almost) 1:1 becomes exactly 1:1. */
    fun end() {
        if (!active) return
        active = false
        if (percent <= RESET_PERCENT) reset()
    }

    /** Back to 1:1 (a 2-finger double-tap, or the setting turned off). */
    fun reset() {
        active = false
        percent = 100
        lens.reset()
    }

    /** The X point drawn at LorieView point (x, y) after the last step (what the renderer shows; for tests and logs). */
    fun sourceAt(x: Float, y: Float): Pair<Float, Float> =
        Pair(panLeft + fracX(x) * width, panTop + fracY(y) * height)

    private fun fracX(x: Float): Float { val v = lens.viewport(); return if (v.width > 0f) (x - v.left) / v.width else 0.5f }
    private fun fracY(y: Float): Float { val v = lens.viewport(); return if (v.height > 0f) (y - v.top) / v.height else 0.5f }
    private fun clamp(v: Float, max: Float): Float = maxOf(0f, minOf(v, maxOf(0f, max)))

    companion object {
        /** A pinch that ends at or below this zoom (percent) snaps back to 1:1. */
        const val RESET_PERCENT = 105
    }
}
