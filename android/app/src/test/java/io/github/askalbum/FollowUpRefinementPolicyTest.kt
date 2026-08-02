package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FollowUpRefinementPolicyTest {
    @Test
    fun scopedSemanticFollowUpKeepsOnlyIndependentlyCorroboratedCandidates() {
        val selected = FollowUpRefinementPolicy.corroboratedSemanticIds(
            scoped = true,
            semanticIds = listOf("marina", "goa", "dog"),
            lexicalIds = setOf("marina"),
            eventIds = setOf("marina", "singapore-skyline"),
        )

        assertEquals(setOf("marina"), selected)
    }

    @Test
    fun semanticOnlyConceptFallsBackWhenNoOtherChannelCorroboratesIt() {
        val selected = FollowUpRefinementPolicy.corroboratedSemanticIds(
            scoped = true,
            semanticIds = listOf("bicycle"),
            lexicalIds = emptySet(),
            eventIds = emptySet(),
        )

        assertNull(selected)
    }

    @Test
    fun initialSearchIsNotNarrowedByFollowUpPolicy() {
        val selected = FollowUpRefinementPolicy.corroboratedSemanticIds(
            scoped = false,
            semanticIds = listOf("marina", "goa"),
            lexicalIds = setOf("marina"),
            eventIds = emptySet(),
        )

        assertNull(selected)
    }
}
