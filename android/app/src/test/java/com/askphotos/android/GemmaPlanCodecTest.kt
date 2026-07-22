package com.askphotos.android

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaPlanCodecTest {
    private val codec = GemmaPlanCodec()

    @Test
    fun decodesFullRelationalPlanAndDerivesFollowUpReference() {
        val active = setOf("m1", "m2")
        val json = """
            {"version":1,"intent":"FIND_MEDIA","mediaScope":"IMAGES","filter":{"op":"TIME_RANGE","startEpochMs":1735689600000,"endEpochMs":1767225599999},"semanticClauses":[{"text":"Person A has a yellow hat","canonicalText":"person a wearing yellow hat","polarity":"POSITIVE","hardness":"HARD","subject":"PERSON","relationToPerson":"person_a"},{"text":"no other person has a yellow hat","polarity":"NEGATIVE","hardness":"HARD","subject":"PERSON"}],"peopleClauses":[{"personId":"person_a","mustBePresent":true,"hardness":"HARD"}],"grouping":"EVENT","sort":"QUALITY","verification":"REQUIRED","answerMode":"RESULTS_AND_SUMMARY","limit":32,"terms":["yellow hat"],"place":"goa"}
        """.trimIndent()

        val plan = codec.decode("Sirf Goa wali", json, active)

        assertEquals(active, plan.baseResultIds)
        assertEquals(VerificationPolicy.REQUIRED, plan.verification)
        assertEquals(Polarity.NEGATIVE, plan.semanticClauses[1].polarity)
        assertEquals("person_a", plan.semanticClauses.first().relationToPerson)
        assertEquals(32, plan.limit)
    }

    @Test
    fun rejectsUnknownFieldsAndModelSuppliedResultIds() {
        val unsafe = """{"intent":"FIND_MEDIA","terms":["beach"],"baseResultIds":["invented"]}"""
        assertThrows(IllegalArgumentException::class.java) { codec.decode("beach", unsafe, null) }
    }

    @Test
    fun boundedCompilerRepairsExactlyOnce() = runBlocking {
        var calls = 0
        val compiler = BoundedGemmaPlanCompiler(codec)
        val plan = compiler.compile("beach sunset", null, "initial") {
            calls++
            if (calls == 1) "not json" else """{"intent":"FIND_MEDIA","terms":["beach","sunset"],"verification":"AUTO"}"""
        }

        assertEquals(2, calls)
        assertEquals(listOf("beach", "sunset"), plan.terms)
    }

    @Test
    fun validFirstResponseDoesNotSpendRepairCall() = runBlocking {
        var calls = 0
        val compiler = BoundedGemmaPlanCompiler(codec)
        compiler.compile("beach", null, "initial") {
            calls++
            """{"intent":"FIND_MEDIA","terms":["beach"]}"""
        }
        assertEquals(1, calls)
        assertFalse(calls > 1)
    }

    @Test
    fun repairPromptRestatesBoundedSchemaAndNumericRules() {
        val prompt = codec.repairPrompt("photos from 2024", "{broken", "unterminated")

        assertTrue(prompt.contains("Required shape:"))
        assertTrue(prompt.contains("\"version\":1"))
        assertTrue(prompt.contains("uninterrupted decimal digits"))
        assertTrue(prompt.contains("Omit optional fields"))
        assertTrue(prompt.contains("quoted scalar string"))
        assertTrue(prompt.contains("never be an array or object"))
    }
}
