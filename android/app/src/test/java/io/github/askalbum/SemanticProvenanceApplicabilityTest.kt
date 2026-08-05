package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticProvenanceApplicabilityTest {
    @Test
    fun onlyExplicitlySafeFactsCanBeSharedAcrossExactDuplicates() {
        assertTrue(
            SemanticProvenanceApplicability.isSafeForExactDuplicateSharing(
                SemanticProvenanceApplicability.SAFE_FOR_EXACT_DUPLICATES,
            ),
        )
        assertFalse(
            SemanticProvenanceApplicability.isSafeForExactDuplicateSharing("EVIDENCE_MEDIA_ONLY"),
        )
        assertFalse(
            SemanticProvenanceApplicability.isSafeForExactDuplicateSharing(
                SemanticProvenanceApplicability.POSSIBLE_INFERENCE,
            ),
        )
    }

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
