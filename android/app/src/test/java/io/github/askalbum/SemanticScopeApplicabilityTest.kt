package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticScopeApplicabilityTest {
    @Test
    fun eventAndVisualGroupOutputsRemainContextual() {
        listOf(SemanticFactScope.EVENT, SemanticFactScope.VISUAL_GROUP).forEach { scope ->
            val result = SemanticEnrichmentCodec.decode(
                job(scope),
                rawOutput,
                "fixture",
                emptyList(),
            )

            assertEquals(SemanticProvenanceApplicability.GROUP_CONTEXT_ONLY, result.caption?.applicability)
            assertTrue(result.facts.isNotEmpty())
            assertTrue(result.facts.all { it.applicability == SemanticProvenanceApplicability.GROUP_CONTEXT_ONLY })
        }
    }

    private fun job(scope: SemanticFactScope) = SemanticEnrichmentJobRecord(
        id = "job-${scope.name}",
        scope = scope,
        subjectId = "${scope.name.lowercase()}-1",
        representativeMediaId = "representative-1",
        reason = "representative",
        status = SemanticEnrichmentStatus.PENDING,
        attemptCount = 0,
        userRequested = false,
    )

    private val rawOutput = """
        {
          "sceneSummary":"People are standing beside a decorated table.",
          "detailedCaption":"People are standing beside a decorated table indoors.",
          "captionConfidence":0.9,
          "facts":[{"predicate":"scene","value":"decorated table","confidence":0.9,"applicability":"EVIDENCE_MEDIA_ONLY"}],
          "people":[]
        }
    """.trimIndent()
}
