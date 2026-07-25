package com.askphotos.android

import java.io.File
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

internal object SemanticFactCodec {
    fun decode(
        job: SemanticEnrichmentJobRecord,
        raw: String,
        modelVersion: String,
    ): List<SemanticFactRecord> {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        require(trimmed.startsWith('{') && trimmed.endsWith('}')) { "Enrichment must return one JSON object" }
        val root = JSONObject(trimmed)
        require(root.keys().asSequence().all { it == "facts" }) { "Enrichment emitted unsupported fields" }
        val facts = root.getJSONArray("facts")
        require(facts.length() <= 12) { "Too many semantic facts" }
        return buildList {
            for (index in 0 until facts.length()) {
                val fact = facts.getJSONObject(index)
                require(fact.keys().asSequence().all { it in FACT_FIELDS }) { "Fact emitted unsupported fields" }
                val predicate = fact.getString("predicate")
                require(predicate in PREDICATES) { "Unsupported semantic predicate" }
                val value = fact.getString("value").trim()
                require(value.isNotBlank() && value.length <= 120 && SENSITIVE.none { it.containsMatchIn(value) }) {
                    "Unsafe semantic fact value"
                }
                val confidence = fact.getDouble("confidence").toFloat()
                require(confidence in 0f..1f) { "Invalid semantic confidence" }
                val applicability = fact.getString("applicability")
                require(applicability in APPLICABILITY) { "Invalid semantic applicability" }
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

    private object Rules {
        const val PROMPT_VERSION = "adaptive-semantic-facts-v1"
        val FACT_FIELDS = setOf("predicate", "value", "confidence", "applicability")
        val PREDICATES = setOf("scene", "activity", "object", "setting", "occasion", "clothing", "document_type")
        val APPLICABILITY = setOf("EVIDENCE_MEDIA_ONLY", "SAFE_FOR_EXACT_DUPLICATES")
        val SENSITIVE = listOf(
            Regex("""(?i)\b(?:password|passcode|pin|cvv|account|card)\b"""),
            Regex("""\b\d{6,}\b"""),
            Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""),
        )
    }

    private val PROMPT_VERSION get() = Rules.PROMPT_VERSION
    private val FACT_FIELDS get() = Rules.FACT_FIELDS
    private val PREDICATES get() = Rules.PREDICATES
    private val APPLICABILITY get() = Rules.APPLICABILITY
    private val SENSITIVE get() = Rules.SENSITIVE
}
