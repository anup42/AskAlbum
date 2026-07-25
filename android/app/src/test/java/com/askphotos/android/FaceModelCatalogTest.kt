package com.askphotos.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FaceModelCatalogTest {
    @Test
    fun sfaceCatalogPinsPermissiveModelAndAndroidContract() {
        val spec = FaceModelCatalog.sface

        assertEquals("opencv/face_recognition_sface", spec.repository)
        assertEquals("c140188d35b7d0050f2dcfdfb8fe3e98d516744f", spec.revision)
        assertEquals(38_696_353L, spec.sizeBytes)
        assertEquals("0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79", spec.sha256)
        assertEquals("Apache-2.0", spec.license)
        assertEquals(112, spec.inputSize)
        assertEquals(128, spec.embeddingDimension)
        assertEquals(.363f, spec.cosineThreshold)
    }

    @Test
    fun clusteringRequiresOpenCvThresholdAndAnExistingCluster() {
        val nearest = VectorHit("face-1", .363f)

        assertEquals("person_a", FaceClusterPolicy.matchingCluster(nearest, "person_a"))
        assertNull(FaceClusterPolicy.matchingCluster(VectorHit("face-1", .3629f), "person_a"))
        assertNull(FaceClusterPolicy.matchingCluster(nearest, null))
        assertFalse(FaceModelCatalog.sface.downloadUrl.contains("latest", ignoreCase = true))
    }

    @Test
    fun automaticClusteringRequiresStrongOrSupportedSeparatedEvidence() {
        fun candidate(faceId: String, score: Float, clusterId: String, hidden: Boolean = false) =
            FaceClusterCandidate(VectorHit(faceId, score), FaceClusterReference(clusterId, reviewed = false, hidden = hidden))

        assertEquals(
            "person_a",
            FaceClusterPolicy.matchingCluster(
                listOf(candidate("a1", .52f, "person_a"), candidate("a2", .44f, "person_a"), candidate("b1", .31f, "person_b")),
            ),
        )
        assertEquals("person_a", FaceClusterPolicy.matchingCluster(listOf(candidate("a1", .60f, "person_a"))))
        assertNull(FaceClusterPolicy.matchingCluster(listOf(candidate("a1", .55f, "person_a"))))
        assertNull(
            FaceClusterPolicy.matchingCluster(
                listOf(candidate("a1", .54f, "person_a"), candidate("a2", .46f, "person_a"), candidate("b1", .51f, "person_b")),
            ),
        )
        assertNull(FaceClusterPolicy.matchingCluster(listOf(candidate("a1", .90f, "person_a", hidden = true))))
    }

    @Test
    fun refinementPreservesRepresentativeCorrectionsAndMissingVectors() {
        val memberships = listOf(
            FaceClusterMembership("representative", userCorrected = false),
            FaceClusterMembership("close", userCorrected = false),
            FaceClusterMembership("wrong", userCorrected = false),
            FaceClusterMembership("manual", userCorrected = true),
            FaceClusterMembership("missing", userCorrected = false),
        )

        val result = FaceClusterRefinementPolicy.decide(
            memberships = memberships,
            representativeFaceId = "representative",
            similarities = mapOf("representative" to 1f, "close" to .43f, "wrong" to .1f, "manual" to .1f),
        )

        assertEquals(setOf("wrong"), result.rejectedFaceIds)
        assertEquals(setOf("representative", "close", "manual", "missing"), result.keptFaceIds)
    }
}
