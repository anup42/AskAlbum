package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticCaptionProvenanceTest {
    @Test
    fun legacyCaptionUsesTextOnlyAndCannotReceivePersonFacts() {
        val job = SemanticEnrichmentJobRecord(
            id = "legacy-job",
            scope = SemanticFactScope.MEDIA,
            subjectId = "media-1",
            representativeMediaId = "media-1",
            reason = "legacy",
            status = SemanticEnrichmentStatus.COMPLETE,
            attemptCount = 1,
            userRequested = false,
        )
        val generated = SemanticGenerationProvenance(
            generationId = "new-generation",
            jobId = job.id,
            scope = job.scope,
            scopeId = job.subjectId,
            evidenceMediaId = job.representativeMediaId,
            modelVersion = "gemma-fixture",
            promptVersion = SemanticEnrichmentCodec.PROMPT_VERSION,
            bodyRegionVersion = PersonalSemanticMemoryPolicy.BODY_REGION_VERSION,
            createdAt = 1L,
        )
        val legacy = SemanticEnrichmentCodec.decode(
            job = job,
            raw = """
                {
                  "sceneSummary":"A family portrait indoors.",
                  "detailedCaption":"A family portrait indoors near a table.",
                  "captionConfidence":0.8,
                  "people":[],
                  "facts":[{"predicate":"scene","value":"family portrait","confidence":0.8}]
                }
            """.trimIndent(),
            modelVersion = generated.modelVersion,
            bindings = emptyList(),
            generation = null,
        )
        val newer = SemanticEnrichmentCodec.decode(
            job = job,
            raw = """
                {
                  "sceneSummary":"P1 is holding a gift.",
                  "detailedCaption":"P1 is holding a gift indoors.",
                  "captionConfidence":0.9,
                  "people":[{"personRef":"P1","actions":["holding a gift"]}],
                  "facts":[{"predicate":"holding","value":"gift","confidence":0.9}]
                }
            """.trimIndent(),
            modelVersion = generated.modelVersion,
            bindings = emptyList(),
            generation = generated,
        )

        val chunks = SemanticCaptionChunker.generate(
            requireNotNull(legacy.caption),
            newer.facts,
            newer.personFacts,
        )

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.applicability == SemanticProvenanceApplicability.LEGACY_UNCORRELATED })
        assertFalse(chunks.any { it.clusterId != null })
        assertEquals(null, legacy.caption?.generationId)
    }
}
