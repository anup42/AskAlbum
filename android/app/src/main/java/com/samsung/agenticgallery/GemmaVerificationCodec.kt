package com.samsung.agenticgallery

import org.json.JSONObject

/** Strict boundary for one-image visual-verification output. Media IDs never enter model output. */
class GemmaVerificationCodec {
    fun decode(response: String, expected: List<VerificationConditionSpec>): CandidateVerificationPayload {
        require(expected.isNotEmpty() && expected.size <= MAX_CONDITIONS) { "Invalid verification condition count" }
        require(expected.map { it.id }.distinct().size == expected.size) { "Duplicate expected condition ID" }
        val json = parseSingleObject(response)
        json.requireExactKeys("conditions", "overallMatch")
        val array = json.getJSONArray("conditions")
        require(array.length() == expected.size) { "Verifier must return every condition exactly once" }
        val byId = buildMap {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val keys = item.keys().asSequence().toSet()
                require(keys == setOf("id", "verdict", "confidence") || keys == setOf("id", "satisfied", "confidence")) {
                    "Verifier emitted missing or unsupported condition fields"
                }
                val id = item.getString("id")
                require(id in expected.map { it.id }) { "Verifier returned an unknown condition ID" }
                val verdict = if (item.has("verdict")) {
                    runCatching { PersonVisualVerdict.valueOf(item.getString("verdict")) }
                        .getOrElse { throw IllegalArgumentException("Verifier returned an invalid verdict") }
                } else if (item.getBoolean("satisfied")) {
                    PersonVisualVerdict.VERIFIED_TRUE
                } else {
                    PersonVisualVerdict.VERIFIED_FALSE
                }
                require(put(id, VerificationConditionEvaluation(
                    id = id,
                    satisfied = verdict == PersonVisualVerdict.VERIFIED_TRUE,
                    confidence = item.getDouble("confidence").toValidatedConfidence(),
                    verdict = verdict,
                )) == null) {
                    "Verifier returned a duplicate condition ID"
                }
            }
        }
        val ordered = expected.map { requireNotNull(byId[it.id]) { "Verifier omitted a condition" } }
        val kotlinOverall = expected.zip(ordered)
            .filter { (spec, _) -> spec.hardness == ConstraintStrength.HARD }
            .all { (_, evaluation) -> evaluation.satisfied }
        require(json.getBoolean("overallMatch") == kotlinOverall) { "Verifier overallMatch disagrees with hard conditions" }
        return CandidateVerificationPayload(ordered, kotlinOverall)
    }

    fun repairPrompt(
        expected: List<VerificationConditionSpec>,
        invalidResponse: String,
        error: String,
    ): String = """
        Your previous visual-verification JSON was invalid. Inspect the same image again and repair it once.
        Return exactly one JSON object and no markdown.
        Error: ${JSONObject.quote(error.take(240))}
        Required condition IDs in this exact set: ${expected.joinToString(prefix = "[", postfix = "]") { JSONObject.quote(it.id) }}
        Required shape: {"conditions":[{"id":"c1","verdict":"VERIFIED_TRUE","confidence":0.95}],"overallMatch":true}
        verdict must be VERIFIED_TRUE, VERIFIED_FALSE, AMBIGUOUS, or NOT_VISIBLE.
        Include every required ID exactly once. confidence must be a finite number from 0 to 1.
        overallMatch must be true exactly when every HARD condition is satisfied; SOFT conditions do not control it.
        Do not add media IDs, paths, URIs, explanations, boxes, tools, or any other fields.
        Invalid response: ${JSONObject.quote(invalidResponse.take(1200))}
    """.trimIndent()

    private fun parseSingleObject(text: String): JSONObject {
        val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        require(trimmed.startsWith('{') && trimmed.endsWith('}')) { "Verifier must return one JSON object" }
        return JSONObject(trimmed)
    }

    private fun JSONObject.requireExactKeys(vararg expected: String): JSONObject {
        val actual = keys().asSequence().toSet()
        require(actual == expected.toSet()) { "Verifier emitted missing or unsupported fields" }
        return this
    }

    private fun Double.toValidatedConfidence(): Float {
        require(isFinite() && this in 0.0..1.0) { "Verifier confidence must be from 0 to 1" }
        return toFloat()
    }

    private companion object {
        const val MAX_CONDITIONS = 16
    }
}

data class CandidateVerificationPayload(
    val conditions: List<VerificationConditionEvaluation>,
    val overallMatch: Boolean,
)

data class BoundedVerificationDecode(
    val payload: CandidateVerificationPayload,
    val generationCalls: Int,
)

/** One initial multimodal call and at most one repair call over the same image. */
class BoundedGemmaVerificationCompiler(private val codec: GemmaVerificationCodec = GemmaVerificationCodec()) {
    suspend fun compile(
        expected: List<VerificationConditionSpec>,
        initialPrompt: String,
        generate: suspend (String) -> String,
    ): BoundedVerificationDecode {
        var calls = 1
        val first = generate(initialPrompt)
        val payload = runCatching { codec.decode(first, expected) }.getOrElse { firstError ->
            calls++
            val repaired = generate(codec.repairPrompt(expected, first, firstError.message ?: "Invalid verification"))
            codec.decode(repaired, expected)
        }
        return BoundedVerificationDecode(payload, calls)
    }
}
