package com.askphotos.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BirthdayRetrievalConceptTest {
    @Test
    fun `reviewed wife filter leaves birthday as the soft retrieval concept`() {
        val compiled = QueryCompiler().compile("show my wife's birthday pics")
        val plan = compiled.copy(peopleClauses = listOf(PersonClause("person-wife")))

        val executionTerms = RetrievalTerms.forExecution(
            plan.terms,
            reviewedPeopleFilterApplied = true,
        )

        assertEquals(listOf("birthday"), executionTerms)
        assertEquals(
            listOf("birthday", "cake", "candle", "candles", "balloon", "balloons", "party", "celebration"),
            RetrievalConceptExpansion.evidenceTerms(executionTerms),
        )
    }

    @Test
    fun `birthday semantic variants preserve original and add concrete event evidence`() {
        val compiled = QueryCompiler().compile("show my wife's birthday pics")
        val plan = compiled.copy(peopleClauses = listOf(PersonClause("person-wife")))

        val variants = SemanticQueryVariants.from(plan)

        assertTrue("Original user query must remain a retrieval variant", compiled.originalQuery in variants)
        assertTrue("Concrete cake evidence must be searched", "birthday cake" in variants)
        assertTrue("Concrete party evidence must be searched", "birthday party" in variants)
        assertFalse("Resolved relationship must not run as a standalone visual query", "wife" in variants)
        assertFalse("Generic media words must not run as standalone visual queries", variants.any { it in setOf("pic", "pics") })
    }
}
