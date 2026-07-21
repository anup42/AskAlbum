package com.askphotos.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultSetPlanPatchResolverTest {
    private val resolver = ResultSetPlanPatchResolver()
    private val ids = setOf("media_one", "media_two")
    private val state = ConversationSearchState(
        sessionId = "test",
        activeResultSetId = "rs_current123",
        activeResultIds = ids,
    )

    @Test
    fun appOwnedPatchStripsMediaIdsThenRestoresOnlyTheActiveScope() {
        val compiled = QueryCompiler().compile("Only bicycles", ids)

        val (patch, resolved) = resolver.createAndApply(compiled, state)

        assertEquals("rs_current123", patch.baseResultSetId)
        assertNull(patch.replacementPlan.baseResultIds)
        assertEquals(ids, resolved.baseResultIds)
        assertTrue("semanticClauses" in patch.changedFields)
    }

    @Test
    fun staleOrModelSuppliedReferencesAreRejected() {
        val compiled = QueryCompiler().compile("Only bicycles", ids)
        val patch = resolver.createAndApply(compiled, state).first

        assertThrows(IllegalArgumentException::class.java) {
            resolver.apply(patch.copy(baseResultSetId = "rs_different123"), state)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolver.apply(patch.copy(replacementPlan = patch.replacementPlan.copy(baseResultIds = setOf("invented"))), state)
        }
    }

    @Test
    fun bestOneIsAQualitySortOverTheExistingSetNotANewSemanticSearch() {
        val compiled = QueryCompiler().compile("Which is the best one?", ids)
        val (patch, resolved) = resolver.createAndApply(compiled, state)

        assertEquals(SortSpec.QUALITY, resolved.sort)
        assertTrue(resolved.terms.isEmpty())
        assertTrue(resolved.semanticClauses.isEmpty())
        assertEquals(setOf("sort"), patch.changedFields)
    }
}
