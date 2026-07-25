package com.samsung.agenticgallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ForegroundIndexRunLimitsTest {
    @Test
    fun acceptsBoundedInitialIndexLimits() {
        val limits = ForegroundIndexRunLimits(maxCycles = 5_000, maxDurationMs = 6 * 60 * 60_000L)
        assertEquals(5_000, limits.maxCycles)
    }

    @Test
    fun rejectsUnboundedOrMeaninglessLimits() {
        assertThrows(IllegalArgumentException::class.java) { ForegroundIndexRunLimits(maxCycles = 0) }
        assertThrows(IllegalArgumentException::class.java) { ForegroundIndexRunLimits(maxCycles = 5_001) }
        assertThrows(IllegalArgumentException::class.java) { ForegroundIndexRunLimits(maxDurationMs = 9_999) }
        assertThrows(IllegalArgumentException::class.java) {
            ForegroundIndexRunLimits(maxDurationMs = 6 * 60 * 60_000L + 1)
        }
    }
}
