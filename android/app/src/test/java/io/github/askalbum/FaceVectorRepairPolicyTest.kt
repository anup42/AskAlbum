package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class FaceVectorRepairPolicyTest {
    @Test
    fun identifiesPersistedFacesMissingFromTheVectorIndex() {
        assertEquals(
            linkedSetOf("face-a", "face-c"),
            FaceVectorRepairPolicy.missingVectorIds(
                persistedFaceIds = linkedSetOf("face-a", "face-b", "face-c"),
                indexedFaceIds = setOf("face-b"),
            ),
        )
    }

    @Test
    fun doesNotRequeueFacesAlreadyPresentInTheVectorIndex() {
        assertEquals(
            emptySet<String>(),
            FaceVectorRepairPolicy.missingVectorIds(
                persistedFaceIds = setOf("face-a"),
                indexedFaceIds = setOf("face-a", "stale-face"),
            ),
        )
    }
}
