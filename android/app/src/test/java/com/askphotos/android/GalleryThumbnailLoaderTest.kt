package com.askphotos.android

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryThumbnailLoaderTest {
    @Test
    fun `thumbnail requests use bounded reusable size buckets`() {
        assertEquals(128, thumbnailEdgeBucket(0))
        assertEquals(128, thumbnailEdgeBucket(96))
        assertEquals(384, thumbnailEdgeBucket(360))
        assertEquals(1536, thumbnailEdgeBucket(4000))
    }

    @Test
    fun `large image fallback is sampled before allocation`() {
        assertEquals(8, thumbnailSampleSize(4032, 3024, 384))
        assertEquals(2, thumbnailSampleSize(1920, 1080, 768))
        assertEquals(1, thumbnailSampleSize(320, 240, 384))
        assertEquals(1, thumbnailSampleSize(0, 0, 384))
    }
}
