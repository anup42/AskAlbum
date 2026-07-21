package com.askphotos.android

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaVerificationCodecTest {
    private val codec = GemmaVerificationCodec()
    private val conditions = listOf(
        condition("c1", ConstraintStrength.HARD),
        condition("c2", ConstraintStrength.HARD),
        condition("c3", ConstraintStrength.SOFT),
    )

    @Test
    fun decodesExactConditionSetAndOrdersByKotlinSpec() {
        val decoded = codec.decode(
            """{"conditions":[{"id":"c2","satisfied":true,"confidence":0.8},{"id":"c3","satisfied":false,"confidence":0.6},{"id":"c1","satisfied":true,"confidence":0.9}],"overallMatch":true}""",
            conditions,
        )

        assertEquals(listOf("c1", "c2", "c3"), decoded.conditions.map { it.id })
        assertTrue(decoded.overallMatch)
        assertFalse(decoded.conditions.last().satisfied)
    }

    @Test
    fun derivesOverallMatchFromHardConditionsOnly() {
        val decoded = codec.decode(
            """{"conditions":[{"id":"c1","satisfied":true,"confidence":1.0},{"id":"c2","satisfied":true,"confidence":0.8},{"id":"c3","satisfied":false,"confidence":0.5}],"overallMatch":true}""",
            conditions,
        )
        assertTrue(decoded.overallMatch)
    }

    @Test
    fun rejectsUnknownMissingDuplicateFieldsAndInvalidConfidence() {
        val validItems = """{"id":"c1","satisfied":true,"confidence":0.9},{"id":"c2","satisfied":true,"confidence":0.8},{"id":"c3","satisfied":true,"confidence":0.7}"""
        val invalid = listOf(
            """{"conditions":[$validItems],"overallMatch":true,"mediaId":"invented"}""",
            """{"conditions":[{"id":"c1","satisfied":true,"confidence":0.9}],"overallMatch":true}""",
            """{"conditions":[{"id":"c1","satisfied":true,"confidence":0.9},{"id":"c1","satisfied":true,"confidence":0.8},{"id":"c3","satisfied":true,"confidence":0.7}],"overallMatch":true}""",
            """{"conditions":[{"id":"c1","satisfied":true,"confidence":1.1},{"id":"c2","satisfied":true,"confidence":0.8},{"id":"c3","satisfied":true,"confidence":0.7}],"overallMatch":true}""",
            """{"conditions":[$validItems],"overallMatch":false}""",
        )
        invalid.forEach { response ->
            assertThrows(RuntimeException::class.java) { codec.decode(response, conditions) }
        }
    }

    @Test
    fun boundedCompilerRepairsOnlyOnce() = runBlocking {
        var calls = 0
        val decoded = BoundedGemmaVerificationCompiler(codec).compile(conditions, "initial") {
            calls++
            if (calls == 1) "not-json" else """{"conditions":[{"id":"c1","satisfied":true,"confidence":0.9},{"id":"c2","satisfied":true,"confidence":0.8},{"id":"c3","satisfied":false,"confidence":0.6}],"overallMatch":true}"""
        }

        assertEquals(2, calls)
        assertEquals(2, decoded.generationCalls)
        assertTrue(decoded.payload.overallMatch)
    }

    @Test
    fun policyOnlyLoadsGemmaForHardVisualWork() {
        val ordinary = plan(VerificationPolicy.AUTO, SemanticClause("beach sunset"))
        val relation = plan(
            VerificationPolicy.AUTO,
            SemanticClause("Person A is wearing a yellow hat", hardness = ConstraintStrength.HARD, subject = SemanticSubject.PERSON),
        )

        assertFalse(VisualVerificationPolicy.requiresVerification(ordinary))
        assertTrue(VisualVerificationPolicy.requiresVerification(relation))
        assertFalse(VisualVerificationPolicy.requiresVerification(relation.copy(verification = VerificationPolicy.NEVER)))
        assertEquals(listOf("c1"), VisualVerificationPolicy.conditions(relation).map { it.id })
        assertEquals(LiteRtGemmaVisualVerifier.MAX_CANDIDATES, 8)
    }

    @Test
    fun imageSourceValidationRejectsTraversalAndNonPrivateFiles() {
        assertEquals("images/sample.png", GalleryImageLoader.requireSafeAssetPath("images/sample.png"))
        listOf("../sample.png", "/images/sample.png", "images\\sample.png", "images//sample.png").forEach { unsafe ->
            assertThrows(IllegalArgumentException::class.java) { GalleryImageLoader.requireSafeAssetPath(unsafe) }
        }
        val root = File(System.getProperty("java.io.tmpdir"), "gallery-private").canonicalFile
        assertTrue(GalleryImageLoader.isWithinRoots(File(root, "preview/image.jpg"), listOf(root)))
        assertFalse(GalleryImageLoader.isWithinRoots(File(root.parentFile, "outside.jpg"), listOf(root)))
    }

    private fun condition(id: String, hardness: ConstraintStrength) = VerificationConditionSpec(
        id = id,
        text = id,
        polarity = Polarity.POSITIVE,
        hardness = hardness,
        subject = SemanticSubject.WHOLE_MEDIA,
        relationToPerson = null,
    )

    private fun plan(policy: VerificationPolicy, clause: SemanticClause) = GalleryQueryPlan(
        originalQuery = clause.text,
        intent = QueryIntent.FIND_MEDIA,
        semanticClauses = listOf(clause),
        terms = listOf(clause.text),
        verification = policy,
    )
}
