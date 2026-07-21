package com.askphotos.android

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
        assertEquals(listOf("beach"), plan.terms)
        assertEquals(100, plan.limit)
        assertNull(plan.baseResultIds)
    }

    @Test
    fun onlyFollowUpRetainsActiveResultSet() {
        val ids = setOf("one", "two")
        val plan = compiler.compile("Only bicycles", ids)
        assertEquals(ids, plan.baseResultIds)
        assertTrue("bicycle" in plan.terms)
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
    fun nonexistentReceiptMerchantIsARequiredDocumentConstraint() {
        val plan = compiler.compile("Show a receipt from a merchant that does not exist.")

        assertEquals(QueryIntent.DOCUMENT_QA, plan.intent)
        assertEquals(MediaScope.DOCUMENTS, plan.mediaScope)
        assertEquals("a merchant that does not exist", plan.ocrClause?.merchant)
    }

    @Test
    fun sensitiveContentClassifierFindsPasswordsAndCards() {
        assertTrue(SensitiveContentClassifier.isSensitive("Wi-Fi password: mango-tree"))
        assertTrue(SensitiveContentClassifier.isSensitive("4111 1111 1111 1111"))
        assertTrue(!SensitiveContentClassifier.isSensitive("Grand total Rs 1,248"))
    }

    @Test
    fun hinglishPreviousYearQueryProducesCanonicalPlaceAndExactRange() {
        val fixed = QueryCompiler(clock = Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC))
        val plan = fixed.compile("Pichle saal Goa wali photos dikhao")

        assertEquals(listOf("goa"), plan.terms)
        assertEquals("goa", plan.place)
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
    }

    @Test
    fun explicitYearIsConvertedToAnExactCalendarRangeByKotlin() {
        val fixed = QueryCompiler(clock = Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC))

        val plan = fixed.compile("Show beach sunset photos from 2024")

        assertEquals(FilterExpression.TimeRange(1704067200000, 1735689599999), plan.filter)
    }
}
