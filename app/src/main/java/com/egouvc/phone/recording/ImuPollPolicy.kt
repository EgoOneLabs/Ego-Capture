package com.egouvc.phone.recording

/** A: write then sleep 10ms. B: guard 10ms from successful USB completion,
 * counting elapsed parse/write time; minimum sleep 1ms, no busy catch-up.
 * The historical startBased name is a build flag, not the USB request-start anchor.
 * Failures retain the same 10ms backoff in both builds.
 */
object ImuPollPolicy {
    fun delayMs(startNs: Long, nowNs: Long, startBased: Boolean, success: Boolean): Long {
        if (!startBased || !success) return 10L
        val remainingNs = 10_000_000L - (nowNs - startNs).coerceAtLeast(0L)
        return ((remainingNs + 999_999L) / 1_000_000L).coerceAtLeast(1L)
    }
}

/** 成功收到重复包也代表本次读取成功，必须重置连续错误计数。 */
class ImuFailureStreak {
    var count = 0
        private set
    fun success() { count = 0 }
    fun failure(): Int { count += 1; return count }
}
