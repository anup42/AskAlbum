package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class GenericPasswordQueryTest {
    @Test
    fun genericPasswordQuestionSelectsTheProtectedPasswordField() {
        val plan = QueryCompiler().compile("What is the password in the latest screenshot?")

        assertEquals(QueryIntent.ANSWER_FACT, plan.intent)
        assertEquals("password", plan.ocrClause?.requestedField)
    }

    @Test
    fun passcodeQuestionUsesTheSameAllowlistedField() {
        val plan = QueryCompiler().compile("Show the passcode from my screenshot.")

        assertEquals(QueryIntent.ANSWER_FACT, plan.intent)
        assertEquals("password", plan.ocrClause?.requestedField)
    }
}
