package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FollowUpPlanContextTest {
    private val codec = GemmaPlanCodec()

    @Test
    fun modelCanSelectAContextualFollowUpWithoutAFixedPrefix() {
        val active = setOf("media-1", "media-2")
        val plan = codec.decode(
            "Narrow that to Marina Bay",
            planJson(true, "marina", "bay"),
            active,
        )

        assertEquals(active, plan.baseResultIds)
        assertEquals(listOf("marina", "bay"), plan.terms)
    }

    @Test
    fun modelCanKeepAStandaloneRequestOutsideTheActiveResultSet() {
        val plan = codec.decode(
            "Show beach photos",
            planJson(false, "beach"),
            setOf("media-1"),
        )

        assertNull(plan.baseResultIds)
    }

    private fun planJson(followUp: Boolean, vararg terms: String): String =
        """
        {
          "version": 1,
          "intent": "FIND_MEDIA",
          "followUp": $followUp,
          "mediaScope": "IMAGES",
          "terms": [${terms.joinToString(",") { "\"$it\"" }}],
          "semanticClauses": [],
          "peopleClauses": [],
          "grouping": "NONE",
          "sort": "RELEVANCE",
          "verification": "AUTO",
          "answerMode": "RESULTS_AND_SUMMARY",
          "limit": 32
        }
        """.trimIndent()
}
