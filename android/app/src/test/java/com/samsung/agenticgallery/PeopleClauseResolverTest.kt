package com.samsung.agenticgallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeopleClauseResolverTest {
    private val media = mapOf(
        "me" to setOf("one", "two", "three"),
        "brother" to setOf("two", "three"),
        "dad" to setOf("three", "four"),
    )

    @Test
    fun intersectsRequiredPeopleAndUnionsExcludedPeople() {
        val scope = PeopleClauseResolver.resolve(
            listOf(
                PersonClause("me"),
                PersonClause("brother"),
                PersonClause("dad", mustBePresent = false),
            ),
        ) { media[it].orEmpty() }

        assertEquals(setOf("two", "three"), scope.requiredIds)
        assertEquals(setOf("three", "four"), scope.excludedIds)
    }

    @Test
    fun negativeOnlyClauseDoesNotCreateARequiredSet() {
        val scope = PeopleClauseResolver.resolve(
            listOf(PersonClause("dad", mustBePresent = false)),
        ) { media[it].orEmpty() }

        assertNull(scope.requiredIds)
        assertEquals(setOf("three", "four"), scope.excludedIds)
    }
}
