package com.askphotos.android

import java.math.BigDecimal
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class GroundedEvidencePacket(
    val query: String,
    val baseline: SearchAnswer,
    val evidence: List<EvidenceRecord>,
)

object GroundedEvidencePacketBuilder {
    const val MAX_EVIDENCE = 24

    fun build(input: GroundedAnswerInput): GroundedEvidencePacket {
        val baseline = requireNotNull(input.deterministicAnswer) { "A deterministic baseline answer is required" }
        val activeMediaIds = input.hits.mapTo(mutableSetOf()) { it.item.id }
        val evidence = input.hits.asSequence()
            .flatMap { it.evidence.asSequence() }
            .filter { it.mediaId in activeMediaIds }
            .distinctBy { it.id }
            .take(MAX_EVIDENCE)
            .toList()
        require(evidence.map { it.id }.distinct().size == evidence.size) { "Evidence IDs must be unique" }
        return GroundedEvidencePacket(input.plan.originalQuery, baseline, evidence)
    }
}

/** Strict evidence-only output boundary for the optional answer-wording call. */
class GroundedAnswerCodec {
    fun decode(response: String, packet: GroundedEvidencePacket): SearchAnswer {
        require(packet.evidence.isNotEmpty()) { "Grounded generation requires evidence" }
        val json = parseSingleObject(response).requireExactKeys("headline", "detail", "claims")
        val headline = json.getString("headline").validatedText("headline", MAX_HEADLINE_CHARS)
        val detail = json.getString("detail").validatedText("detail", MAX_DETAIL_CHARS)
        val known = packet.evidence.associateBy { it.id }
        val claimsJson = json.getJSONArray("claims")
        require(claimsJson.length() in 1..MAX_CLAIMS) { "Grounded answer must contain 1..$MAX_CLAIMS claims" }
        val claims = List(claimsJson.length()) { index ->
            val item = claimsJson.getJSONObject(index).requireExactKeys("text", "evidenceIds", "confidence")
            val text = item.getString("text").validatedText("claim", MAX_CLAIM_CHARS)
            val ids = item.getJSONArray("evidenceIds").strings(MAX_EVIDENCE_PER_CLAIM).distinct()
            require(ids.isNotEmpty() && ids.all(known::containsKey)) { "Claim cites missing or unknown evidence" }
            val confidence = item.getDouble("confidence")
            require(confidence.isFinite() && confidence in 0.0..1.0) { "Claim confidence must be from 0 to 1" }
            validateClaimOverlap(text, ids.map { known.getValue(it) }, packet)
            GroundedClaim(text, ids, confidence.toFloat())
        }
        val outputText = buildString {
            append(headline).append('\n').append(detail)
            claims.forEach { append('\n').append(it.text) }
        }
        require(FORBIDDEN_OUTPUT.none { it.containsMatchIn(outputText) }) { "Grounded answer emitted a path or URI" }
        val allSources = packet.query + " " + packet.baseline.headline + " " + packet.baseline.detail + " " +
            packet.evidence.joinToString(" ") { it.text }
        validateVocabulary(headline, allSources)
        validateVocabulary(detail, allSources)
        validateSupportedLiterals(outputText, packet)
        val evidenceIds = claims.flatMap { it.evidenceIds }.distinct()
        require(evidenceIds.isNotEmpty()) { "Grounded answer has no evidence" }
        return packet.baseline.copy(
            headline = headline,
            detail = detail,
            evidenceIds = evidenceIds,
            claims = claims,
        )
    }

    fun repairPrompt(packet: GroundedEvidencePacket, invalidResponse: String, error: String): String = """
        Your previous grounded-answer JSON was invalid. Repair it once using only the same evidence packet.
        Return exactly one JSON object and no markdown.
        Error: ${JSONObject.quote(error.take(240))}
        Required shape: {"headline":"Short answer","detail":"Evidence-only detail","claims":[{"text":"Supported claim","evidenceIds":["EV1"],"confidence":0.95}]}
        Every claim needs one or more evidenceIds copied exactly from the supplied packet. Do not invent IDs, media, facts, numbers, dates, names, paths, or URIs.
        Preserve every deterministic number and date. If paraphrasing is unsafe, copy the deterministic baseline wording.
        Evidence packet: ${packet.toPromptJson()}
        Invalid response: ${JSONObject.quote(invalidResponse.take(1200))}
    """.trimIndent()

    private fun validateSupportedLiterals(text: String, packet: GroundedEvidencePacket) {
        val source = buildString {
            append(packet.query).append(' ')
            append(packet.baseline.headline).append(' ').append(packet.baseline.detail)
            packet.evidence.forEach { append(' ').append(it.text) }
        }
        val allowedNumbers = NUMBER.findAll(source).map { normalizeNumber(it.value) }.toSet()
        val outputNumbers = NUMBER.findAll(text).map { normalizeNumber(it.value) }.toSet()
        require(outputNumbers.all { it in allowedNumbers }) { "Grounded answer introduced an unsupported number or date" }
        val allowedCalendarWords = CALENDAR_WORD.findAll(source).map { it.value.lowercase(Locale.ROOT) }.toSet()
        val outputCalendarWords = CALENDAR_WORD.findAll(text).map { it.value.lowercase(Locale.ROOT) }.toSet()
        require(outputCalendarWords.all { it in allowedCalendarWords }) { "Grounded answer introduced an unsupported calendar date" }
    }

    private fun validateClaimOverlap(text: String, cited: List<EvidenceRecord>, packet: GroundedEvidencePacket) {
        val source = packet.query + " " + packet.baseline.headline + " " + cited.joinToString(" ") { it.text }
        val sourceWords = tokenize(source)
        val claimWords = tokenize(text)
        require(claimWords.any { it in sourceWords }) { "Claim is not lexically connected to its cited evidence" }
        validateVocabulary(text, source)
    }

    private fun validateVocabulary(text: String, source: String) {
        val sourceWords = tokenize(source)
        val unsupported = tokenize(text).filterNot { it in sourceWords || it in SAFE_WORDS }
        require(unsupported.isEmpty()) { "Grounded answer introduced unsupported descriptive terms" }
    }

    private fun tokenize(text: String): Set<String> = WORD.findAll(text.lowercase(Locale.ROOT))
        .map { it.value }
        .filter { it.length >= 3 && it !in STOP_WORDS }
        .toSet()

    private fun normalizeNumber(raw: String): String = runCatching {
        BigDecimal(raw.replace(",", "")).stripTrailingZeros().toPlainString()
    }.getOrElse { raw.replace(",", "") }

    private fun parseSingleObject(text: String): JSONObject {
        val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        require(trimmed.startsWith('{') && trimmed.endsWith('}')) { "Answer composer must return one JSON object" }
        return JSONObject(trimmed)
    }

    private fun JSONObject.requireExactKeys(vararg expected: String): JSONObject {
        require(keys().asSequence().toSet() == expected.toSet()) { "Grounded answer emitted missing or unsupported fields" }
        return this
    }

    private fun String.validatedText(label: String, max: Int): String = trim().also {
        require(it.isNotEmpty() && it.length <= max) { "Invalid $label length" }
        require(!it.contains('\u0000')) { "Invalid $label content" }
    }

    private fun JSONArray.strings(max: Int): List<String> {
        require(length() in 1..max) { "Evidence citation array exceeds its bound" }
        return List(length()) { index -> getString(index).trim().also { require(it.isNotEmpty()) } }
    }

    companion object {
        private const val MAX_HEADLINE_CHARS = 240
        private const val MAX_DETAIL_CHARS = 640
        private const val MAX_CLAIM_CHARS = 420
        private const val MAX_CLAIMS = 12
        private const val MAX_EVIDENCE_PER_CLAIM = 12
        private val NUMBER = Regex("(?<![\\p{L}\\p{N}])[-+]?\\d[\\d,]*(?:\\.\\d+)?")
        private val WORD = Regex("[\\p{L}\\p{N}]+")
        private val CALENDAR_WORD = Regex(
            "\\b(?:january|february|march|april|may|june|july|august|september|october|november|december|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val FORBIDDEN_OUTPUT = listOf(
            Regex("content://", RegexOption.IGNORE_CASE),
            Regex("file://", RegexOption.IGNORE_CASE),
            Regex("(?:^|\\s)/(?:data|storage|sdcard|system)/", RegexOption.IGNORE_CASE),
            Regex("[A-Za-z]:\\\\"),
            Regex("(?:^|[/\\\\])\\.\\.(?:[/\\\\]|$)"),
        )
        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "from", "that", "this", "was", "were", "are", "has", "have", "had",
            "your", "you", "found", "match", "matches", "answer", "evidence", "appears", "shows", "show", "image",
        )
        private val SAFE_WORDS = setOf(
            "verified", "local", "locally", "visible", "photo", "photos", "result", "results", "candidate", "candidates",
            "condition", "conditions", "required", "supported", "ranked", "wears", "wearing", "satisfied", "checked",
        )
    }
}

internal fun GroundedEvidencePacket.toPromptJson(): JSONObject = JSONObject().apply {
    put("query", query)
    put("deterministicBaseline", JSONObject().apply {
        put("headline", baseline.headline)
        put("detail", baseline.detail)
        put("exactness", baseline.exactness.name)
        put("indexedEligibleCount", baseline.indexedEligibleCount)
        put("totalEligibleCount", baseline.totalEligibleCount)
    })
    put("evidence", JSONArray().apply {
        evidence.forEach { record ->
            put(JSONObject().apply {
                put("id", record.id)
                put("type", record.sourceField)
                put("text", record.text.take(500))
                put("confidence", record.confidence.toDouble())
                record.pageIndex?.let { put("pageIndex", it) }
            })
        }
    })
}

data class BoundedGroundedAnswerDecode(val answer: SearchAnswer, val generationCalls: Int)

class BoundedGemmaAnswerCompiler(private val codec: GroundedAnswerCodec = GroundedAnswerCodec()) {
    suspend fun compile(
        packet: GroundedEvidencePacket,
        initialPrompt: String,
        generate: suspend (String) -> String,
    ): BoundedGroundedAnswerDecode {
        var calls = 1
        val first = generate(initialPrompt)
        val answer = runCatching { codec.decode(first, packet) }.getOrElse { firstError ->
            calls++
            val repaired = generate(codec.repairPrompt(packet, first, firstError.message ?: "Invalid grounded answer"))
            codec.decode(repaired, packet)
        }
        return BoundedGroundedAnswerDecode(answer, calls)
    }
}
