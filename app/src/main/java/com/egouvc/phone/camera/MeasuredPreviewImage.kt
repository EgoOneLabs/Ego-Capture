package com.egouvc.phone.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.ImageView

/** Counts distinct sampled preview bitmaps actually drawn by this View. */
class MeasuredPreviewImage @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {
    init {
        // Opaque coverage prevents any native Surface from leaking through the
        // FIT_CENTER margins or a frame being cleared during camera restart.
        setBackgroundColor(Color.BLACK)
        scaleType = ScaleType.FIT_CENTER
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = (width.toLong() * UvcMode.TARGET_HEIGHT / UvcMode.TARGET_WIDTH).toInt()
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    var onRenderedFps: ((Double) -> Unit)? = null
    private var image: Bitmap? = null
    private var submitted = 0L
    private var drawn = 0L
    private var windowStartNs = 0L
    private var windowFrames = 0L

    fun showFrame(bitmap: Bitmap) {
        val old = image
        image = bitmap
        submitted++
        setImageBitmap(bitmap)
        // Hardware rendering may still hold the previous bitmap for a draw.
        if (old != null) postDelayed({ if (old !== image) old.recycle() }, 1_000L)
    }

    fun clearFrame() {
        setImageDrawable(null)
        val old = image
        image = null
        if (old != null) postDelayed({ if (old !== image) old.recycle() }, 1_000L)
        windowStartNs = 0L
        windowFrames = 0L
        onRenderedFps?.invoke(0.0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (image == null || drawn == submitted) return
        drawn = submitted
        val now = SystemClock.elapsedRealtimeNanos()
        if (windowStartNs == 0L) windowStartNs = now
        windowFrames++
        val elapsed = now - windowStartNs
        if (elapsed >= 1_000_000_000L) {
            val fps = windowFrames * 1_000_000_000.0 / elapsed
            windowStartNs = now
            windowFrames = 0L
            post { onRenderedFps?.invoke(fps) }
        }
    }
}
