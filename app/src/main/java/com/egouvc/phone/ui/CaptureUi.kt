package com.egouvc.phone.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.TextView

/** Presentation only: caller supplies the existing USB/camera enum state. */
object CaptureUi {
    const val NEUTRAL = 0
    const val READY = 1
    const val WAITING = 2
    const val ERROR = 3

    fun status(view: TextView, severity: Int) {
        val colors = when (severity) {
            READY -> "#E7F5EF" to "#176848"
            WAITING -> "#FFF4D9" to "#805B11"
            ERROR -> "#FCECEF" to "#A42F43"
            else -> "#EEF1F7" to "#526178"
        }
        view.setTextColor(Color.parseColor(colors.second))
        view.background = GradientDrawable().apply {
            setColor(Color.parseColor(colors.first))
            cornerRadius = 12f * view.resources.displayMetrics.density
        }
    }
}
