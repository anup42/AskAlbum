package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GroundedEvidenceClosureTest {
    @Test
    fun answerPacketKeepsDirectVerificationInsideEvidenceLimit() {
        val plan = GalleryQueryPlan(
            originalQuery = "show beach photos",
            intent = QueryIntent.FIND_MEDIA,
            peopleClauses = emptyList(),
            semanticClauses = emptyList(),
        )
        val lowPriority = (1..GroundedEvidencePacketBuilder.MAX_EVIDENCE).map { index ->
            evidence("caption-$index", "m1", "semantic_caption", "A beach scene")
        }
        val hit = SearchHit(
            item("m1"),
            1.0,
            lowPriority + evidence("verified", "m1", "visual_verification", "P1 is at the beach"),
        )

        val packet = GroundedEvidencePacketBuilder.build(
            GroundedAnswerInput(plan, listOf(hit), baseline()),
        )

        assertTrue(packet.evidence.any { it.id == "verified" })
    }

    @Test
    fun capabilityAnswerCitationsKeepDirectEvidenceAndRejectForeignMedia() {
        val plan = GalleryQueryPlan(
            originalQuery = "show beach photos",
            intent = QueryIntent.FIND_MEDIA,
            peopleClauses = emptyList(),
            semanticClauses = emptyList(),
        )
        val lowPriority = (1..GroundedEvidencePacketBuilder.MAX_EVIDENCE).map { index ->
            evidence("caption-$index", "m1", "semantic_caption", "A beach scene")
        }
        val hit = SearchHit(
            item("m1"),
            1.0,
            lowPriority +
                evidence("foreign", "m2", "visual_verification", "A different beach") +
                evidence("verified", "m1", "visual_verification", "P1 is at the beach"),
        )

        val answer = CapabilityAnswerExecutor.execute(
            CapabilityAnswerContext(
                plan = plan,
                hits = listOf(hit),
                matchCount = 1,
                exactness = ResultExactness.ESTIMATED_FROM_RETRIEVAL,
                indexedEligibleCount = 1,
                totalEligibleCount = 1,
                warnings = emptyList(),
                channelReports = emptyList(),
            ),
        )

        assertTrue(answer.evidenceIds.contains("verified"))
        assertTrue("Foreign-media evidence must not be cited", "foreign" !in answer.evidenceIds)
    }

    @Test
    fun capabilityAnswerCitationsRejectContextualEvidenceForOrdinaryMediaSearch() {
        val plan = GalleryQueryPlan(
            originalQuery = "show beach photos",
            intent = QueryIntent.FIND_MEDIA,
        )
        val direct = evidence("direct", "m1", "semantic_caption", "A beach scene")
        val groupContext = direct.copy(
            id = "group-context",
            sourceField = "semantic_caption_candidate_expansion",
            scope = SemanticFactScope.VISUAL_GROUP,
            scopeId = "group-1",
            evidenceMediaId = "representative",
        )
        val eventContext = direct.copy(
            id = "event-context",
            sourceField = "event",
            scope = SemanticFactScope.EVENT,
            scopeId = "event-1",
            evidenceMediaId = "representative",
        )
        val answer = CapabilityAnswerExecutor.execute(
            CapabilityAnswerContext(
                plan = plan,
                hits = listOf(SearchHit(item("m1"), 1.0, listOf(direct, groupContext, eventContext))),
                matchCount = 1,
                exactness = ResultExactness.ESTIMATED_FROM_RETRIEVAL,
                indexedEligibleCount = 1,
                totalEligibleCount = 1,
                warnings = emptyList(),
                channelReports = emptyList(),
            ),
        )

        assertTrue(answer.evidenceIds.contains("direct"))
        assertTrue("Visual-group context must remain candidate-only", "group-context" !in answer.evidenceIds)
        assertTrue("Event context must not cite an ordinary media search", "event-context" !in answer.evidenceIds)
    }

    @Test
    fun groundedPromptPreservesVideoTimestampAndEvidenceRegion() {
        val record = evidence("video", "m1", "visual_verification", "Keyframe shows the beach")
            .copy(timestampMs = 12_500L, region = listOf(.1f, .2f, .7f, .9f))
        val packet = GroundedEvidencePacket(
            query = "show the beach video",
            baseline = baseline(),
            evidence = listOf(record),
        )

        val encoded = packet.toPromptJson().getJSONArray("evidence").getJSONObject(0)

        assertEquals(12_500L, encoded.getLong("timestampMs"))
        val region = encoded.getJSONArray("region")
        listOf(.1, .2, .7, .9).forEachIndexed { index, expected ->
            assertEquals(expected, region.getDouble(index), 0.000001)
        }
    }

    @Test
    fun personVisualAnswersUseOnlySameMediaVisualVerificationEvidence() {
        val plan = GalleryQueryPlan(
            originalQuery = "show wife wearing white",
            intent = QueryIntent.FIND_MEDIA,
            peopleClauses = listOf(PersonClause("wife")),
            semanticClauses = listOf(
                SemanticClause(
                    text = "wearing white",
                    subject = SemanticSubject.PERSON,
                    relationToPerson = "wife",
                    hardness = ConstraintStrength.HARD,
                ),
            ),
        )
        val hit = SearchHit(
            item("m1"),
            1.0,
            listOf(
                evidence("context", "m1", "semantic_caption_candidate_expansion", "Wife wears white"),
                evidence("foreign", "m2", "visual_verification", "P1 wears white"),
                evidence("direct", "m1", "visual_verification", "P1 wears white"),
            ),
        )

        val packet = GroundedEvidencePacketBuilder.build(
            GroundedAnswerInput(plan, listOf(hit), baseline()),
        )

        assertEquals(listOf("direct"), packet.evidence.map(EvidenceRecord::id))
    }

    @Test
    fun personVisualAnswersMayUseOnlyDirectCachedEvidenceForTheRequestedCluster() {
        val plan = GalleryQueryPlan(
            originalQuery = "show wife wearing white",
            intent = QueryIntent.FIND_MEDIA,
            peopleClauses = listOf(PersonClause("wife")),
            semanticClauses = listOf(
                SemanticClause(
                    text = "wearing white",
                    subject = SemanticSubject.PERSON,
                    relationToPerson = "wife",
                    hardness = ConstraintStrength.HARD,
                ),
            ),
        )
        val valid = evidence("cached-wife", "m1", "semantic_caption", "Wife wears a white dress").copy(
            scope = SemanticFactScope.MEDIA,
            scopeId = "m1",
            evidenceMediaId = "m1",
            clusterId = "wife",
        )
        val foreignCluster = valid.copy(id = "cached-me", clusterId = "me")
        val contextual = valid.copy(
            id = "group-context",
            scope = SemanticFactScope.VISUAL_GROUP,
            scopeId = "group-1",
            evidenceMediaId = "representative",
            applicability = SemanticProvenanceApplicability.GROUP_CONTEXT_ONLY,
        )

        assertTrue(GroundedEvidencePolicy.allow(valid, plan))
        assertTrue(!GroundedEvidencePolicy.allow(foreignCluster, plan))
        assertTrue(!GroundedEvidencePolicy.allow(contextual, plan))
    }

    @Test
    fun eventContextIsOnlyAnswerEvidenceForEventCapabilities() {
        val eventEvidence = evidence("event", "m1", "event", "Singapore trip")
            .copy(scope = SemanticFactScope.EVENT, scopeId = "event-1")
        val hit = SearchHit(item("m1"), 1.0, listOf(eventEvidence))
        val ordinary = GroundedEvidencePacketBuilder.build(
            GroundedAnswerInput(
                GalleryQueryPlan(originalQuery = "show Singapore photos", intent = QueryIntent.FIND_MEDIA, terms = listOf("Singapore")),
                listOf(hit),
                baseline(),
            ),
        )
        val event = GroundedEvidencePacketBuilder.build(
            GroundedAnswerInput(
                GalleryQueryPlan(originalQuery = "summarize Singapore trip", intent = QueryIntent.EVENT_SUMMARY, terms = listOf("Singapore")),
                listOf(hit),
                baseline(),
            ),
        )

        assertTrue(ordinary.evidence.isEmpty())
        assertEquals(listOf("event"), event.evidence.map(EvidenceRecord::id))
    }

    @Test
    fun possibleInferenceClaimsMustRemainUncertain() {
        val possible = evidence("occasion", "m1", "semantic_caption", "possible birthday celebration")
            .copy(applicability = SemanticProvenanceApplicability.POSSIBLE_INFERENCE)
        val packet = GroundedEvidencePacket(
            query = "birthday photos",
            baseline = baseline(),
            evidence = listOf(possible),
        )
        val certain = """{"headline":"Birthday celebration","detail":"Birthday celebration","claims":[{"text":"Birthday celebration","evidenceIds":["occasion"],"confidence":0.8}]}"""
        val uncertain = """{"headline":"Possible birthday celebration","detail":"Possible birthday celebration","claims":[{"text":"Possible birthday celebration","evidenceIds":["occasion"],"confidence":0.8}]}"""

        assertThrows(IllegalArgumentException::class.java) { GroundedAnswerCodec().decode(certain, packet) }
        assertEquals(listOf("occasion"), GroundedAnswerCodec().decode(uncertain, packet).evidenceIds)
    }

    @Test
    fun possibleInferenceCannotBeAssertedInHeadlineOrDetail() {
        val possible = evidence("occasion", "m1", "semantic_caption", "possible birthday celebration")
            .copy(applicability = SemanticProvenanceApplicability.POSSIBLE_INFERENCE)
        val packet = GroundedEvidencePacket(
            query = "birthday photos",
            baseline = baseline(),
            evidence = listOf(possible),
        )
        val certainHeader = """{"headline":"Birthday celebration","detail":"Birthday celebration","claims":[{"text":"Possible birthday celebration","evidenceIds":["occasion"],"confidence":0.8}]}"""

        assertThrows(IllegalArgumentException::class.java) {
            GroundedAnswerCodec().decode(certainHeader, packet)
        }
    }

    private fun evidence(id: String, mediaId: String, source: String, text: String) = EvidenceRecord(
        id = id,
        mediaId = mediaId,
        sourceField = source,
        text = text,
        confidence = .95f,
        producerVersion = "fixture",
    )

    private fun baseline() = SearchAnswer(
        headline = "Found 1 match",
        detail = "Local evidence was used.",
        evidenceIds = emptyList(),
        exactness = ResultExactness.ESTIMATED_FROM_RETRIEVAL,
        indexedEligibleCount = 1,
        totalEligibleCount = 1,
    )

    private fun item(id: String) = GalleryItem(
        id = id,
        filename = "$id.jpg",
        title = id,
        creator = null,
        location = "fixture",
        latitude = null,
        longitude = null,
        tags = emptyList(),
        description = "fixture",
        license = "CC0-1.0",
        sourceUrl = "local-fixture",
        assetPath = "images/$id.jpg",
    )
}
