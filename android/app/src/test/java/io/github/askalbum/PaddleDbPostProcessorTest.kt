package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleDbPostProcessorTest {
    @Test
    fun extractsAndScalesHighConfidenceConnectedTextRegion() {
        val width = 10
        val height = 8
        val probabilities = FloatArray(width * height)
        for (y in 2..4) for (x in 2..5) probabilities[y * width + x] = .9f

        val boxes = PaddleDbPostProcessor.extract(
            probabilities,
            longArrayOf(1, 1, height.toLong(), width.toLong()),
            originalWidth = 100,
            originalHeight = 80,
        )

        assertEquals(listOf(PaddleTextBox(10, 10, 70, 60)), boxes)
    }

    @Test
    fun rejectsLowConfidenceAndTinyRegions() {
        val probabilities = FloatArray(64)
        probabilities[9] = .95f
        for (y in 3..5) for (x in 3..5) probabilities[y * 8 + x] = .4f

        assertTrue(
            PaddleDbPostProcessor.extract(probabilities, longArrayOf(1, 1, 8, 8), 80, 80).isEmpty(),
        )
    }

    @Test
    fun detectorDimensionsStayPositiveAndMultipleOfThirtyTwo() {
        listOf(1 to 1, 63 to 500, 1280 to 853, 9000 to 1000).forEach { (width, height) ->
            val (resizedWidth, resizedHeight) = PaddleBitmapPipeline.detectorDimensions(width, height)
            assertTrue(resizedWidth >= 32 && resizedWidth % 32 == 0)
            assertTrue(resizedHeight >= 32 && resizedHeight % 32 == 0)
            assertTrue(maxOf(resizedWidth, resizedHeight) <= 4000 + 32)
        }
    }
}
