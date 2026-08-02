package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedRetrievalModelTest {
    @Test
    fun embeddedSpecPinsExactSiglip2ArchiveAndRevisions() {
        val spec = EmbeddedRetrievalModel.siglip2BaseQuantized
        assertEquals("models/retrieval/siglip2-base-p16-224-q8-core05.agretrieval", spec.assetPath)
        assertEquals("siglip2-base-p16-224-q8", spec.packId)
        assertEquals(267_744_234L, spec.archiveSizeBytes)
        assertEquals("5966d528a7ddf73be52a299251e5c0071d878ba1e0fcc70d39fcf38ec6a8f010", spec.archiveSha256)
        assertEquals("022b6f71160ffb0169ca4709e2d7e25be659598a", spec.sourceRevision)
        assertEquals("ba1f3b0843f24bc5417d38e19c37b287d719b2f4", spec.artifactRevision)
    }
}
