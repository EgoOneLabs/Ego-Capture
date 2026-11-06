package com.egouvc.phone.recording

import org.junit.Assert.*
import org.junit.Test

class CaptureRatesTest {
    @Test fun actualLogicalByteRateUsesDecimalMBAndElapsedTime() {
        assertEquals(8.0, CaptureRates.mbPerSec(16_000_000L, 2_000_000_000L), 1e-12)
        assertEquals(0.0, CaptureRates.mbPerSec(10, 0), 0.0)
    }
    @Test fun supportedCadencesSelectExpectedFramesAt60Hz() {
        for (fps in CaptureRates.choices) {
            val sampler = RawFrameSampler(CaptureRates.checked(fps))
            val times = (0L until 3600L).map { 1_000_000_000L + it * 1_000_000_000L / 60 }
            val chosen = times.filter { sampler.select(it) }
            assertEquals(fps * 60, chosen.size)
            assertTrue(chosen.zipWithNext().all { (a, b) -> b > a })
        }
    }
    @Test fun missingInputFramesNeverProduceSyntheticFramesOrBursts() {
        for (fps in CaptureRates.choices) {
            val sampler = RawFrameSampler(fps)
            assertTrue(sampler.select(1_000_000_000L))
            assertTrue(sampler.select(10_000_000_000L))
            assertFalse(sampler.select(10_000_000_001L))
        }
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectUnsupportedSelection() { CaptureRates.checked(60) }
}
