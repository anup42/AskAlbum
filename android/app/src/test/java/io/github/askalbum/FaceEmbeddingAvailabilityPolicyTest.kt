package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceEmbeddingAvailabilityPolicyTest {
    @Test
    fun onlyTheCurrentReadableDimensionCountsAsUsable() {
        assertTrue(FaceEmbeddingAvailabilityPolicy.isUsable(512, 512))
        assertFalse(FaceEmbeddingAvailabilityPolicy.isUsable(null, 512))
        assertFalse(FaceEmbeddingAvailabilityPolicy.isUsable(256, 512))
    }
}
