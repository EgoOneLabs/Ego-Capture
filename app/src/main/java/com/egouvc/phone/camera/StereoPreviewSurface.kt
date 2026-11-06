package com.egouvc.phone.camera

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import android.view.View

/** Native UVC sink; exact dimensions must be respected, including the hidden 1dp sink. */
class StereoPreviewSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = (width.toLong() * UvcMode.TARGET_HEIGHT / UvcMode.TARGET_WIDTH).toInt()
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }
}
