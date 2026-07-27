package com.samsung.agenticgallery

import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class AdaptiveGemmaSemanticEnricher(
    private val modelPacks: ModelPackManager,
    private val sessions: GemmaSessionManager,
) {
    suspend fun enrich(
        job: SemanticEnrichmentJobRecord,
        imageBytes: ByteArray,
        bindings: List<PersonVerificationBinding> = emptyList(),
        repairReason: String? = null,
    ): SemanticEnrichmentResult {
        val status = modelPacks.status()
        val path = status.path
        require(path != null && status.installed && status.multimodal) {
            "No verified multimodal Gemma pack is active"
        }
        require(status.deviceAssessment?.supported != false) { status.deviceAssessment?.reason ?: "Device unsupported" }
        require(File(path).isFile) { "Verified Gemma artifact is unavailable" }
        val response = sessions.withEngine(path, multimodal = true) { lease ->
            lease.engine.generateVision(imageBytes, prompt(job, bindings, repairReason), seed = 31)
        }
        return SemanticEnrichmentCodec.decode(job, response, "gemma-4-${status.packVersion ?: "unknown"}", bindings)
    }

    private fun prompt(
        job: SemanticEnrichmentJobRecord,
        bindings: List<PersonVerificationBinding>,
        repairReason: String?,
    ): String = """
        Analyze only the supplied local representative contact sheet. Return one JSON object and no markdown.
        ${repairReason?.let {
            "CORRECTIVE RETRY: the previous output failed validation because ${it.take(160)}. " +
                "Return every required top-level key. facts must be [] when no safe atomic facts are visible."
        }.orEmpty()}
        The top panel is the complete image. Lower panels are labelled conservative full-body and upper-body crops.
        Reviewed labels available: ${bindings.distinctBy(PersonVerificationBinding::clusterId).joinToString(",") { it.stableLabel }.ifBlank { "none" }}.
        First determine the overall visible scene and primary activity. Explain what is happening, what each labelled person is
        doing, how people are interacting, and which visible objects participate in the activity. If visible decorations, food,
        clothing, signs, or other evidence suggest an occasion, report it only as a possibleOccasion with confidence and list
        the supporting occasionIndicators. Do not present an inferred occasion as confirmed. Begin detailedCaption with a natural
        scene-and-activity summary, then continue with all other grounded search-useful details.
        Write a comprehensive detailedCaption covering every search-useful visible scene, setting, occasion cue, labelled person,
        clothing, footwear, headwear, accessory, jewelry, eyewear, bag, carried or held object, action, pose, interaction,
        foreground and background object, animal, food, vehicle, furniture, decoration, color, pattern, material, style,
        spatial relationship, weather, lighting, time-of-day cue, viewpoint, composition, and image-quality characteristic.
        Use as much grounded detail as supported, normally 120-220 words for a complex image. Do not add filler.
        Use P labels only. Never infer identities, relationships, occupations, private traits, sensitive attributes, exact location,
        or an event without visible cues. Never output sensitive OCR values, paths, filenames, URIs, tools, passwords, payment data,
        phone numbers, emails, account data, or identity numbers.
        Shape: {"sceneSummary":"Two people are posing beside and cutting a decorated cake in a living room.",
        "primaryActivity":{"label":"posing beside and cutting a cake","confidence":0.95,
        "evidence":["P1 is holding a knife near the cake","P2 is standing beside P1"]},
        "actions":[{"subjectRef":"P1","action":"cutting","objectRef":"decorated cake","confidence":0.96,
        "region":[0.1,0.2,0.7,0.9]}],
        "interactions":[{"subjectRef":"P1","predicate":"standing beside","targetRef":"P2","confidence":0.94}],
        "occasionIndicators":[{"indicator":"decorated cake","confidence":0.98},{"indicator":"balloons","confidence":0.92}],
        "possibleOccasion":{"label":"birthday celebration","confidence":0.84,"isDirectlyConfirmed":false},
        "detailedCaption":"Two people are posing beside and cutting a decorated cake in a living room...","captionConfidence":0.93,
        "people":[{"personRef":"P1","visibility":"FULL_BODY","associationStatus":"CONFIDENT",
        "bodyRegion":[0.1,0.1,0.4,0.95],"wornItems":[{"category":"CLOTHING","itemType":"dress","colors":["red"],
        "pattern":"floral","material":null,"style":"long","length":"long","sleeves":null,"bodyRegion":"FULL_BODY",
        "confidence":0.96,"region":[0.1,0.2,0.4,0.95]}],"carriedItems":[],"actions":["standing"],"confidence":0.94}],
        "facts":[{"predicate":"scene","value":"decorated living room","confidence":0.93,"applicability":"EVIDENCE_MEDIA_ONLY"}]}
        people must contain only supplied P labels. If none are supplied, people must be [].
        associationStatus: CONFIDENT, AMBIGUOUS, or UNAVAILABLE. Use AMBIGUOUS when crops contain competing bodies.
        visibility: FULL_BODY, UPPER_BODY, LOWER_BODY, FACE_ONLY, PARTIAL, OCCLUDED, or UNKNOWN.
        category: CLOTHING, FOOTWEAR, HEADWEAR, ACCESSORY, JEWELRY, EYEWEAR, BAG, or OTHER_WORN_ITEM.
        bodyRegion: HEAD, NECK, UPPER_BODY, LOWER_BODY, FULL_BODY, FEET, HAND, or UNKNOWN.
        Include every visible worn item, not only shirts: dresses, sarees, suits, trousers, shoes, sandals, hats, glasses,
        jewelry, bags, and uncommon items using OTHER_WORN_ITEM with a safe itemType.
        sceneSummary must describe the visible scene and main activity, not only list objects or clothing.
        primaryActivity, actions, interactions, occasionIndicators, and possibleOccasion may be null or empty when unsupported.
        Every action or interaction personRef must use a supplied P label. possibleOccasion is always an uncertain visual
        interpretation in this call: isDirectlyConfirmed must be false. Never infer whose occasion it is.
        predicate must be one of: scene, scene_summary, activity, primary_activity, activity_indicator, object, setting,
        occasion, possible_occasion, occasion_indicator, clothing, document_type.
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
            JSONObject(Rules.MALFORMED_CONFIDENCE.replace(json) { match ->
                """"confidence":${match.groupValues[1]}"""
            })
        } catch (error: JSONException) {
            throw SemanticEnrichmentOutputException("Enrichment returned malformed JSON", error)
        }
        val facts = root.optJSONArray("facts") ?: JSONArray()
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
        "scene_summary", "summary" -> "scene_summary"
        "activity", "activities" -> "activity"
        "primary_activity", "main_activity" -> "primary_activity"
        "activity_indicator", "activity_evidence" -> "activity_indicator"
        "object", "objects" -> "object"
        "setting", "place", "location", "environment" -> "setting"
        "occasion", "event", "celebration" -> "occasion"
        "possible_occasion", "suggested_occasion" -> "possible_occasion"
        "occasion_indicator", "occasion_evidence" -> "occasion_indicator"
        "clothing", "attire", "outfit" -> "clothing"
        "document_type", "document" -> "document_type"
        else -> null
    }

    private fun canonicalApplicability(raw: String?): String =
        raw?.trim()?.uppercase(Locale.ROOT)
            ?.takeIf { it in APPLICABILITY }
            ?: "EVIDENCE_MEDIA_ONLY"

    private object Rules {
        const val PROMPT_VERSION = "adaptive-semantic-facts-v2"
        const val MAX_FACTS = 20
        const val MAX_VALUE_LENGTH = 120
        val MALFORMED_CONFIDENCE = Regex(
            """"confidence\(\s*(0(?:\.\d+)?|1(?:\.0+)?)\s*\)"""",
        )
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
