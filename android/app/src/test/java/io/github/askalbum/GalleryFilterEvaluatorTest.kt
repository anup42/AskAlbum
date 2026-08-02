package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryFilterEvaluatorTest {
    private val item = GalleryItem(
        id = "item",
        filename = "one.jpg",
        title = "One",
        creator = null,
        location = "Singapore",
        album = "Singapore 2024",
        latitude = null,
        longitude = null,
        tags = emptyList(),
        description = "",
        license = "CC0",
        sourceUrl = "",
        assetPath = null,
        kind = MediaKind.IMAGE,
        capturedAt = 1_710_000_000_000,
    )

    @Test
    fun typedFiltersAreExecutedAsHardDeterministicConstraints() {
        assertTrue(GalleryFilterEvaluator.matches(item, FilterExpression.TimeRange(1_700_000_000_000, 1_720_000_000_000)))
        assertFalse(GalleryFilterEvaluator.matches(item, FilterExpression.TimeRange(1_720_000_000_001, null)))
        assertTrue(GalleryFilterEvaluator.matches(item, FilterExpression.MediaKindIs(MediaKind.IMAGE)))
        assertFalse(GalleryFilterEvaluator.matches(item, FilterExpression.MediaKindIs(MediaKind.VIDEO)))
        assertTrue(GalleryFilterEvaluator.matches(item, FilterExpression.AlbumIs("singapore 2024")))
    }

    @Test
    fun unknownDateFailsClosedAndAndRequiresEveryClause() {
        val unknownDate = item.copy(capturedAt = null)
        assertFalse(GalleryFilterEvaluator.matches(unknownDate, FilterExpression.TimeRange(0, Long.MAX_VALUE)))
        assertFalse(
            GalleryFilterEvaluator.matches(
                item,
                FilterExpression.And(
                    listOf(FilterExpression.AlbumIs("Singapore 2024"), FilterExpression.MediaKindIs(MediaKind.VIDEO)),
                ),
            ),
        )
    }
}
