package com.askphotos.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoSemanticHitResolutionTest {
    @Test
    fun evidenceComesFromTheWinningParentOrKeyframeVector() {
        val lowFrame = frame("frame-low", 2_000)
        val highFrame = frame("frame-high", 7_000)
        val keyframes = listOf(lowFrame, highFrame).associateBy { it.id }

        val parentWins = resolveSemanticVideoHits(
            listOf(VectorHit("video-1", .9f), VectorHit(lowFrame.id, .2f)),
            keyframes,
        ).single()
        assertEquals("video-1", parentWins.hit.mediaId)
        assertEquals(.9f, parentWins.hit.score)
        assertNull(parentWins.keyframe)

        val frameWins = resolveSemanticVideoHits(
            listOf(VectorHit("video-1", .5f), VectorHit(lowFrame.id, .7f), VectorHit(highFrame.id, .95f)),
            keyframes,
        ).single()
        assertEquals("video-1", frameWins.hit.mediaId)
        assertEquals(.95f, frameWins.hit.score)
        assertEquals(highFrame, frameWins.keyframe)
    }

    private fun frame(id: String, timestampMs: Long) = VideoKeyframeRecord(
        id = id,
        mediaId = "video-1",
        timestampMs = timestampMs,
        previewPath = "/private/$id.jpg",
        labels = emptyList(),
        ocrText = "",
        perceptualHash = 0,
        qualityScore = 1f,
        producerVersion = VideoKeyframePolicy.PRODUCER_VERSION,
    )
}
