package com.samsung.agenticgallery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundedAnswerCodecTest {
    private val codec = GroundedAnswerCodec()
    private val packet = packet()

    @Test
    fun decodesClaimsWithOnlyKnownEvidenceAndPreservesDeterministicFields() {
        val answer = codec.decode(
            """{"headline":"Found 1 verified match","detail":"Person A wears a yellow hat and Person B wears a blue suit.","claims":[{"text":"Person A wears a yellow hat.","evidenceIds":["EV1"],"confidence":0.96},{"text":"Person B wears a blue suit.","evidenceIds":["EV2"],"confidence":0.94}]}""",
            packet,
        )

        assertEquals(listOf("EV1", "EV2"), answer.evidenceIds)
        assertEquals(2, answer.claims.size)
        assertEquals(ResultExactness.ESTIMATED_FROM_RETRIEVAL, answer.exactness)
        assertEquals(1, answer.indexedEligibleCount)
        assertEquals(1, answer.totalEligibleCount)
    }

    @Test
    fun rejectsUnknownIdsUnsupportedNumbersDatesPathsAndFields() {
        val invalid = listOf(
            """{"headline":"Found 1 verified match","detail":"Supported","claims":[{"text":"Person A wears a yellow hat.","evidenceIds":["INVENTED"],"confidence":0.9}]}""",
            """{"headline":"Found 99 verified matches","detail":"Supported","claims":[{"text":"Person A wears a yellow hat.","evidenceIds":["EV1"],"confidence":0.9}]}""",
            """{"headline":"Found 1 verified match","detail":"This happened in July.","claims":[{"text":"Person A wears a yellow hat.","evidenceIds":["EV1"],"confidence":0.9}]}""",
            """{"headline":"Found 1 verified match","detail":"Open content://invented/media/4","claims":[{"text":"Person A wears a yellow hat.","evidenceIds":["EV1"],"confidence":0.9}]}""",
            """{"headline":"Found 1 verified match","detail":"Person A wears a yellow hat in Paris.","claims":[{"text":"Person A wears a yellow hat in Paris.","evidenceIds":["EV1"],"confidence":0.9}]}""",
            """{"headline":"Found 1 verified match","detail":"Supported","claims":[{"text":"Person A wears a yellow hat.","evidenceIds":["EV1"],"confidence":0.9}],"mediaId":"invented"}""",
        )
        invalid.forEach { response ->
            assertThrows(RuntimeException::class.java) { codec.decode(response, packet) }
        }
    }

    @Test
    fun rejectsUnrelatedClaimEvenWhenItBorrowsAnEvidenceId() {
        val response = """{"headline":"Found 1 verified match","detail":"Supported","claims":[{"text":"A helicopter landed nearby.","evidenceIds":["EV1"],"confidence":0.9}]}"""
        assertThrows(IllegalArgumentException::class.java) { codec.decode(response, packet) }
    }

    @Test
    fun packetBuilderIncludesOnlyEvidenceOwnedByActiveHitsAndStaysBounded() {
        val item = item("m1")
        val evidence = (1..30).map { index -> evidence("E$index", if (index == 30) "other" else "m1", "yellow hat $index") }
        val built = GroundedEvidencePacketBuilder.build(
            GroundedAnswerInput(
                plan = plan(),
                hits = listOf(SearchHit(item, 1.0, evidence)),
                deterministicAnswer = baseline(),
            ),
        )

        assertEquals(GroundedEvidencePacketBuilder.MAX_EVIDENCE, built.evidence.size)
        assertTrue(built.evidence.all { it.mediaId == "m1" })
        assertFalse(built.evidence.any { it.id == "E30" })
    }

    @Test
    fun sensitiveEvidenceIsLockedAndRejectedBeforeGemmaPacketConstruction() {
        val item = item("private").copy(ocrText = "Wi-Fi password: mango-tree-2048")
        val hit = SearchHit(
            item,
            1.0,
            listOf(evidence("PRIVATE", item.id, "Password: mango-tree-2048")),
        )
        val locked = SensitiveEvidencePolicy.lock(baseline())

        assertTrue(SensitiveEvidencePolicy.requiresAuthentication(hit))
        assertTrue(locked.requiresAuthentication)
        assertTrue(locked.evidenceIds.isEmpty())
        assertTrue(locked.claims.isEmpty())
        assertFalse(locked.headline.contains("mango", ignoreCase = true))
        assertThrows(IllegalArgumentException::class.java) {
            GroundedEvidencePacketBuilder.build(
                GroundedAnswerInput(plan(), listOf(hit), baseline()),
            )
        }
    }

    @Test
    fun boundedCompilerUsesAtMostOneRepair() = runBlocking {
        var calls = 0
        val decoded = BoundedGemmaAnswerCompiler(codec).compile(packet, "initial") {
            calls++
            if (calls == 1) "not-json" else """{"headline":"Found 1 verified match","detail":"Person A wears a yellow hat.","claims":[{"text":"Person A wears a yellow hat.","evidenceIds":["EV1"],"confidence":0.9}]}"""
        }

        assertEquals(2, calls)
        assertEquals(2, decoded.generationCalls)
        assertEquals(listOf("EV1"), decoded.answer.evidenceIds)
    }

    private fun packet() = GroundedEvidencePacket(
        query = "Show Person A wearing a yellow hat and Person B wearing a blue suit",
        baseline = baseline(),
        evidence = listOf(
            evidence("EV1", "m1", "Person A is wearing a yellow hat"),
            evidence("EV2", "m1", "Person B is wearing a blue suit"),
        ),
    )

    private fun baseline() = SearchAnswer(
        headline = "Found 1 verified match",
        detail = "All 3 required visual conditions were checked locally.",
        evidenceIds = listOf("EV1", "EV2"),
        exactness = ResultExactness.ESTIMATED_FROM_RETRIEVAL,
        indexedEligibleCount = 1,
        totalEligibleCount = 1,
    )

    private fun evidence(id: String, mediaId: String, text: String) = EvidenceRecord(
        id = id,
        mediaId = mediaId,
        sourceField = "visual_verification",
        text = text,
        confidence = .95f,
        producerVersion = "gemma-4-e2b-test",
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

    private fun plan() = GalleryQueryPlan(
        originalQuery = "Show Person A wearing a yellow hat",
        intent = QueryIntent.FIND_MEDIA,
        terms = listOf("yellow hat"),
    )
}
