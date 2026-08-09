package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveCapabilityAnswerTest {
    @Test
    fun sensitiveFactAnswersAreLockedBeforeFormatting() {
        val passwordHit = SearchHit(
            item = item("password-media"),
            score = 1.0,
            evidence = listOf(
                EvidenceRecord(
                    id = "password-evidence",
                    mediaId = "password-media",
                    sourceField = "document_password",
                    text = "mango-tree-2048",
                    confidence = .95f,
                ),
            ),
        )

        listOf(QueryIntent.ANSWER_FACT, QueryIntent.DOCUMENT_QA).forEach { intent ->
            val answer = CapabilityAnswerExecutor.execute(
                CapabilityAnswerContext(
                    plan = GalleryQueryPlan(
                        originalQuery = "What is the Wi-Fi password?",
                        intent = intent,
                        ocrClause = OcrClause(requestedField = "password"),
                    ),
                    hits = listOf(passwordHit),
                    matchCount = 1,
                    exactness = ResultExactness.EXACT,
                    indexedEligibleCount = 1,
                    totalEligibleCount = 1,
                    warnings = emptyList(),
                    channelReports = emptyList(),
                    deterministicHits = listOf(passwordHit),
                ),
            )

            assertEquals(SensitiveEvidencePolicy.LOCKED_HEADLINE, answer.headline)
            assertTrue(answer.requiresAuthentication)
            assertFalse(answer.detail.contains("mango-tree-2048"))
        }
    }

    @Test
    fun protectedDeterministicAnswerUsesOpaqueOneTimeStorage() {
        val store = OneTimeSensitiveAnswerStore(maximumEntries = 2)
        val protected = SearchAnswer(
            headline = "Wi-Fi password: mango-tree-2048",
            detail = "Read from the latest screenshot.",
            evidenceIds = listOf("password-evidence"),
            exactness = ResultExactness.EXACT,
            indexedEligibleCount = 1,
            totalEligibleCount = 1,
        )

        val token = store.put(protected)

        assertFalse(token.contains("mango-tree-2048"))
        assertEquals(protected, store.take(token))
        assertNull("A protected answer token must be one-use", store.take(token))
    }

    @Test
    fun protectedAnswerStoreEvictsOldestUnusedEntry() {
        val store = OneTimeSensitiveAnswerStore(maximumEntries = 1)
        val first = answer("first secret")
        val second = answer("second secret")

        val firstToken = store.put(first)
        val secondToken = store.put(second)

        assertNull(store.take(firstToken))
        assertEquals(second, store.take(secondToken))
    }

    private fun answer(value: String) = SearchAnswer(
        headline = value,
        detail = "Protected deterministic fact",
        evidenceIds = listOf("evidence"),
        exactness = ResultExactness.EXACT,
        indexedEligibleCount = 1,
        totalEligibleCount = 1,
    )

    private fun item(id: String) = GalleryItem(
        id = id,
        filename = "$id.png",
        title = id,
        creator = null,
        location = "fixture",
        latitude = null,
        longitude = null,
        tags = emptyList(),
        description = "fixture",
        license = "CC0-1.0",
        sourceUrl = "local-fixture",
        assetPath = "images/$id.png",
    )
}
