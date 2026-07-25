package com.samsung.agenticgallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualFeatureExtractorTest {
    @Test
    fun transformedGradientKeepsNearIdenticalPerceptualHash() {
        val base = texturedImage(64, 64, 0)
        val brighter = texturedImage(64, 64, 20)

        val first = VisualFeatureExtractor.extract(base, 64, 64)
        val second = VisualFeatureExtractor.extract(brighter, 64, 64)

        assertTrue(VisualFeatureExtractor.hammingDistance(first.perceptualHash, second.perceptualHash) <= 6)
    }

    @Test
    fun highFrequencyImageHasMoreDetailThanFlatImage() {
        val flat = IntArray(64 * 64) { rgb(128, 128, 128) }
        val checker = IntArray(64 * 64) { index ->
            val x = index % 64
            val y = index / 64
            if ((x / 4 + y / 4) % 2 == 0) rgb(0, 0, 0) else rgb(255, 255, 255)
        }

        val flatFeatures = VisualFeatureExtractor.extract(flat, 64, 64)
        val checkerFeatures = VisualFeatureExtractor.extract(checker, 64, 64)

        assertTrue(checkerFeatures.blurScore > flatFeatures.blurScore)
        assertTrue(checkerFeatures.qualityScore in 0f..1f)
        assertEquals(0f, flatFeatures.blurScore, 1e-6f)
    }

    private fun texturedImage(width: Int, height: Int, offset: Int) = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        val value = 30 + ((x * 17 + y * 29 + x * y) % 150) + offset
        rgb(value, value, value)
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue
}
