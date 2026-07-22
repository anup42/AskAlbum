package com.askphotos.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
