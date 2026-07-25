package com.samsung.agenticgallery

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
    fun exactCalendarYearReplacesModelDateGuessWithoutDuplicatingTimeFilters() {
        val model = plan(
            filter = FilterExpression.And(
                listOf(
                    FilterExpression.AlbumIs("Favorites"),
                    FilterExpression.TimeRange(1704067200000, 1735689600000),
                ),
            ),
            place = "goa",
        )

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

    @Test
    fun fillsMissingMerchantInsideModelOcrClause() {
        val model = plan(FilterExpression.True, null).copy(
            intent = QueryIntent.DOCUMENT_QA,
            ocrClause = OcrClause(query = "receipt", merchant = null),
        )

        val result = overlay.apply("Show a receipt from a merchant that does not exist.", model, null)

        assertEquals(QueryIntent.DOCUMENT_QA, result.plan.intent)
        assertEquals("a merchant that does not exist", result.plan.ocrClause?.merchant)
    }

    @Test
    fun qualityFollowUpRemovesInventedSemanticSearchTerms() {
        val active = setOf("one", "two")
        val model = plan(FilterExpression.True, null).copy(baseResultIds = active)

        val result = overlay.apply("Which is the best one?", model, active)

        assertEquals(SortSpec.QUALITY, result.plan.sort)
        assertTrue(result.plan.terms.isEmpty())
        assertTrue(result.plan.semanticClauses.isEmpty())
        assertEquals(active, result.plan.baseResultIds)
    }

    @Test
    fun temporalOnlyFollowUpRemovesModelInventedSemanticTerms() {
        val active = setOf("one", "two")
        val model = plan(FilterExpression.True, null).copy(baseResultIds = active)

        val result = overlay.apply("What about last year?", model, active)

        assertEquals(FilterExpression.TimeRange(1735689600000, 1767225599999), result.plan.filter)
        assertTrue(result.plan.terms.isEmpty())
        assertTrue(result.plan.semanticClauses.isEmpty())
        assertEquals(active, result.plan.baseResultIds)
    }

    @Test
    fun exactYearCountOverridesInventedModelSemanticsAndAggregation() {
        val model = plan(FilterExpression.True, null)

        val result = overlay.apply("How many photos did I take in 2024?", model, null)

        assertEquals(QueryIntent.COUNT, result.plan.intent)
        assertEquals(MediaScope.IMAGES, result.plan.mediaScope)
        assertEquals(AggregationSpec(AggregationOperation.COUNT), result.plan.aggregation)
        assertEquals(FilterExpression.TimeRange(1704067200000, 1735689599999), result.plan.filter)
        assertTrue(result.plan.terms.isEmpty())
        assertTrue(result.plan.semanticClauses.isEmpty())
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
