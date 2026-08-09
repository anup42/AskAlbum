package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoKeyframePolicyTest {
    @Test
    fun samplingIsBoundedOrderedAndCoversTheTimeline() {
        val short = VideoKeyframePolicy.candidateTimestamps(18_000)
        assertEquals(listOf(2_250L, 6_750L, 11_250L, 15_750L), short)

        val long = VideoKeyframePolicy.candidateTimestamps(60 * 60 * 1_000L)
        assertEquals(VideoKeyframePolicy.MAX_KEYFRAMES, long.size)
        assertEquals(long.sorted(), long)
        assertTrue(long.first() > 0)
        assertTrue(long.last() < 60 * 60 * 1_000L)
    }

    @Test
    fun adjacentNearDuplicatesCollapseAndIdsAreStable() {
        assertTrue(VideoKeyframePolicy.shouldKeep(null, 0L))
        assertFalse(VideoKeyframePolicy.shouldKeep(0L, 0b111L))
        assertTrue(VideoKeyframePolicy.shouldKeep(0L, 0xffffL))
        assertEquals(
            VideoKeyframePolicy.stableId("media-1", 5_000),
            VideoKeyframePolicy.stableId("media-1", 5_000),
        )
        assertTrue(VideoKeyframePolicy.stableId("media-1", 5_000).matches(Regex("vf-[0-9a-f]{30}")))
    }

    @Test
    fun semanticFrameTimestampWinsOverOtherEvidenceTimestamps() {
        val evidence = listOf(
            EvidenceRecord("ocr", "video-1", "ocr_text", "text", .8f, timestampMs = 2_000L),
            EvidenceRecord("frame", "video-1", "image_text_embedding", "visual", .9f, timestampMs = 7_000L),
        )

        assertEquals(7_000L, VideoKeyframeSelectionPolicy.selectEvidenceTimestamp(evidence))
        assertEquals(
            7_000L,
            VideoKeyframeSelectionPolicy.selectTimestamp(
                available = listOf(2_000L, 7_000L),
                evidence = listOfNotNull(VideoKeyframeSelectionPolicy.selectEvidenceTimestamp(evidence)),
            ),
        )
    }

    @Test
    fun viewerKeepsKeyframeEvidenceForAnotherSearchResult() {
        val initial = SearchHit(item("video-1"), 1.0, emptyList())
        val next = SearchHit(
            item("video-2"),
            .9,
            listOf(
                EvidenceRecord(
                    id = "frame-2",
                    mediaId = "video-2",
                    sourceField = "video_keyframe",
                    text = "yellow bicycle at 7 seconds",
                    confidence = .92f,
                    timestampMs = 7_000L,
                ),
            ),
        )

        val selected = requireNotNull(findViewerEvidenceHit("video-2", initial, listOf(next)))
        assertEquals(7_000L, VideoKeyframeSelectionPolicy.selectEvidenceTimestamp(selected.evidence))
    }

    private fun item(id: String) = GalleryItem(
        id = id,
        filename = "$id.mp4",
        title = id,
        creator = null,
        location = "",
        latitude = null,
        longitude = null,
        tags = emptyList(),
        description = "",
        license = "",
        sourceUrl = "",
        assetPath = null,
        kind = MediaKind.VIDEO,
        mimeType = "video/mp4",
    )
}
