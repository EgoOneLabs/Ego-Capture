package com.egouvc.phone.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class ImuPollPolicyTest {
    @Test fun legacyDelayIsUnchanged() {
        assertEquals(10L, ImuPollPolicy.delayMs(0L, 6_000_000L, false, true))
    }
    @Test fun usbEndGuardCountsElapsedParseAndWriteTime() {
        assertEquals(4L, ImuPollPolicy.delayMs(0L, 6_000_000L, true, true))
        assertEquals(5L, ImuPollPolicy.delayMs(0L, 5_100_000L, true, true))
    }
    @Test fun slowRequestNeverTriggersBusyCatchup() {
        assertEquals(1L, ImuPollPolicy.delayMs(0L, 35_000_000L, true, true))
    }
    @Test fun errorsKeepTenMillisecondBackoff() {
        assertEquals(10L, ImuPollPolicy.delayMs(0L, 500_000_000L, true, false))
    }
    @Test fun guardRoundsUpAndNeverBusySpins() {
        assertEquals(1L, ImuPollPolicy.delayMs(0L, 9_999_999L, true, true))
        assertEquals(1L, ImuPollPolicy.delayMs(0L, 10_000_000L, true, true))
        assertEquals(10L, ImuPollPolicy.delayMs(5_000_000L, 4_000_000L, true, true))
    }
    @Test fun variantsDifferOnlyForSuccessfulWaits() {
        val usbCompleted = 100_000_000L
        val afterWrite = 106_000_000L
        assertEquals(10L, ImuPollPolicy.delayMs(usbCompleted, afterWrite, false, true))
        assertEquals(4L, ImuPollPolicy.delayMs(usbCompleted, afterWrite, true, true))
        assertEquals(10L, ImuPollPolicy.delayMs(usbCompleted, afterWrite, false, false))
        assertEquals(10L, ImuPollPolicy.delayMs(usbCompleted, afterWrite, true, false))
    }
    @Test fun successfulDuplicateBreaksFailureStreak() {
        val streak = ImuFailureStreak()
        repeat(99) { streak.failure() }
        streak.success() // 包是否重复由保存去重逻辑处理，不应影响读取成功计数
        assertEquals(0, streak.count)
        assertEquals(1, streak.failure())
    }
}
