package com.egouvc.phone.recording

data class RecordingMetrics(
    val targetFps: Int,
    val savedFps: Double,
    val videoWindowMbPerSec: Double,
    val videoAverageMbPerSec: Double,
    val videoMb: Double,
)

object CaptureRates {
    val choices = listOf(30, 15, 10)
    fun checked(fps: Int): Int { require(fps in choices); return fps }
    fun mbPerSec(bytes: Long, elapsedNs: Long): Double =
        if (elapsedNs > 0L) bytes.toDouble() * 1000.0 / elapsedNs else 0.0
}
