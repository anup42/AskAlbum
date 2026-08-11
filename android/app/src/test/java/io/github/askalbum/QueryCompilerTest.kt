package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class QueryCompilerTest {
    private val compiler = QueryCompiler()

    @Test
    fun countQueryProducesBoundedTypedPlan() {
        val plan = compiler.compile("How many beach photos do I have?")
        assertEquals(QueryIntent.COUNT, plan.intent)
        assertEquals(MediaScope.IMAGES, plan.mediaScope)
        assertEquals(listOf("beach"), plan.terms)
        assertEquals(100, plan.limit)
        assertNull(plan.baseResultIds)
    }

    @Test
    fun exactYearPhotoCountContainsNoSemanticPredicate() {
        val fixed = QueryCompiler(clock = Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC))

        val plan = fixed.compile("How many photos did I take in 2024?")

        assertEquals(QueryIntent.COUNT, plan.intent)
        assertEquals(MediaScope.IMAGES, plan.mediaScope)
        assertEquals(AggregationSpec(AggregationOperation.COUNT), plan.aggregation)
        assertEquals(FilterExpression.TimeRange(1704067200000, 1735689599999), plan.filter)
        assertTrue(plan.terms.isEmpty())
        assertTrue(plan.semanticClauses.isEmpty())
    }

    @Test
    fun onlyFollowUpRetainsActiveResultSet() {
        val ids = setOf("one", "two")
        val plan = compiler.compile("Only bicycles", ids)
        assertEquals(ids, plan.baseResultIds)
        assertTrue("bicycle" in plan.terms)
    }

    @Test
    fun referentialCountUsesOnlyTheClosedActiveResultSet() {
        val ids = setOf("sunset-one", "sunset-two")

        val plan = compiler.compile("How many are there?", ids)

        assertEquals(QueryIntent.COUNT, plan.intent)
        assertEquals(AggregationSpec(AggregationOperation.COUNT), plan.aggregation)
        assertEquals(ids, plan.baseResultIds)
        assertTrue(plan.terms.isEmpty())
        assertTrue(plan.semanticClauses.isEmpty())
    }

    @Test
    fun explicitStandaloneCountStartsANewGalleryScope() {
        val fixed = QueryCompiler(clock = Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC))
        val plan = fixed.compile(
            "How many photos did I take in 2024?",
            setOf("prior-result"),
        )

        assertNull(plan.baseResultIds)
        assertEquals(QueryIntent.COUNT, plan.intent)
        assertEquals(FilterExpression.TimeRange(1704067200000, 1735689599999), plan.filter)
    }

    @Test
    fun aliasesAreCanonicalized() {
        val plan = compiler.compile("Find bike pictures")
        assertEquals(listOf("bicycle"), plan.terms)
    }

    @Test
    fun receiptTotalUsesDeterministicFactIntent() {
        val plan = compiler.compile("What was the total on my latest Swiggy receipt?")
        assertEquals(QueryIntent.ANSWER_FACT, plan.intent)
        assertTrue("swiggy" in plan.terms)
        assertTrue("receipt" in plan.terms)
        assertEquals(SortSpec.CAPTURE_TIME_DESC, plan.sort)
    }

    @Test
    fun wifiPasswordAcceptanceQueryUsesProtectedFactExecutorWithoutGemma() {
        val plan = compiler.compile("What is the Wi-Fi password in the latest screenshot?")

        assertEquals(QueryIntent.ANSWER_FACT, plan.intent)
        assertEquals("password", plan.ocrClause?.requestedField)
        assertEquals(SortSpec.CAPTURE_TIME_DESC, plan.sort)
    }

    @Test
    fun pluralScreenshotCategoryUsesCanonicalHardFilterConcept() {
        val plan = compiler.compile("Show screenshots")

        assertEquals(QueryIntent.FIND_MEDIA, plan.intent)
        assertEquals(listOf("screenshot"), plan.terms)
        assertTrue(DeterministicScreenshotMediaPolicy.requiresScreenshot(plan))
    }

    @Test
    fun nonexistentReceiptMerchantIsARequiredDocumentConstraint() {
        val plan = compiler.compile("Show a receipt from a merchant that does not exist.")

        assertEquals(QueryIntent.DOCUMENT_QA, plan.intent)
        assertEquals(MediaScope.DOCUMENTS, plan.mediaScope)
        assertEquals("a merchant that does not exist", plan.ocrClause?.merchant)
    }

    @Test
    fun sensitiveContentClassifierFindsPasswordsCardsAndFinancialFacts() {
        assertTrue(SensitiveContentClassifier.isSensitive("Wi-Fi password: mango-tree"))
        assertTrue(SensitiveContentClassifier.isSensitive("4111 1111 1111 1111"))
        assertTrue(SensitiveContentClassifier.isSensitive("Grand total Rs 1,248"))
    }

    @Test
    fun hinglishPreviousYearQueryProducesCanonicalPlaceAndExactRange() {
        val fixed = QueryCompiler(clock = Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC))
        val plan = fixed.compile("Pichle saal Goa wali photos dikhao")

        assertEquals(listOf("goa"), plan.terms)
        assertEquals("goa", plan.place)
        assertEquals(MediaScope.IMAGES, plan.mediaScope)
        assertEquals(Grouping.EVENT, plan.grouping)
        val range = plan.filter as FilterExpression.TimeRange
        assertEquals(Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(), range.startEpochMs)
        assertEquals(Instant.parse("2025-12-31T23:59:59.999Z").toEpochMilli(), range.endEpochMs)
    }

    @Test
    fun hindiQueryRetainsOriginalClauseAndCanonicalizesGoa() {
        val plan = compiler.compile("पिछले साल गोवा वाली फोटो दिखाओ")
        assertEquals(listOf("goa"), plan.terms)
        assertEquals("गोवा", plan.semanticClauses.single().text)
        assertEquals("goa", plan.semanticClauses.single().canonicalText)
        assertEquals(MediaScope.IMAGES, plan.mediaScope)
    }

    @Test
    fun hindiFamilyPhotoQueryUsesImageScope() {
        val plan = compiler.compile(
            "\u092a\u093f\u091b\u0932\u0947 \u0938\u093e\u0932 \u0915\u0940 \u0917\u094b\u0935\u093e " +
                "\u092b\u0948\u092e\u093f\u0932\u0940 \u092b\u094b\u091f\u094b \u0926\u093f\u0916\u093e\u0913\u0964",
        )

        assertEquals(MediaScope.IMAGES, plan.mediaScope)
    }

    @Test
    fun explicitYearIsConvertedToAnExactCalendarRangeByKotlin() {
        val fixed = QueryCompiler(clock = Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC))

        val plan = fixed.compile("Show beach sunset photos from 2024")

        assertEquals(FilterExpression.TimeRange(1704067200000, 1735689599999), plan.filter)
    }

    @Test
    fun temporalOnlyFollowUpUsesTheActiveResultSetWithoutSemanticFiller() {
        val fixed = QueryCompiler(clock = Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC))

        val plan = fixed.compile("What about last year?", setOf("sg-1", "sg-2"))

        assertEquals(FilterExpression.TimeRange(1735689600000, 1767225599999), plan.filter)
        assertTrue(plan.terms.isEmpty())
        assertTrue(plan.semanticClauses.isEmpty())
        assertEquals(setOf("sg-1", "sg-2"), plan.baseResultIds)
    }

    @Test
    fun comparisonQueryRetainsBothKnownScopesInsteadOfSelectingOnePlaceFilter() {
        val plan = compiler.compile("Compare my Goa and Singapore trips")

        assertEquals(QueryIntent.COMPARE, plan.intent)
        assertEquals(listOf("goa", "singapore"), plan.comparisonScopes)
        assertEquals(null, plan.place)
    }

    @Test
    fun listPlacesUsesCompletePlaceGrouping() {
        val plan = compiler.compile("List places in my recent photos")

        assertEquals(QueryIntent.LIST, plan.intent)
        assertEquals(Grouping.PLACE, plan.grouping)
        assertTrue(plan.terms.isEmpty())
    }

    @Test
    fun numericAggregationsUseOcrFactsInsteadOfSemanticPredicates() {
        val sum = compiler.compile("Sum my receipt totals")
        val maximum = compiler.compile("Which receipt has the highest total?")

        assertTrue(sum.semanticClauses.isEmpty())
        assertTrue(maximum.semanticClauses.isEmpty())
    }

    @Test
    fun pluralReceiptAggregationKeepsDocumentScopeAndOnlyExplicitMerchant() {
        val allReceipts = compiler.compile("Sum my receipt totals")
        val swiggy = compiler.compile("Sum the totals on my Swiggy receipts")

        assertEquals(QueryIntent.SUM, allReceipts.intent)
        assertEquals(MediaScope.DOCUMENTS, allReceipts.mediaScope)
        assertEquals("total", allReceipts.ocrClause?.requestedField)
        assertNull(allReceipts.ocrClause?.merchant)
        assertEquals("swiggy", swiggy.ocrClause?.merchant)
    }

    @Test
    fun closeUpFollowUpAddsACompositionPredicateInsteadOfOnlySorting() {
        val active = setOf("portrait", "wide-shot")

        val plan = compiler.compile("Show close-ups", active)

        assertEquals(active, plan.baseResultIds)
        assertEquals(listOf("close-up"), plan.terms)
        assertEquals(SortSpec.RELEVANCE, plan.sort)
    }
}
