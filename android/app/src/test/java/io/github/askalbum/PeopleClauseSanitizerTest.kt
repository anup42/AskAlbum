package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleClauseSanitizerTest {
    @Test
    fun removesModelNullPlaceholdersAndPreservesReviewedClusterIds() {
        val clauses = PeopleClauseSanitizer.sanitize(
            listOf(
                PersonClause("null"),
                PersonClause(" undefined "),
                PersonClause("person-wife"),
                PersonClause("person-wife"),
                PersonClause("person-me", mustBePresent = false),
            ),
        )

        assertEquals(
            listOf(
                PersonClause("person-wife"),
                PersonClause("person-me", mustBePresent = false),
            ),
            clauses,
        )
    }
}
