package io.github.anup42.askalbum

import java.util.Locale
import java.util.UUID
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
    val generationId: String = "",
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
    val generationId: String = "",
)

data class SemanticEnrichmentResult(
    val facts: List<SemanticFactRecord>,
    val caption: SemanticCaptionRecord? = null,
    val personFacts: List<PersonVisualFactRecord> = emptyList(),
    val generationId: String = "",
)

data class CaptionSearchHit(
    val mediaId: String,
    val caption: SemanticCaptionRecord,
    val score: Double,
    val directEvidence: Boolean,
    val chunk: SemanticCaptionChunkRecord? = null,
    val queryVariant: String? = null,
)

internal object SemanticEnrichmentCodec {
    private const val MAX_CAPTION_LENGTH = 4_000
    private const val MAX_PEOPLE = 12
    private const val MAX_ITEMS_PER_PERSON = 24
    internal const val PROMPT_VERSION = "adaptive-comprehensive-caption-v4"

    fun decode(
        job: SemanticEnrichmentJobRecord,
        raw: String,
        modelVersion: String,
        bindings: List<PersonVerificationBinding>,
    ): SemanticEnrichmentResult {
        val root = JSONObject(
            extractFirstJsonObject(raw)
                ?: throw SemanticEnrichmentOutputException("Enrichment must return one JSON object"),
        )
        val generationId = UUID.randomUUID().toString()
        val baseFacts = SemanticFactCodec.decode(job, raw, modelVersion)
        val bindingByLabel = bindings.distinctBy(PersonVerificationBinding::clusterId)
            .associateBy(PersonVerificationBinding::stableLabel)
        val sceneSummary = root.safeText("sceneSummary", 600)
        val detailedCaption = root.safeText("detailedCaption", MAX_CAPTION_LENGTH)
        if (detailedCaption.isNotBlank() && sceneSummary.isBlank()) {
            throw SemanticEnrichmentOutputException("Enrichment omitted a safe sceneSummary")
        }
        val captionText = activityAwareCaption(sceneSummary, detailedCaption)
        val captionConfidence = root.opt("captionConfidence").asConfidence()
        val activityFacts = decodeSceneActivityFacts(job, root, modelVersion, captionConfidence)
        val facts = (baseFacts + activityFacts).map { it.copy(generationId = generationId) }.distinctBy {
            "${it.scope}|${it.subjectId}|${it.predicate}|${it.value}|${it.applicability}"
        }
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
                    generationId = generationId,
                )
            }
        if (PersonalSemanticMemoryPolicy.isPersonalJob(job.reason) && caption == null) {
            throw SemanticEnrichmentOutputException("Personal enrichment omitted a safe detailedCaption")
        }
        val people = root.optJSONArray("people") ?: JSONArray()
        val refs = mutableListOf<SemanticCaptionPersonRefRecord>()
        val personFacts = mutableListOf<PersonVisualFactRecord>()
        val confidentlyAssociatedLabels = mutableSetOf<String>()
        for (index in 0 until minOf(people.length(), MAX_PEOPLE)) {
            val person = people.optJSONObject(index) ?: continue
            val personRef = person.safeText("personRef", 20)
            val binding = bindingByLabel[personRef] ?: continue
            val association = enumValue<PersonAssociationStatus>(person.safeText("associationStatus", 32))
                ?: PersonAssociationStatus.AMBIGUOUS
            val visibility = enumValue<PersonVisibility>(person.safeText("visibility", 32)) ?: PersonVisibility.UNKNOWN
            val bodyBox = person.optJSONArray("bodyRegion")?.normalizedRegion()
            refs += SemanticCaptionPersonRefRecord(
                personRef = personRef,
                clusterId = binding.clusterId,
                faceRegion = binding.faceRegion(),
                bodyRegion = bodyBox,
                associationStatus = association,
            )
            if (association != PersonAssociationStatus.CONFIDENT) continue
            confidentlyAssociatedLabels += personRef
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
                val action = actions.safeText(actionIndex, 120)
                if (action.isBlank() || action.length > 120 || SensitiveContentClassifier.isSensitive(action)) continue
                val negative = isNegative(action)
                personFacts += PersonVisualFactRecord(
                    mediaId = job.representativeMediaId,
                    clusterId = binding.clusterId,
                    personRef = personRef,
                    relation = normalizedActionRelation(action),
                    value = action,
                    bodyRegion = BodyRegion.FULL_BODY,
                    confidence = person.opt("confidence").asConfidence() ?: captionConfidence ?: 0.6f,
                    faceRegion = binding.faceRegion(),
                    evidenceRegion = bodyBox,
                    modelVersion = modelVersion,
                    promptVersion = PROMPT_VERSION,
                    verdict = if (negative) PersonVisualVerdict.VERIFIED_FALSE else PersonVisualVerdict.VERIFIED_TRUE,
                )
            }
        }
        personFacts += decodeTopLevelActions(
            job = job,
            actions = root.optJSONArray("actions") ?: JSONArray(),
            bindingByLabel = bindingByLabel,
            confidentlyAssociatedLabels = confidentlyAssociatedLabels,
            modelVersion = modelVersion,
            defaultConfidence = captionConfidence,
        )
        personFacts += decodeInteractions(
            job = job,
            interactions = root.optJSONArray("interactions") ?: JSONArray(),
            bindingByLabel = bindingByLabel,
            confidentlyAssociatedLabels = confidentlyAssociatedLabels,
            modelVersion = modelVersion,
            defaultConfidence = captionConfidence,
        )
        return SemanticEnrichmentResult(
            facts = facts,
            caption = caption?.copy(personRefs = refs.distinctBy(SemanticCaptionPersonRefRecord::clusterId)),
            personFacts = personFacts.distinctBy {
                "${it.clusterId}|${it.relation}|${it.category}|${it.itemType}|${it.value}|${it.bodyRegion}"
            }.map { it.copy(generationId = generationId) },
            generationId = generationId,
        )
    }

    private fun decodeSceneActivityFacts(
        job: SemanticEnrichmentJobRecord,
        root: JSONObject,
        modelVersion: String,
        defaultConfidence: Float?,
    ): List<SemanticFactRecord> = buildList {
        fun addFact(
            predicate: String,
            value: String,
            confidence: Float?,
            applicability: String = "EVIDENCE_MEDIA_ONLY",
        ) {
            val safe = value.trim().take(600)
            if (safe.isBlank() || SensitiveContentClassifier.isSensitive(safe)) return
            add(
                SemanticFactRecord(
                    scope = job.scope,
                    subjectId = job.subjectId,
                    predicate = predicate,
                    value = safe,
                    confidence = (confidence ?: defaultConfidence ?: 0.6f).coerceIn(0f, 1f),
                    evidenceMediaId = job.representativeMediaId,
                    applicability = applicability,
                    modelVersion = modelVersion,
                    promptVersion = PROMPT_VERSION,
                ),
            )
        }

        root.safeText("sceneSummary", 600).takeIf(String::isNotBlank)?.let {
            addFact("scene_summary", it, defaultConfidence)
        }
        root.optJSONObject("primaryActivity")?.let { activity ->
            addFact("primary_activity", activity.safeText("label", 240), activity.opt("confidence").asConfidence())
            activity.optJSONArray("evidence")?.strings()?.forEach {
                addFact("activity_indicator", it, activity.opt("confidence").asConfidence())
            }
        }
        val indicators = root.optJSONArray("occasionIndicators") ?: JSONArray()
        for (index in 0 until minOf(indicators.length(), 16)) {
            val indicator = indicators.optJSONObject(index) ?: continue
            addFact(
                "occasion_indicator",
                indicator.safeText("indicator", 240),
                indicator.opt("confidence").asConfidence(),
            )
        }
        root.optJSONObject("possibleOccasion")?.let { occasion ->
            addFact(
                "possible_occasion",
                occasion.safeText("label", 240),
                occasion.opt("confidence").asConfidence(),
                applicability = "POSSIBLE_INFERENCE",
            )
        }
    }

    private fun decodeTopLevelActions(
        job: SemanticEnrichmentJobRecord,
        actions: JSONArray,
        bindingByLabel: Map<String, PersonVerificationBinding>,
        confidentlyAssociatedLabels: Set<String>,
        modelVersion: String,
        defaultConfidence: Float?,
    ): List<PersonVisualFactRecord> = buildList {
        for (index in 0 until minOf(actions.length(), 24)) {
            val actionObject = actions.optJSONObject(index) ?: continue
            val personRef = actionObject.safeText("subjectRef", 20)
            val binding = bindingByLabel[personRef] ?: continue
            if (personRef !in confidentlyAssociatedLabels) continue
            val action = actionObject.safeText("action", 120)
            val objectRef = actionObject.safeText("objectRef", 120)
            val value = listOf(action, objectRef).filter(String::isNotBlank).joinToString(" ").take(240)
            if (value.isBlank() || SensitiveContentClassifier.isSensitive(value)) continue
            val negative = isNegative(actionObject.safeText("polarity", 24)) || isNegative(action)
            val relation = normalizedActionRelation(action)
            add(
                PersonVisualFactRecord(
                    mediaId = job.representativeMediaId,
                    clusterId = binding.clusterId,
                    personRef = personRef,
                    relation = relation,
                    itemType = objectRef.takeIf(String::isNotBlank),
                    value = value,
                    bodyRegion = BodyRegion.FULL_BODY,
                    confidence = actionObject.opt("confidence").asConfidence() ?: defaultConfidence ?: 0.6f,
                    faceRegion = binding.faceRegion(),
                    evidenceRegion = actionObject.optJSONArray("region")?.normalizedRegion(),
                    modelVersion = modelVersion,
                    promptVersion = PROMPT_VERSION,
                    verdict = if (negative) PersonVisualVerdict.VERIFIED_FALSE else PersonVisualVerdict.VERIFIED_TRUE,
                ),
            )
        }
    }

    private fun decodeInteractions(
        job: SemanticEnrichmentJobRecord,
        interactions: JSONArray,
        bindingByLabel: Map<String, PersonVerificationBinding>,
        confidentlyAssociatedLabels: Set<String>,
        modelVersion: String,
        defaultConfidence: Float?,
    ): List<PersonVisualFactRecord> = buildList {
        for (index in 0 until minOf(interactions.length(), 24)) {
            val interaction = interactions.optJSONObject(index) ?: continue
            val subjectRef = interaction.safeText("subjectRef", 20)
            val targetRef = interaction.safeText("targetRef", 20)
            val subject = bindingByLabel[subjectRef] ?: continue
            val target = bindingByLabel[targetRef] ?: continue
            if (subjectRef !in confidentlyAssociatedLabels || targetRef !in confidentlyAssociatedLabels) continue
            val predicate = interaction.safeText("predicate", 120)
            if (predicate.isBlank() || SensitiveContentClassifier.isSensitive(predicate)) continue
            val negative = isNegative(interaction.safeText("polarity", 24)) || isNegative(predicate)
            val normalizedPredicate = relationText(predicate)
            val relation = when {
                normalizedPredicate.contains("standing beside") -> PersonVisualRelation.STANDING_BESIDE
                normalizedPredicate.contains("sitting beside") -> PersonVisualRelation.SITTING_BESIDE
                normalizedPredicate.contains("interact") -> PersonVisualRelation.INTERACTING_WITH
                else -> PersonVisualRelation.ACTION
            }
            add(
                PersonVisualFactRecord(
                    mediaId = job.representativeMediaId,
                    clusterId = subject.clusterId,
                    personRef = subjectRef,
                    relation = relation,
                    value = predicate,
                    bodyRegion = BodyRegion.FULL_BODY,
                    confidence = interaction.opt("confidence").asConfidence() ?: defaultConfidence ?: 0.6f,
                    faceRegion = subject.faceRegion(),
                    associationStatus = PersonAssociationStatus.CONFIDENT,
                    targetClusterId = target.clusterId,
                    modelVersion = modelVersion,
                    promptVersion = PROMPT_VERSION,
                    verdict = if (negative) PersonVisualVerdict.VERIFIED_FALSE else PersonVisualVerdict.VERIFIED_TRUE,
                ),
            )
        }
    }

    private fun activityAwareCaption(sceneSummary: String, detailedCaption: String): String {
        if (detailedCaption.isBlank()) return ""
        val normalizedSummary = sceneSummary.trim().trimEnd('.', '!', '?')
        if (normalizedSummary.isBlank()) return detailedCaption
        val normalizedCaption = detailedCaption.trim()
        val opening = normalizedCaption
            .split(Regex("(?<=[.!?])\\s+"))
            .take(2)
            .joinToString(" ")
        return if (equivalentOpening(normalizedSummary, opening)) {
            normalizedCaption
        } else {
            boundedCaption("$normalizedSummary. $normalizedCaption")
        }
    }

    private fun equivalentOpening(summary: String, opening: String): Boolean {
        val summaryTokens = captionMeaningfulTokens(summary)
        val openingTokens = captionMeaningfulTokens(opening)
        if (summaryTokens.isEmpty() || openingTokens.isEmpty()) return false
        if (
            summaryTokens == openingTokens ||
            summaryTokens.all(openingTokens::contains) ||
            openingTokens.all(summaryTokens::contains)
        ) return true
        val common = summaryTokens.intersect(openingTokens).size.toFloat()
        return common / minOf(summaryTokens.size, openingTokens.size) >= 0.8f
    }

    private fun captionMeaningfulTokens(text: String): Set<String> = text
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .split(Regex("\\s+"))
        .asSequence()
        .filter { it.isNotBlank() && it !in CAPTION_STOP_WORDS }
        .map { token ->
            val stemmed = when {
                token.endsWith("ing") && token.length > 5 -> token.dropLast(3)
                token.endsWith("ed") && token.length > 4 -> token.dropLast(2)
                token.endsWith("s") && token.length > 4 -> token.dropLast(1)
                else -> token
            }
            if (stemmed.length > 2 && stemmed.last() == stemmed[stemmed.lastIndex - 1]) {
                stemmed.dropLast(1)
            } else {
                stemmed
            }
        }
        .toSet()

    private fun boundedCaption(text: String): String {
        if (text.length <= MAX_CAPTION_LENGTH) return text
        val prefix = text.take(MAX_CAPTION_LENGTH)
        val sentenceEnd = prefix.lastIndexOfAny(charArrayOf('.', '!', '?'))
        if (sentenceEnd >= MAX_CAPTION_LENGTH / 2) return prefix.substring(0, sentenceEnd + 1).trim()
        val wordEnd = prefix.lastIndexOf(' ')
        return prefix.substring(0, wordEnd.takeIf { it > 0 } ?: MAX_CAPTION_LENGTH).trimEnd()
    }

    private val CAPTION_STOP_WORDS = setOf(
        "a", "an", "and", "are", "at", "beside", "by", "for", "from", "in", "is", "near",
        "of", "on", "or", "the", "to", "with",
    )

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
        val itemType = item.safeText("itemType", 120).takeIf(String::isNotBlank) ?: return null
        if (itemType.length > 120 || SensitiveContentClassifier.isSensitive(itemType)) return null
        val confidence = item.opt("confidence").asConfidence() ?: return null
        val category = enumValue<WornItemCategory>(item.safeText("category", 48)) ?: defaultCategory
        val bodyRegion = enumValue<BodyRegion>(item.safeText("bodyRegion", 48)) ?: inferredBodyRegion(category, visibility)
        val evidenceRegion = item.optJSONArray("region")?.normalizedRegion()
        val attributes = buildMap {
            item.optJSONArray("colors")?.strings()?.takeIf(List<String>::isNotEmpty)?.let { put("colors", it) }
            listOf("pattern", "material", "style", "length", "sleeves").forEach { key ->
                item.safeText(key, 120).takeIf(String::isNotBlank)?.let { put(key, listOf(it)) }
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
            safeText(index, 120).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun JSONObject.safeText(key: String, maxLength: Int): String =
        (opt(key) as? String)
            ?.trim()
            ?.takeUnless {
                it.isBlank() ||
                    it.equals("null", ignoreCase = true) ||
                    it.equals("undefined", ignoreCase = true) ||
                    it.equals("unknown", ignoreCase = true)
            }
            ?.take(maxLength)
            ?.takeUnless(SensitiveContentClassifier::isSensitive)
            .orEmpty()

    private fun JSONArray.safeText(index: Int, maxLength: Int): String =
        (opt(index) as? String)
            ?.trim()
            ?.takeUnless {
                it.isBlank() ||
                    it.equals("null", ignoreCase = true) ||
                    it.equals("undefined", ignoreCase = true) ||
                    it.equals("unknown", ignoreCase = true)
            }
            ?.take(maxLength)
            ?.takeUnless(SensitiveContentClassifier::isSensitive)
            .orEmpty()

    private fun normalizedActionRelation(action: String): PersonVisualRelation = when {
        relationText(action).startsWith("hold") -> PersonVisualRelation.HOLDING
        relationText(action).startsWith("carry") -> PersonVisualRelation.CARRYING
        relationText(action).startsWith("us") -> PersonVisualRelation.USING
        else -> PersonVisualRelation.ACTION
    }

    private fun relationText(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("\\b(?:does not|doesn't|is not|isn't|not|never|without|no)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun isNegative(value: String): Boolean = value.isNotBlank() &&
        Regex("(?i)\\b(?:not|never|without|no|doesn't|does not|isn't|is not)\\b").containsMatchIn(value)

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
