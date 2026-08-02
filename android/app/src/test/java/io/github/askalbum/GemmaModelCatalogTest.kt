package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URL

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

    @Test
    fun modelDownloadPolicyAllowsOnlyPinnedHttpsHosting() {
        GemmaModelCatalog.all.forEach { spec ->
            assertEquals(URL(spec.downloadUrl), ModelDownloadEndpointPolicy.requireAllowed(URL(spec.downloadUrl)))
        }
        assertEquals(
            "cas-bridge.xethub.hf.co",
            ModelDownloadEndpointPolicy.requireAllowed(URL("https://cas-bridge.xethub.hf.co/model")).host,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ModelDownloadEndpointPolicy.requireAllowed(URL("http://huggingface.co/model"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelDownloadEndpointPolicy.requireAllowed(URL("https://firebaselogging.googleapis.com/model"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelDownloadEndpointPolicy.requireAllowed(URL("https://huggingface.co.evil.example/model"))
        }
    }
}
