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
}
