package com.samsung.agenticgallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrModelCatalogTest {
    @Test
    fun multilingualPackPinsOfficialImmutablePaddleArtifacts() {
        val spec = OcrModelCatalog.paddleV5Multilingual

        assertEquals("Apache-2.0", spec.license)
        assertEquals(20_792_696L, spec.sizeBytes)
        assertEquals(setOf("det.onnx", "latin.onnx", "latin.yml", "devanagari.onnx", "devanagari.yml"), spec.artifacts.map { it.targetName }.toSet())
        assertTrue(spec.languages.contains("Hindi"))
        spec.artifacts.forEach { artifact ->
            assertTrue(artifact.repository.startsWith("PaddlePaddle/"))
            assertTrue(artifact.revision.matches(Regex("[0-9a-f]{40}")))
            assertTrue(artifact.sha256.matches(Regex("[0-9a-f]{64}")))
            assertFalse(artifact.downloadUrl.contains("/main/"))
        }
    }
}
