package com.askphotos.android

import org.junit.Assert.assertEquals
import org.junit.Test

class FaceCropSamplingPolicyTest {
    @Test
    fun largeGalleryImagesAreDecodedWithBoundedDimensions() {
        assertEquals(8, FaceCropSamplingPolicy.sampleSize(width = 8000, height = 6000))
        assertEquals(4, FaceCropSamplingPolicy.sampleSize(width = 4032, height = 3024))
    }

    @Test
    fun existingThumbnailsAreNotDownsampled() {
        assertEquals(1, FaceCropSamplingPolicy.sampleSize(width = 1024, height = 768))
        assertEquals(1, FaceCropSamplingPolicy.sampleSize(width = 0, height = 0))
    }
}
