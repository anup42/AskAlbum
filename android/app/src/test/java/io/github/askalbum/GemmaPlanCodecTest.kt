package io.github.anup42.askalbum

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
    fun safelyDowngradesStringSemanticClauseShorthandToLexicalTerm() {
        val plan = codec.decode(
            "Pichle saal Goa wali family photos dikhao.",
            """{"intent":"FIND_MEDIA","semanticClauses":["family photos in Goa"]}""",
            null,
        )

        assertEquals(listOf("family photos in goa"), plan.terms)
        assertTrue(plan.semanticClauses.isEmpty())
    }

    @Test
    fun rejectsUnknownFieldsAndModelSuppliedResultIds() {
        val unsafe = """{"intent":"FIND_MEDIA","terms":["beach"],"baseResultIds":["invented"]}"""
        assertThrows(IllegalArgumentException::class.java) { codec.decode("beach", unsafe, null) }
    }

    @Test
    fun rejectsCategoryAsSubjectWithExactRepairGuidance() {
        val invalid = """{"intent":"FIND_MEDIA","terms":["family"],"semanticClauses":[{"text":"family","subject":"FAMILY"}]}"""

        val error = assertThrows(IllegalArgumentException::class.java) {
            codec.decode("family photos", invalid, null)
        }

        assertTrue(error.message.orEmpty().contains("\"subject\" must be one of [WHOLE_MEDIA, PERSON, EVENT, DOCUMENT]"))
        assertTrue(error.message.orEmpty().contains("received \"FAMILY\""))
        val repair = codec.repairPrompt("family photos", invalid, error.message.orEmpty())
        assertTrue(repair.contains("family, pet, trip, food, or clothing belong in text/canonicalText"))
        assertTrue(repair.contains("never in subject"))
        assertTrue(repair.contains("use terms/place and return semanticClauses as []"))
        assertTrue(repair.contains("Use semanticClauses only for relational, negative, comparative, or fine-grained visual conditions"))
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
    fun ordinaryTermsRemainLexicalInsteadOfBecomingSemanticPredicates() {
        val plan = codec.decode(
            "Show beach sunset photos",
            """{"intent":"FIND_MEDIA","mediaScope":"IMAGES","terms":["beach","sunset"],"verification":"AUTO"}""",
            null,
        )

        assertEquals(listOf("beach", "sunset"), plan.terms)
        assertTrue(plan.semanticClauses.isEmpty())
        assertTrue(SemanticQueryVariants.from(plan).contains(plan.originalQuery))
    }

    @Test
    fun numericAggregationTermsDoNotCreateSemanticPredicates() {
        val plan = codec.decode(
            "Sum my receipt totals",
            """{"intent":"SUM","terms":["receipt","total"],"aggregation":{"operation":"SUM","field":"total"}}""",
            null,
        )

        assertTrue(plan.semanticClauses.isEmpty())
        assertEquals("total", plan.aggregation?.field)
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
        assertTrue(prompt.contains("Allowed root fields are exactly:"))
        assertTrue(prompt.contains("followUp,mediaScope"))
        assertTrue(prompt.contains("Same event but videos"))
        assertTrue(prompt.contains("never emit event, eventId, eventScope"))
    }

    @Test
    fun referentialCountCannotBeDescopedOrPollutedByModelFillerTerms() {
        val active = setOf("sunset-one", "sunset-two")
        val plan = codec.decode(
            "How many are there?",
            """{"intent":"COUNT","followUp":false,"terms":["many","there"],"semanticClauses":[{"text":"there","subject":"WHOLE_MEDIA"}]}""",
            active,
        )

        assertEquals(QueryIntent.COUNT, plan.intent)
        assertEquals(active, plan.baseResultIds)
        assertTrue(plan.terms.isEmpty())
        assertTrue(plan.semanticClauses.isEmpty())
    }

    @Test
    fun decodesBoundedComparisonScopes() {
        val plan = codec.decode(
            "Compare Goa and Singapore",
            """{"intent":"COMPARE","comparisonScopes":["goa","singapore"],"terms":["goa","singapore"],"grouping":"NONE"}""",
            null,
        )

        assertEquals(listOf("goa", "singapore"), plan.comparisonScopes)
    }

    @Test
    fun emptyOrNullFilterOperationMeansNoHardFilter() {
        listOf(
            """{"intent":"FIND_MEDIA","mediaScope":"IMAGES","filter":{},"terms":["family"]}""",
            """{"intent":"FIND_MEDIA","mediaScope":"IMAGES","filter":{"op":null},"terms":["family"]}""",
        ).forEach { response ->
            val plan = codec.decode("family photos", response, null)
            assertEquals(FilterExpression.True, plan.filter)
        }
    }

    @Test
    fun infersOnlyUnambiguousMissingFilterOperations() {
        val plan = codec.decode(
            "Goa photos from 2024",
            """{"intent":"FIND_MEDIA","mediaScope":"IMAGES","filter":{"startEpochMs":1704067200000,"endEpochMs":1735689599999},"terms":["goa"]}""",
            null,
        )

        assertEquals(FilterExpression.TimeRange(1704067200000, 1735689599999), plan.filter)
    }

    @Test
    fun termsOnlyFilterObjectIsPromotedToLexicalTerms() {
        listOf(
            """{"intent":"FIND_MEDIA","mediaScope":"IMAGES","filter":{"terms":["Goa","family"]}}""",
            """{"intent":"FIND_MEDIA","mediaScope":"IMAGES","filter":{"terms":"Goa family"}}""",
        ).forEachIndexed { index, response ->
            val plan = codec.decode("Goa family photos", response, null)
            assertEquals(if (index == 0) listOf("goa", "family") else listOf("goa family"), plan.terms)
            assertEquals(FilterExpression.True, plan.filter)
        }
    }

    @Test
    fun termsOnlyNestedFilterClauseIsNotAVisualPredicate() {
        val plan = codec.decode(
            "Goa family photos",
            """{"intent":"FIND_MEDIA","mediaScope":"IMAGES","filter":{"op":"AND","clauses":[{"terms":["family"]}]},"terms":["Goa"]}""",
            null,
        )

        assertEquals(FilterExpression.And(listOf(FilterExpression.True)), plan.filter)
        assertEquals(listOf("goa"), plan.terms)
    }

    @Test
    fun listAnswerModeAliasKeepsMediaSearchGrounded() {
        val plan = codec.decode(
            "Show Goa photos",
            """{"intent":"FIND_MEDIA","mediaScope":"IMAGES","answerMode":"LIST","terms":["Goa"]}""",
            null,
        )

        assertEquals(AnswerMode.RESULTS_AND_SUMMARY, plan.answerMode)
    }

    @Test
    fun listScopeWordsDoNotCreateAFalseSemanticPredicate() {
        val plan = codec.decode(
            "List places in Goa",
            """{"intent":"LIST","grouping":"PLACE","place":"Goa","terms":["places","Goa"]}""",
            null,
        )

        assertEquals(emptyList<String>(), plan.terms)
        assertTrue(plan.semanticClauses.isEmpty())
        assertEquals("Goa", plan.place)
    }
}
