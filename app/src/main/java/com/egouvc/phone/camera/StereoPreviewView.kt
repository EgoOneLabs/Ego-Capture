package com.egouvc.phone.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** A single visible layer draws both halves; no second decoder or bitmap crop. */
class StereoPreviewView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    var maximumHeightPx: Int = Int.MAX_VALUE
        set(value) { if (field != value) { field = value.coerceAtLeast(0); requestLayout() } }
    private var frame: Bitmap? = null
    private var labels = arrayOf("左画面", "右画面")
    fun setLabels(left: String, right: String) { labels = arrayOf(left, right); invalidate() }
    fun showFrame(bitmap: Bitmap) {
        val old = frame
        frame = bitmap
        invalidate()
        old?.let { postDelayed({ if (it !== frame && !it.isRecycled) it.recycle() }, 1000) }
    }
    fun clearFrame() {
        val old = frame; frame = null; invalidate()
        old?.let { postDelayed({ if (it !== frame && !it.isRecycled) it.recycle() }, 1000) }
    }
    // The view asks for exactly one 16:9 eye row, not the remaining screen height.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val density = resources.displayMetrics.density
        val eyeWidth = ((width - 8f * density) / 2f).coerceAtLeast(0f)
        val desiredHeight = kotlin.math.ceil(labelHeight() + eyeWidth * 9f / 16f).toInt()
        setMeasuredDimension(width, resolveSize(minOf(desiredHeight, maximumHeightPx), heightMeasureSpec))
    }
    private fun labelHeight(): Float {
        paint.textSize = 12f * resources.displayMetrics.scaledDensity
        return maxOf(22f * resources.displayMetrics.density,
            paint.fontMetrics.descent - paint.fontMetrics.ascent + 6f * resources.displayMetrics.density)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val gap = 8f * density
        val labelHeight = labelHeight()
        // Fixed horizontal pair: left sensor on the left, right sensor on the right.
        val panelWidth = (width - gap) / 2f
        val panelHeight = minOf(height - labelHeight, panelWidth * 9f / 16f)
        if (panelWidth <= 0f || panelHeight <= 0f) return
        val bitmap = frame?.takeUnless { it.isRecycled }
        for (eye in 0..1) {
            val left = eye * (panelWidth + gap)
            paint.color = Color.rgb(109, 131, 148)
            paint.textSize = 12f * resources.displayMetrics.scaledDensity
            canvas.drawText(labels[eye], left, -paint.fontMetrics.ascent, paint)
            val rect = RectF(left, labelHeight, left + panelWidth, labelHeight + panelHeight)
            paint.color = Color.rgb(20, 35, 50)
            canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)
            if (bitmap != null) {
                val half = bitmap.width / 2
                val src = Rect(if (eye == 0) 0 else half, 0, if (eye == 0) half else bitmap.width, bitmap.height)
                val scale = minOf(rect.width() / src.width(), rect.height() / src.height())
                val w = src.width() * scale; val h = src.height() * scale
                val dst = RectF(rect.centerX()-w/2,rect.centerY()-h/2,rect.centerX()+w/2,rect.centerY()+h/2)
                clip.reset(); clip.addRoundRect(rect, 8f * density, 8f * density, Path.Direction.CW)
                canvas.save(); canvas.clipPath(clip); canvas.drawBitmap(bitmap, src, dst, paint); canvas.restore()
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.color = Color.argb(65, 255, 255, 255)
            canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)
            paint.style = Paint.Style.FILL
        }
    }
}
