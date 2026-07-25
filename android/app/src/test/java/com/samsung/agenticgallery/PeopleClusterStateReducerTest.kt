package com.samsung.agenticgallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleClusterStateReducerTest {
    @Test
    fun reviewedIdentityMovesImmediatelyToNamedStateAndEnforcesOneMe() {
        val clusters = listOf(
            cluster(id = "person_existing", label = "Anup", relationship = "Me", reviewed = true),
            cluster(id = "person_new", label = null, relationship = null, reviewed = false),
        )

        val updated = PeopleClusterStateReducer.review(
            clusters = clusters,
            id = "person_new",
            label = "Ravi",
            relationship = "Me",
            aliases = listOf("भैया", "bhaiya"),
        )

        val tagged = updated.single { it.id == "person_new" }
        assertEquals("Ravi", tagged.label)
        assertEquals("Me", tagged.relationship)
        assertEquals(listOf("भैया", "bhaiya"), tagged.aliases)
        assertTrue(tagged.reviewed)
        assertFalse(tagged.hidden)
        assertNull(updated.single { it.id == "person_existing" }.relationship)
    }

    private fun cluster(
        id: String,
        label: String?,
        relationship: String?,
        reviewed: Boolean,
    ) = PersonClusterReviewItem(
        id = id,
        label = label,
        relationship = relationship,
        aliases = emptyList(),
        faceCount = 1,
        sampleMediaId = null,
        reviewed = reviewed,
    )
}
