package com.askphotos.android

import org.junit.Assert.assertEquals
import org.junit.Test

class Fp16Test {
    @Test
    fun roundTripsRepresentativeFiniteValues() {
        listOf(0f, -0f, 1f, -2f, 0.3333f, 65_504f, 0.00006103515625f).forEach { value ->
            val restored = Fp16.toFloat(Fp16.fromFloat(value))
            val tolerance = maxOf(0.000001f, kotlin.math.abs(value) * 0.001f)
            assertEquals(value, restored, tolerance)
        }
    }
}
