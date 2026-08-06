package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewedIdentityExpansionPolicyTest {
    @Test
    fun missingReferenceFailsClosed() {
        assertEquals(
            emptySet<String>(),
            ReviewedIdentityExpansionPolicy.acceptedFaces(
                scoresByCandidate = mapOf("face-1" to listOf(.95f)),
                references = emptyMap(),
            ),
        )
    }

    @Test
    fun reviewedHiddenAndUserCorrectedFacesAreNeverMoved() {
        val references = mapOf(
            "reviewed" to FaceClusterReference("reviewed-cluster", reviewed = true, hidden = false),
            "hidden" to FaceClusterReference("hidden-cluster", reviewed = false, hidden = true),
            "corrected" to FaceClusterReference("corrected-cluster", reviewed = false, hidden = false, userCorrected = true),
        )

        assertEquals(
            emptySet<String>(),
            ReviewedIdentityExpansionPolicy.acceptedFaces(
                scoresByCandidate = references.keys.associateWith { listOf(.95f) },
                references = references,
            ),
        )
    }

    @Test
    fun referencedUnreviewedFaceCanBeAcceptedWithStrongEvidence() {
        assertEquals(
            setOf("face-1"),
            ReviewedIdentityExpansionPolicy.acceptedFaces(
                scoresByCandidate = mapOf("face-1" to listOf(.95f)),
                references = mapOf("face-1" to FaceClusterReference("automatic", reviewed = false, hidden = false)),
            ),
        )
    }

    @Test
    fun unassignedFaceCanBeAcceptedWithStrongEvidence() {
        assertEquals(
            setOf("face-1"),
            ReviewedIdentityExpansionPolicy.acceptedFaces(
                scoresByCandidate = mapOf("face-1" to listOf(.95f)),
                references = mapOf(
                    "face-1" to FaceClusterReference(clusterId = null, reviewed = false, hidden = false),
                ),
            ),
        )
    }
}
