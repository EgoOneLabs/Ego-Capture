package com.egouvc.phone.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** A single visible layer draws both halves; no second decoder or bitmap crop. */
class StereoPreviewView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
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
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val gap = 10f * density
        val labelHeight = 20f * density
        val panelWidth = width.toFloat()
        val panelHeight = minOf((height - gap - 2 * labelHeight) / 2f, panelWidth * 9f / 16f)
        if (panelWidth <= 0f || panelHeight <= 0f) return
        val top = (height - (2 * (panelHeight + labelHeight) + gap)) / 2f
        val bitmap = frame?.takeUnless { it.isRecycled }
        for (eye in 0..1) {
            val labelTop = top + eye * (panelHeight + labelHeight + gap)
            paint.color = Color.rgb(109, 131, 148)
            paint.textSize = 12f * resources.displayMetrics.scaledDensity
            canvas.drawText(labels[eye], 0f, labelTop + 14f * density, paint)
            val rect = RectF(0f, labelTop + labelHeight, panelWidth, labelTop + labelHeight + panelHeight)
            paint.color = Color.rgb(20, 35, 50)
            canvas.drawRoundRect(rect, 8f * density, 8f * density, paint)
            if (bitmap != null) {
                val half = bitmap.width / 2
                val src = Rect(if (eye == 0) 0 else half, 0, if (eye == 0) half else bitmap.width, bitmap.height)
                val scale = minOf(rect.width() / src.width(), rect.height() / src.height())
                val w = src.width() * scale; val h = src.height() * scale
                val dst = RectF(rect.centerX()-w/2,rect.centerY()-h/2,rect.centerX()+w/2,rect.centerY()+h/2)
                canvas.save(); canvas.clipRect(rect); canvas.drawBitmap(bitmap, src, dst, paint); canvas.restore()
            }
        }
    }
}
