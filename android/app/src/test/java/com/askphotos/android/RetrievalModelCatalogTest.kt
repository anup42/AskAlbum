package com.askphotos.android

import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RetrievalModelCatalogTest {
    @Test
    fun catalogPinsExactSiglip2ArchiveAndRevisions() {
        val spec = RetrievalModelCatalog.siglip2BaseQuantized
        assertEquals("siglip2-base-p16-224-q8", spec.packId)
        assertEquals(267_744_234L, spec.archiveSizeBytes)
        assertEquals("5966d528a7ddf73be52a299251e5c0071d878ba1e0fcc70d39fcf38ec6a8f010", spec.archiveSha256)
        assertEquals("022b6f71160ffb0169ca4709e2d7e25be659598a", spec.sourceRevision)
        assertEquals("ba1f3b0843f24bc5417d38e19c37b287d719b2f4", spec.artifactRevision)
    }

    @Test
    fun endpointPolicyAcceptsOnlyPinnedReleaseHostsOverHttps() {
        val spec = RetrievalModelCatalog.siglip2BaseQuantized
        assertEquals("github.com", RetrievalDownloadEndpointPolicy.requireAllowed(URL(spec.downloadUrl)).host)
        assertEquals(
            "release-assets.githubusercontent.com",
            RetrievalDownloadEndpointPolicy.requireAllowed(URL("https://release-assets.githubusercontent.com/asset")).host,
        )
        assertThrows(IllegalArgumentException::class.java) {
            RetrievalDownloadEndpointPolicy.requireAllowed(URL("http://github.com/asset"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RetrievalDownloadEndpointPolicy.requireAllowed(URL("https://github.com.evil.example/asset"))
        }
    }
}
