package com.fire17.phoneinphone.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/** Letterboxes its content to the guest's aspect ratio (so touches map 1:1 onto guest pixels). */
class AspectFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private var aspect = 9f / 19.5f // width / height, until the first Session packet

    fun setAspect(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val a = width.toFloat() / height
        if (a != aspect) {
            aspect = a
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = View.MeasureSpec.getSize(widthMeasureSpec)
        val maxH = View.MeasureSpec.getSize(heightMeasureSpec)
        var w = maxW
        var h = (w / aspect).toInt()
        if (h > maxH) {
            h = maxH
            w = (h * aspect).toInt()
        }
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
    }
}
