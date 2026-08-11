package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class CloseUpRefinementContractTest {
    @Test
    fun scopedCloseUpRefinementExcludesAnUncorroboratedWideShot() {
        val plan = QueryCompiler().compile(
            query = "Show close-ups",
            activeResultIds = setOf("close-up-item", "wide-shot-item"),
        )

        val refinedIds = FollowUpRefinementPolicy.corroboratedSemanticIds(
            scoped = plan.baseResultIds != null && "close-up" in plan.terms,
            semanticIds = listOf("close-up-item", "wide-shot-item"),
            lexicalIds = listOf("close-up-item"),
            eventIds = emptyList(),
        )

        assertEquals(setOf("close-up-item"), refinedIds)
    }
}
