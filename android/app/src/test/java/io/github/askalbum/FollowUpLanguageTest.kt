package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpLanguageTest {
    private val active = setOf("media-1", "media-2")

    @Test
    fun naturalReferencePhrasesUseTheActiveResultSet() {
        assertTrue(FollowUpLanguage.isFollowUp("Make them close-ups", activeResultAvailable = true))
        assertTrue(FollowUpLanguage.isFollowUp("Same event but videos", activeResultAvailable = true))
        assertTrue(FollowUpLanguage.permitsMediaScopeRefinement("Same event but videos"))
    }

    @Test
    fun mediaScopeFollowUpKeepsTheParentScope() {
        val plan = QueryCompiler().compile("Same event but videos", active)
        assertEquals(active, plan.baseResultIds)
        assertEquals(MediaScope.VIDEOS, plan.mediaScope)
    }

    @Test
    fun closeUpFollowUpRequestsSearchableCompositionRefinement() {
        val plan = QueryCompiler().compile("Make them close-ups", active)
        assertEquals(active, plan.baseResultIds)
        assertEquals(SortSpec.RELEVANCE, plan.sort)
        assertEquals(listOf("close-up"), plan.terms)
    }
}
