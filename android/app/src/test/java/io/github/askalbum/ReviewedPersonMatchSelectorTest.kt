package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewedPersonMatchSelectorTest {
    @Test
    fun duplicateReviewedIdentityTermsBecomeOneAlternativeGroup() {
        val groups = ReviewedPersonMatchSelector.group(
            listOf(
                candidate("pooja-primary", listOf("Pooja"), faceCount = 313, updatedAt = 10),
                candidate("pooja-duplicate", listOf("pooja"), faceCount = 4, updatedAt = 20),
            ),
        )

        assertEquals(1, groups.size)
        assertEquals(setOf("pooja-primary", "pooja-duplicate"), groups.single().personIds)
    }

    @Test
    fun differentMentionedPeopleRemainSeparateRequirements() {
        val groups = ReviewedPersonMatchSelector.group(
            listOf(
                candidate("me", listOf("Me"), faceCount = 217, updatedAt = 10),
                candidate("brother", listOf("भैया"), faceCount = 120, updatedAt = 10),
                candidate("brother-duplicate", listOf("भैया"), faceCount = 3, updatedAt = 20),
            ),
        )

        assertEquals(
            setOf(setOf("me"), setOf("brother", "brother-duplicate")),
            groups.map(ReviewedPersonMatchGroup::personIds).toSet(),
        )
    }

    private fun candidate(
        id: String,
        terms: List<String>,
        faceCount: Int,
        updatedAt: Long,
    ) = ReviewedPersonMatchCandidate(id, terms, faceCount, updatedAt)
}
