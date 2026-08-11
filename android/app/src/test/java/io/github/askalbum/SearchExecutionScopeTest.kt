package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchExecutionScopeTest {
    @Test
    fun explicitInitialScopeAppliesBeforeAResultSetExists() {
        assertEquals(setOf("seed-1", "seed-2"), resolveExecutionScope(null, setOf("seed-1", "seed-2")))
    }

    @Test
    fun persistedFollowUpScopeTakesPrecedence() {
        assertEquals(setOf("result-1"), resolveExecutionScope(setOf("result-1"), setOf("stale-explicit")))
        assertNull(resolveExecutionScope(null, null))
    }

    @Test
    fun onlyResolvedPlanOrExplicitIdsCreateAClosedExecutionScope() {
        assertTrue(hasClosedExecutionScope(setOf("active-result"), null))
        assertTrue(hasClosedExecutionScope(null, setOf("fixture-item")))
        assertFalse(hasClosedExecutionScope(null, null))
    }
}
