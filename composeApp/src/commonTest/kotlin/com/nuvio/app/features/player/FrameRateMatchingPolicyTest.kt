package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrameRateMatchingPolicyTest {
    @Test
    fun acceptsPlausibleVideoFrameRates() {
        assertEquals(23.976f, FrameRateMatchingPolicy.validSourceFrameRate(23.976f))
        assertEquals(60f, FrameRateMatchingPolicy.validSourceFrameRate(60f))
    }

    @Test
    fun rejectsUnknownAndImplausibleFrameRates() {
        assertNull(FrameRateMatchingPolicy.validSourceFrameRate(null))
        assertNull(FrameRateMatchingPolicy.validSourceFrameRate(0f))
        assertNull(FrameRateMatchingPolicy.validSourceFrameRate(9.99f))
        assertNull(FrameRateMatchingPolicy.validSourceFrameRate(120.1f))
        assertNull(FrameRateMatchingPolicy.validSourceFrameRate(Float.NaN))
    }
}
