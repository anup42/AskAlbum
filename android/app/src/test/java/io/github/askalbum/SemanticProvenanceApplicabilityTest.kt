package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticProvenanceApplicabilityTest {
    @Test
    fun exactDuplicateGroupRequiresExplicitSafeApplicability() {
        assertTrue(
            SemanticProvenanceApplicability.isDirect(
                scope = "EXACT_DUPLICATE_GROUP",
                applicability = SemanticProvenanceApplicability.SAFE_FOR_EXACT_DUPLICATES,
                mediaId = "member",
                evidenceMediaId = "representative",
            ),
        )
        assertFalse(
            SemanticProvenanceApplicability.isDirect(
                scope = "EXACT_DUPLICATE_GROUP",
                applicability = "EVIDENCE_MEDIA_ONLY",
                mediaId = "member",
                evidenceMediaId = "representative",
            ),
        )
    }
}
