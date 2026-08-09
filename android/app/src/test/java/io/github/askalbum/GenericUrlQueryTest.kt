package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class GenericUrlQueryTest {
    @Test
    fun linkQuestionSelectsTheAllowlistedUrlField() {
        val plan = QueryCompiler().compile("What is the link in the latest screenshot?")

        assertEquals(QueryIntent.ANSWER_FACT, plan.intent)
        assertEquals("url", plan.ocrClause?.requestedField)
    }
}
