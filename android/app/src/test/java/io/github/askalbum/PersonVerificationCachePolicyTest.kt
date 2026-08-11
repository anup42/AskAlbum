package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonVerificationCachePolicyTest {
    private val me = binding("me-cluster", "P1", .10f)
    private val wife = binding("wife-cluster", "P2", .55f)
    private val meCondition = condition("c1", "P1 is wearing white", "me-cluster")
    private val wifeCondition = condition("c2", "P2 is wearing a white dress", "wife-cluster")

    @Test
    fun exactClusterBoundFactsAreReusedWithoutSwappingPeople() {
        val cached = PersonVerificationCachePolicy.resolve(
            mediaId = MEDIA_ID,
            conditions = listOf(meCondition, wifeCondition),
            bindings = listOf(me, wife),
            facts = listOf(
                fact(me, meCondition.text, PersonVisualVerdict.VERIFIED_FALSE),
                fact(wife, wifeCondition.text, PersonVisualVerdict.VERIFIED_TRUE),
            ),
            activeProducerVersion = MODEL_VERSION,
        )

        assertNotNull(cached)
        val result = requireNotNull(cached)
        assertFalse(result.candidate.overallMatch)
        assertEquals(
            mapOf("c1" to PersonVisualVerdict.VERIFIED_FALSE, "c2" to PersonVisualVerdict.VERIFIED_TRUE),
            result.candidate.conditions.associate { it.id to it.verdict },
        )
        assertEquals(setOf("wife-cluster"), result.evidence.mapNotNull(EvidenceRecord::clusterId).toSet())
    }

    @Test
    fun negativePredicateReusesOnlyVerifiedContradiction() {
        val negative = meCondition.copy(polarity = Polarity.NEGATIVE)
        val cached = requireNotNull(
            PersonVerificationCachePolicy.resolve(
                MEDIA_ID,
                listOf(negative),
                listOf(me),
                listOf(fact(me, negative.text, PersonVisualVerdict.VERIFIED_FALSE)),
                MODEL_VERSION,
            ),
        )

        assertTrue(cached.candidate.overallMatch)
        assertEquals(1, cached.evidence.size)
        assertTrue(cached.evidence.single().text.startsWith("No visible"))
    }

    @Test
    fun staleAmbiguousOrCrossFaceFactsCannotBypassInference() {
        val valid = fact(me, meCondition.text, PersonVisualVerdict.VERIFIED_TRUE)
        val invalid = listOf(
            valid.copy(modelVersion = "old-model"),
            valid.copy(promptVersion = "old-prompt"),
            valid.copy(bodyRegionVersion = "old-body-regions"),
            valid.copy(associationStatus = PersonAssociationStatus.AMBIGUOUS),
            valid.copy(faceRegion = listOf(.55f, .1f, .75f, .5f)),
            valid.copy(clusterId = "wife-cluster"),
            valid.copy(predicate = "P1 is wearing black"),
        )
        invalid.forEach { candidate ->
            assertNull(
                PersonVerificationCachePolicy.resolve(
                    MEDIA_ID,
                    listOf(meCondition),
                    listOf(me, wife),
                    listOf(candidate),
                    MODEL_VERSION,
                ),
            )
        }
    }

    private fun binding(clusterId: String, stableLabel: String, left: Float) = PersonVerificationBinding(
        faceId = "face-$clusterId",
        clusterId = clusterId,
        stableLabel = stableLabel,
        identityTerms = setOf(clusterId),
        left = left,
        top = .1f,
        right = left + .2f,
        bottom = .5f,
    )

    private fun condition(id: String, text: String, clusterId: String) = VerificationConditionSpec(
        id = id,
        text = text,
        polarity = Polarity.POSITIVE,
        hardness = ConstraintStrength.HARD,
        subject = SemanticSubject.PERSON,
        relationToPerson = clusterId,
    )

    private fun fact(
        binding: PersonVerificationBinding,
        predicate: String,
        verdict: PersonVisualVerdict,
    ) = PersonVisualFactRecord(
        mediaId = MEDIA_ID,
        clusterId = binding.clusterId,
        personRef = binding.stableLabel,
        relation = PersonVisualRelation.ACTION,
        value = verdict.name,
        confidence = .95f,
        faceRegion = listOf(binding.left, binding.top, binding.right, binding.bottom),
        associationStatus = PersonAssociationStatus.CONFIDENT,
        verdict = verdict,
        modelVersion = MODEL_VERSION,
        promptVersion = PersonVerificationCachePolicy.PROMPT_VERSION,
        bodyRegionVersion = PersonalSemanticMemoryPolicy.BODY_REGION_VERSION,
        updatedAt = 10L,
        predicate = predicate,
    )

    private companion object {
        const val MEDIA_ID = "media-1"
        const val MODEL_VERSION = "gemma-4-e2b-pack-1"
    }
}
