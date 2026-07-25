package com.askphotos.android

import java.io.File
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

class AdaptiveGemmaSemanticEnricher(
    private val modelPacks: ModelPackManager,
    private val sessions: GemmaSessionManager,
) {
    suspend fun enrich(job: SemanticEnrichmentJobRecord, imageBytes: ByteArray): List<SemanticFactRecord> {
        val status = modelPacks.status()
        val path = status.path
        require(path != null && status.installed && status.multimodal) {
            "No verified multimodal Gemma pack is active"
        }
        require(status.deviceAssessment?.supported != false) { status.deviceAssessment?.reason ?: "Device unsupported" }
        require(File(path).isFile) { "Verified Gemma artifact is unavailable" }
        val response = sessions.withEngine(path, multimodal = true) { lease ->
            lease.engine.generateVision(imageBytes, prompt(job), seed = 31)
        }
        return SemanticFactCodec.decode(job, response, "gemma-4-${status.packVersion ?: "unknown"}")
    }

    private fun prompt(job: SemanticEnrichmentJobRecord): String = """
        Analyze only the supplied local representative image. Return one JSON object and no markdown.
        Shape: {"facts":[{"predicate":"scene","value":"beach","confidence":0.93,"applicability":"EVIDENCE_MEDIA_ONLY"}]}
        predicate must be one of: scene, activity, object, setting, occasion, clothing, document_type.
        value must be concise visible evidence, never a caption, identity guess, private value, password, payment data, phone, email, order ID, path, URI, or tool.
        applicability must be EVIDENCE_MEDIA_ONLY or SAFE_FOR_EXACT_DUPLICATES.
        Group/event representatives do not make a fact true for every member. Use EVIDENCE_MEDIA_ONLY unless pixels are exact duplicates.
        Emit at most 12 facts with confidence from 0 to 1.
        Scope: ${job.scope}; reason: ${job.reason}
    """.trimIndent()

}

internal class SemanticEnrichmentOutputException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

internal object SemanticFactCodec {
    fun decode(
        job: SemanticEnrichmentJobRecord,
        raw: String,
        modelVersion: String,
    ): List<SemanticFactRecord> {
        val json = extractFirstJsonObject(raw)
        if (json == null) {
            throw SemanticEnrichmentOutputException("Enrichment must return one JSON object")
        }
        val root = try {
            JSONObject(json)
        } catch (error: JSONException) {
            throw SemanticEnrichmentOutputException("Enrichment returned malformed JSON", error)
        }
        val facts = root.optJSONArray("facts")
            ?: throw SemanticEnrichmentOutputException("Enrichment omitted the facts array")
        return buildList {
            for (index in 0 until minOf(facts.length(), MAX_FACTS)) {
                val fact = facts.optJSONObject(index) ?: continue
                val predicate = canonicalPredicate(fact.opt("predicate") as? String) ?: continue
                val value = (fact.opt("value") as? String)?.trim() ?: continue
                if (value.isBlank() || value.length > MAX_VALUE_LENGTH || SENSITIVE.any { it.containsMatchIn(value) }) {
                    continue
                }
                val confidence = when (val rawConfidence = fact.opt("confidence")) {
                    is Number -> rawConfidence.toFloat()
                    is String -> rawConfidence.toFloatOrNull()
                    else -> null
                } ?: continue
                if (!confidence.isFinite() || confidence !in 0f..1f) continue
                val applicability = canonicalApplicability(fact.opt("applicability") as? String)
                add(
                    SemanticFactRecord(
                        scope = job.scope,
                        subjectId = job.subjectId,
                        predicate = predicate,
                        value = value,
                        confidence = confidence,
                        evidenceMediaId = job.representativeMediaId,
                        applicability = applicability,
                        modelVersion = modelVersion,
                        promptVersion = PROMPT_VERSION,
                    ),
                )
            }
        }
    }

    private fun extractFirstJsonObject(raw: String): String? {
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        for (index in raw.indices) {
            val character = raw[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                continue
            }
            when (character) {
                '"' -> if (start >= 0) inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth += 1
                }
                '}' -> if (depth > 0) {
                    depth -= 1
                    if (depth == 0 && start >= 0) return raw.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun canonicalPredicate(raw: String?): String? = when (
        raw?.trim()?.lowercase(Locale.ROOT)?.replace('-', '_')?.replace(' ', '_')
    ) {
        "scene", "scene_type" -> "scene"
        "activity", "activities" -> "activity"
        "object", "objects" -> "object"
        "setting", "place", "location", "environment" -> "setting"
        "occasion", "event", "celebration" -> "occasion"
        "clothing", "attire", "outfit" -> "clothing"
        "document_type", "document" -> "document_type"
        else -> null
    }

    private fun canonicalApplicability(raw: String?): String =
        raw?.trim()?.uppercase(Locale.ROOT)
            ?.takeIf { it in APPLICABILITY }
            ?: "EVIDENCE_MEDIA_ONLY"

    private object Rules {
        const val PROMPT_VERSION = "adaptive-semantic-facts-v1"
        const val MAX_FACTS = 12
        const val MAX_VALUE_LENGTH = 120
        val APPLICABILITY = setOf("EVIDENCE_MEDIA_ONLY", "SAFE_FOR_EXACT_DUPLICATES")
        val SENSITIVE = listOf(
            Regex("""(?i)\b(?:password|passcode|cvv)\b"""),
            Regex("""(?i)\bpin\s*(?:code|number|[:=#]|\d)"""),
            Regex("""(?i)\b(?:account|card)\s*(?:number|no\.?|#|[:=])"""),
            Regex("""(?i)\b(?:credit|debit|payment|bank)\s+card\b"""),
            Regex("""(?i)\b(?:visa|mastercard|amex)\b"""),
            Regex("""\b\d{6,}\b"""),
            Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""),
            Regex("""(?<!\d)(?:\+?\d[\s().-]?){8,15}(?!\d)"""),
        )
    }

    private val PROMPT_VERSION get() = Rules.PROMPT_VERSION
    private val MAX_FACTS get() = Rules.MAX_FACTS
    private val MAX_VALUE_LENGTH get() = Rules.MAX_VALUE_LENGTH
    private val APPLICABILITY get() = Rules.APPLICABILITY
    private val SENSITIVE get() = Rules.SENSITIVE
}
