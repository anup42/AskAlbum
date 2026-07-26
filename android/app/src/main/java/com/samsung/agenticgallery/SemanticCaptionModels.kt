package com.samsung.agenticgallery

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

enum class PersonVisualRelation { WEARING, CARRYING, HOLDING, USING, ACTION, STANDING_BESIDE, SITTING_BESIDE, INTERACTING_WITH }
enum class WornItemCategory { CLOTHING, FOOTWEAR, HEADWEAR, ACCESSORY, JEWELRY, EYEWEAR, BAG, OTHER_WORN_ITEM }
enum class BodyRegion { HEAD, NECK, UPPER_BODY, LOWER_BODY, FULL_BODY, FEET, HAND, UNKNOWN }
enum class PersonVisibility { FULL_BODY, UPPER_BODY, LOWER_BODY, FACE_ONLY, PARTIAL, OCCLUDED, UNKNOWN }
enum class PersonAssociationStatus { CONFIDENT, AMBIGUOUS, UNAVAILABLE }
enum class PersonVisualVerdict { VERIFIED_TRUE, VERIFIED_FALSE, AMBIGUOUS, NOT_VISIBLE }

data class SemanticCaptionPersonRefRecord(
    val personRef: String,
    val clusterId: String,
    val resolvedLabel: String? = null,
    val faceRegion: List<Float>,
    val bodyRegion: List<Float>? = null,
    val associationStatus: PersonAssociationStatus,
)

data class SemanticCaptionRecord(
    val id: String = "",
    val scope: SemanticFactScope,
    val subjectId: String,
    val text: String,
    val confidence: Float,
    val evidenceMediaId: String,
    val representativeMediaId: String? = evidenceMediaId,
    val sourceType: String = "GEMMA_DIRECT",
    val applicability: String = "EVIDENCE_MEDIA_ONLY",
    val bodyRegionVersion: String = PersonalSemanticMemoryPolicy.BODY_REGION_VERSION,
    val modelVersion: String,
    val promptVersion: String,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val personRefs: List<SemanticCaptionPersonRefRecord> = emptyList(),
)

data class PersonVisualFactRecord(
    val id: String = "",
    val mediaId: String,
    val clusterId: String,
    val resolvedLabel: String? = null,
    val personRef: String,
    val relation: PersonVisualRelation,
    val category: WornItemCategory? = null,
    val itemType: String? = null,
    val value: String,
    val attributes: Map<String, List<String>> = emptyMap(),
    val bodyRegion: BodyRegion = BodyRegion.UNKNOWN,
    val confidence: Float,
    val faceRegion: List<Float>,
    val evidenceRegion: List<Float>? = null,
    val associationStatus: PersonAssociationStatus = PersonAssociationStatus.CONFIDENT,
    val verdict: PersonVisualVerdict = PersonVisualVerdict.VERIFIED_TRUE,
    val targetClusterId: String? = null,
    val modelVersion: String,
    val promptVersion: String,
    val updatedAt: Long = 0L,
)

data class SemanticEnrichmentResult(
    val facts: List<SemanticFactRecord>,
    val caption: SemanticCaptionRecord? = null,
    val personFacts: List<PersonVisualFactRecord> = emptyList(),
)

data class CaptionSearchHit(
    val mediaId: String,
    val caption: SemanticCaptionRecord,
    val score: Double,
    val directEvidence: Boolean,
)

internal object SemanticEnrichmentCodec {
    private const val MAX_CAPTION_LENGTH = 4_000
    private const val MAX_PEOPLE = 12
    private const val MAX_ITEMS_PER_PERSON = 24
    internal const val PROMPT_VERSION = "adaptive-comprehensive-caption-v3"

    fun decode(
        job: SemanticEnrichmentJobRecord,
        raw: String,
        modelVersion: String,
        bindings: List<PersonVerificationBinding>,
    ): SemanticEnrichmentResult {
        val facts = SemanticFactCodec.decode(job, raw, modelVersion)
        val root = JSONObject(
            extractFirstJsonObject(raw)
                ?: throw SemanticEnrichmentOutputException("Enrichment must return one JSON object"),
        )
        val bindingByLabel = bindings.distinctBy(PersonVerificationBinding::clusterId)
            .associateBy(PersonVerificationBinding::stableLabel)
        val captionText = root.optString("detailedCaption").trim()
        val captionConfidence = root.opt("captionConfidence").asConfidence()
        val caption = captionText
            .takeIf { it.isNotBlank() && it.length <= MAX_CAPTION_LENGTH && !SensitiveContentClassifier.isSensitive(it) }
            ?.let {
                SemanticCaptionRecord(
                    scope = job.scope,
                    subjectId = job.subjectId,
                    text = it,
                    confidence = captionConfidence ?: facts.maxOfOrNull(SemanticFactRecord::confidence) ?: 0.5f,
                    evidenceMediaId = job.representativeMediaId,
                    representativeMediaId = job.representativeMediaId,
                    sourceType = when {
                        PersonalSemanticMemoryPolicy.isPersonalJob(job.reason) -> "GEMMA_MEDIA_DIRECT"
                        job.scope == SemanticFactScope.EXACT_DUPLICATE_GROUP -> "GEMMA_EXACT_DUPLICATE_REPRESENTATIVE"
                        job.scope == SemanticFactScope.VISUAL_GROUP -> "GEMMA_VISUAL_GROUP_REPRESENTATIVE"
                        job.scope == SemanticFactScope.EVENT -> "GEMMA_EVENT_REPRESENTATIVE"
                        else -> "GEMMA_MEDIA_DIRECT"
                    },
                    applicability = if (job.scope == SemanticFactScope.EXACT_DUPLICATE_GROUP) {
                        "SAFE_FOR_EXACT_DUPLICATES"
                    } else {
                        "EVIDENCE_MEDIA_ONLY"
                    },
                    bodyRegionVersion = PersonalSemanticMemoryPolicy.BODY_REGION_VERSION,
                    modelVersion = modelVersion,
                    promptVersion = PROMPT_VERSION,
                )
            }
        val people = root.optJSONArray("people") ?: JSONArray()
        val refs = mutableListOf<SemanticCaptionPersonRefRecord>()
        val personFacts = mutableListOf<PersonVisualFactRecord>()
        for (index in 0 until minOf(people.length(), MAX_PEOPLE)) {
            val person = people.optJSONObject(index) ?: continue
            val personRef = person.optString("personRef").trim()
            val binding = bindingByLabel[personRef] ?: continue
            val association = enumValue<PersonAssociationStatus>(person.optString("associationStatus"))
                ?: PersonAssociationStatus.AMBIGUOUS
            val visibility = enumValue<PersonVisibility>(person.optString("visibility")) ?: PersonVisibility.UNKNOWN
            val bodyBox = person.optJSONArray("bodyRegion")?.normalizedRegion()
            refs += SemanticCaptionPersonRefRecord(
                personRef = personRef,
                clusterId = binding.clusterId,
                faceRegion = binding.faceRegion(),
                bodyRegion = bodyBox,
                associationStatus = association,
            )
            if (association != PersonAssociationStatus.CONFIDENT) continue
            val worn = person.optJSONArray("wornItems") ?: JSONArray()
            for (itemIndex in 0 until minOf(worn.length(), MAX_ITEMS_PER_PERSON)) {
                decodeVisibleItem(
                    mediaId = job.representativeMediaId,
                    item = worn.optJSONObject(itemIndex) ?: continue,
                    binding = binding,
                    personRef = personRef,
                    relation = PersonVisualRelation.WEARING,
                    defaultCategory = WornItemCategory.OTHER_WORN_ITEM,
                    visibility = visibility,
                    modelVersion = modelVersion,
                )?.let(personFacts::add)
            }
            val carried = person.optJSONArray("carriedItems") ?: JSONArray()
            for (itemIndex in 0 until minOf(carried.length(), MAX_ITEMS_PER_PERSON)) {
                decodeVisibleItem(
                    mediaId = job.representativeMediaId,
                    item = carried.optJSONObject(itemIndex) ?: continue,
                    binding = binding,
                    personRef = personRef,
                    relation = PersonVisualRelation.CARRYING,
                    defaultCategory = WornItemCategory.OTHER_WORN_ITEM,
                    visibility = visibility,
                    modelVersion = modelVersion,
                )?.let(personFacts::add)
            }
            val actions = person.optJSONArray("actions") ?: JSONArray()
            for (actionIndex in 0 until minOf(actions.length(), 12)) {
                val action = actions.optString(actionIndex).trim()
                if (action.isBlank() || action.length > 120 || SensitiveContentClassifier.isSensitive(action)) continue
                personFacts += PersonVisualFactRecord(
                    mediaId = job.representativeMediaId,
                    clusterId = binding.clusterId,
                    personRef = personRef,
                    relation = PersonVisualRelation.ACTION,
                    value = action,
                    bodyRegion = BodyRegion.FULL_BODY,
                    confidence = person.opt("confidence").asConfidence() ?: captionConfidence ?: 0.6f,
                    faceRegion = binding.faceRegion(),
                    evidenceRegion = bodyBox,
                    modelVersion = modelVersion,
                    promptVersion = PROMPT_VERSION,
                )
            }
        }
        return SemanticEnrichmentResult(
            facts = facts,
            caption = caption?.copy(personRefs = refs.distinctBy(SemanticCaptionPersonRefRecord::clusterId)),
            personFacts = personFacts.distinctBy {
                "${it.clusterId}|${it.relation}|${it.category}|${it.itemType}|${it.value}|${it.bodyRegion}"
            },
        )
    }

    private fun decodeVisibleItem(
        mediaId: String,
        item: JSONObject,
        binding: PersonVerificationBinding,
        personRef: String,
        relation: PersonVisualRelation,
        defaultCategory: WornItemCategory,
        visibility: PersonVisibility,
        modelVersion: String,
    ): PersonVisualFactRecord? {
        val itemType = item.optString("itemType").trim().takeIf(String::isNotBlank) ?: return null
        if (itemType.length > 120 || SensitiveContentClassifier.isSensitive(itemType)) return null
        val confidence = item.opt("confidence").asConfidence() ?: return null
        val category = enumValue<WornItemCategory>(item.optString("category")) ?: defaultCategory
        val bodyRegion = enumValue<BodyRegion>(item.optString("bodyRegion")) ?: inferredBodyRegion(category, visibility)
        val evidenceRegion = item.optJSONArray("region")?.normalizedRegion()
        val attributes = buildMap {
            item.optJSONArray("colors")?.strings()?.takeIf(List<String>::isNotEmpty)?.let { put("colors", it) }
            listOf("pattern", "material", "style", "length", "sleeves").forEach { key ->
                item.optString(key).trim().takeIf(String::isNotBlank)?.let { put(key, listOf(it.take(120))) }
            }
        }
        val value = buildString {
            attributes["colors"].orEmpty().forEach { append(it).append(' ') }
            attributes["pattern"].orEmpty().forEach { append(it).append(' ') }
            attributes["style"].orEmpty().forEach { append(it).append(' ') }
            append(itemType)
        }.trim().take(240)
        if (SensitiveContentClassifier.isSensitive(value)) return null
        return PersonVisualFactRecord(
            mediaId = mediaId,
            clusterId = binding.clusterId,
            personRef = personRef,
            relation = relation,
            category = category,
            itemType = itemType,
            value = value,
            attributes = attributes,
            bodyRegion = bodyRegion,
            confidence = confidence,
            faceRegion = binding.faceRegion(),
            evidenceRegion = evidenceRegion,
            modelVersion = modelVersion,
            promptVersion = PROMPT_VERSION,
        )
    }

    private fun inferredBodyRegion(category: WornItemCategory, visibility: PersonVisibility): BodyRegion = when (category) {
        WornItemCategory.FOOTWEAR -> BodyRegion.FEET
        WornItemCategory.HEADWEAR, WornItemCategory.EYEWEAR -> BodyRegion.HEAD
        WornItemCategory.JEWELRY -> BodyRegion.NECK
        else -> if (visibility == PersonVisibility.FULL_BODY) BodyRegion.FULL_BODY else BodyRegion.UPPER_BODY
    }

    private fun PersonVerificationBinding.faceRegion(): List<Float> = listOf(left, top, right, bottom)

    private fun JSONArray.normalizedRegion(): List<Float>? {
        if (length() != 4) return null
        val values = List(4) { optDouble(it, Double.NaN).toFloat() }
        return values.takeIf {
            it.all(Float::isFinite) && it.all { value -> value in 0f..1f } && it[0] < it[2] && it[1] < it[3]
        }
    }

    private fun JSONArray.strings(): List<String> = buildList {
        for (index in 0 until minOf(length(), 12)) {
            optString(index).trim().takeIf { it.isNotBlank() && it.length <= 120 }?.let(::add)
        }
    }

    private fun Any?.asConfidence(): Float? = when (this) {
        is Number -> toFloat()
        is String -> toFloatOrNull()
        else -> null
    }?.takeIf { it.isFinite() && it in 0f..1f }

    private inline fun <reified T : Enum<T>> enumValue(raw: String): T? =
        enumValues<T>().firstOrNull {
            it.name == raw.trim().uppercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
        }

    private fun extractFirstJsonObject(raw: String): String? {
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        raw.forEachIndexed { index, character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> if (start >= 0) inString = true
                    '{' -> {
                        if (depth == 0) start = index
                        depth++
                    }
                    '}' -> if (depth > 0 && --depth == 0 && start >= 0) return raw.substring(start, index + 1)
                }
            }
        }
        return null
    }
}
