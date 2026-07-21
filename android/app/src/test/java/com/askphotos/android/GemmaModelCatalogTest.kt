package com.askphotos.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaModelCatalogTest {
    @Test
    fun catalogPinsOnlyOfficialGemma4Artifacts() {
        assertEquals(setOf(GemmaModelTier.E2B, GemmaModelTier.E4B), GemmaModelCatalog.all.map { it.tier }.toSet())
        GemmaModelCatalog.all.forEach { spec ->
            assertTrue(spec.repository.startsWith("litert-community/gemma-4-"))
            assertTrue(spec.fileName.endsWith(".litertlm"))
            assertTrue(spec.revision.matches(Regex("[0-9a-f]{40}")))
            assertTrue(spec.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(spec.downloadUrl.startsWith("https://huggingface.co/"))
            assertTrue(spec.sizeBytes > 2_000_000_000L)
        }
    }

    @Test
    fun e4bRemainsTheHigherResourceOptionalTier() {
        assertTrue(GemmaModelCatalog.e4b.sizeBytes > GemmaModelCatalog.e2b.sizeBytes)
        assertTrue(GemmaModelCatalog.e4b.minimumRamBytes > GemmaModelCatalog.e2b.minimumRamBytes)
        assertEquals(8, GemmaModelCatalog.e2b.deviceClassRamGb)
        assertEquals(12, GemmaModelCatalog.e4b.deviceClassRamGb)
    }
}
