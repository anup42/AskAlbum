package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticGenerationProvenanceTest {
    @Test
    fun oneDecodedGemmaResponseSharesGenerationAcrossFactsCaptionAndChunks() {
        val job = SemanticEnrichmentJobRecord(
            id = "generation-job",
            scope = SemanticFactScope.MEDIA,
            subjectId = "media-1",
            representativeMediaId = "media-1",
            reason = "fixture",
            status = SemanticEnrichmentStatus.RUNNING,
            attemptCount = 1,
            userRequested = false,
        )
        val generation = SemanticGenerationProvenance(
            generationId = "generation-1",
            jobId = job.id,
            scope = job.scope,
            scopeId = job.subjectId,
            evidenceMediaId = job.representativeMediaId,
            modelVersion = "gemma-fixture",
            promptVersion = SemanticEnrichmentCodec.PROMPT_VERSION,
            bodyRegionVersion = PersonalSemanticMemoryPolicy.BODY_REGION_VERSION,
            createdAt = 1L,
        )
        val result = SemanticEnrichmentCodec.decode(
            job = job,
            raw = """
                {
                  "sceneSummary":"A red car is parked outdoors.",
                  "detailedCaption":"A red car is parked outdoors beside a sidewalk.",
                  "captionConfidence":0.9,
                  "people":[],
                  "facts":[{"predicate":"scene","value":"red car","confidence":0.9,"applicability":"EVIDENCE_MEDIA_ONLY"}]
                }
            """.trimIndent(),
            modelVersion = generation.modelVersion,
            bindings = emptyList(),
            generation = generation,
        )

        assertEquals(generation.generationId, result.generation?.generationId)
        assertEquals(generation.generationId, result.caption?.generationId)
        assertTrue(result.facts.isNotEmpty())
        assertTrue(result.facts.all { it.generationId == generation.generationId })

        val chunks = SemanticCaptionChunker.generate(
            requireNotNull(result.caption),
            result.facts,
            result.personFacts,
        )
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.generationId == generation.generationId })
    }
}
