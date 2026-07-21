package com.askphotos.android

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicPlanOverlayTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC)
    private val overlay = DeterministicPlanOverlay(QueryCompiler(clock = clock))

    @Test
    fun addsExactPreviousYearAndCanonicalPlaceWithoutReplacingModelSemantics() {
        val model = plan(filter = FilterExpression.True, place = null)

        val result = overlay.apply("Pichle saal Goa wali family photos dikhao", model, null)

        assertTrue(result.applied)
        assertEquals(FilterExpression.TimeRange(1735689600000, 1767225599999), result.plan.filter)
        assertEquals("goa", result.plan.place)
        assertEquals(listOf("family close-up"), result.plan.terms)
    }

    @Test
    fun combinesModelFilterWithExactExplicitCalendarYear() {
        val model = plan(filter = FilterExpression.AlbumIs("Favorites"), place = "goa")

        val result = overlay.apply("Show Goa photos from 2024", model, null)

        assertEquals(
            FilterExpression.And(
                listOf(
                    FilterExpression.AlbumIs("Favorites"),
                    FilterExpression.TimeRange(1704067200000, 1735689599999),
                ),
            ),
            result.plan.filter,
        )
    }

    @Test
    fun deterministicHardMediaPlaceAndSortOverrideModelGuesses() {
        val model = plan(filter = FilterExpression.True, place = "singapore").copy(
            mediaScope = MediaScope.IMAGES,
            sort = SortSpec.QUALITY,
        )

        val result = overlay.apply("Show my latest Goa videos", model, null)

        assertEquals(MediaScope.VIDEOS, result.plan.mediaScope)
        assertEquals("goa", result.plan.place)
        assertEquals(SortSpec.CAPTURE_TIME_DESC, result.plan.sort)
    }

    private fun plan(filter: FilterExpression, place: String?) = GalleryQueryPlan(
        originalQuery = "fixture",
        intent = QueryIntent.FIND_MEDIA,
        filter = filter,
        semanticClauses = listOf(SemanticClause("family close-up", "family close-up")),
        terms = listOf("family close-up"),
        place = place,
    )
}
