package io.github.anup42.askalbum

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
    val generationId: String? = null,
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
    val bodyRegionVersion: String = PersonalSemanticMemoryPolicy.BODY_REGION_VERSION,
    val updatedAt: Long = 0L,
    val generationId: String? = null,
    val predicate: String? = null,
)

data class SemanticEnrichmentResult(
    val facts: List<SemanticFactRecord>,
    val caption: SemanticCaptionRecord? = null,
    val personFacts: List<PersonVisualFactRecord> = emptyList(),
    val generation: SemanticGenerationProvenance? = null,
)

data class CaptionSearchHit(
    val mediaId: String,
    val caption: SemanticCaptionRecord,
    val score: Double,
    val directEvidence: Boolean,
    val chunk: SemanticCaptionChunkRecord? = null,
    val queryVariant: String? = null,
)

data class CaptionLexicalSearchResult(
    val hits: List<CaptionSearchHit>,
    val status: ChannelStatus,
    val errorCode: String? = null,
)

internal object SemanticCaptionValuePolicy {
    private val placeholderValues = setOf("null", "undefined", "unknown", "n a", "na")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    fun text(value: Any?, maximumLength: Int): String {
        if (value !is String) return ""
        val text = value.trim().take(maximumLength)
        if (text.isBlank() || SensitiveContentClassifier.isSensitive(text)) return ""
        val normalized = text.lowercase(Locale.ROOT).replace(nonWord, " ").trim()
        return text.takeUnless { normalized in placeholderValues }.orEmpty()
    }
}

internal object SemanticEnrichmentCodec {
    private const val MAX_CAPTION_LENGTH = 4_000
    private const val MAX_PEOPLE = 12
    private const val MAX_ITEMS_PER_PERSON = 24
    internal const val PROMPT_VERSION = "adaptive-comprehensive-caption-v5"

    fun decode(
        job: SemanticEnrichmentJobRecord,
        raw: String,
        modelVersion: String,
        bindings: List<PersonVerificationBinding>,
        generation: SemanticGenerationProvenance? = null,
    ): SemanticEnrichmentResult {
        val root = JSONObject(
            extractFirstJsonObject(raw)
                ?: throw SemanticEnrichmentOutputException("Enrichment must return one JSON object"),
        )
        val sceneSummary = root.safeText("sceneSummary", 600)
        val detailedCaption = root.safeText("detailedCaption", MAX_CAPTION_LENGTH)
        val activityState = root.safeText("activityState", 40)
            .uppercase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_')
        val activityIsObserved = activityState == "OBSERVED"
        val baseFacts = SemanticFactCodec.decode(job, raw, modelVersion, generation)
            .filterNot { it.predicate in ACTIVITY_FACT_PREDICATES }
        val bindingByLabel = bindings.distinctBy(PersonVerificationBinding::clusterId)
            .associateBy(PersonVerificationBinding::stableLabel)
        if (detailedCaption.isNotBlank() && sceneSummary.isBlank()) {
            throw SemanticEnrichmentOutputException("Enrichment omitted a safe sceneSummary")
        }
        val captionText = activityAwareCaption(sceneSummary, detailedCaption)
        val captionConfidence = root.opt("captionConfidence").asConfidence()
        val activityFacts = decodeSceneActivityFacts(job, root, modelVersion, captionConfidence, activityState, generation)
        val facts = (baseFacts + activityFacts).distinctBy {
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
                    applicability = SemanticProvenanceApplicability.forGeneratedScope(
                        job.scope,
                        if (job.scope == SemanticFactScope.EXACT_DUPLICATE_GROUP) {
                            SemanticProvenanceApplicability.SAFE_FOR_EXACT_DUPLICATES
                        } else {
                            "EVIDENCE_MEDIA_ONLY"
                        },
                    ),
                    bodyRegionVersion = PersonalSemanticMemoryPolicy.BODY_REGION_VERSION,
                    modelVersion = modelVersion,
                    promptVersion = PROMPT_VERSION,
                    generationId = generation?.generationId,
                )
            }
        if (PersonalSemanticMemoryPolicy.isPersonalJob(job.reason) && caption == null) {
            throw SemanticEnrichmentOutputException("Personal enrichment omitted a safe detailedCaption")
        }
        val people = root.optJSONArray("people") ?: JSONArray()
        val refs = mutableListOf<SemanticCaptionPersonRefRecord>()
        val personFacts = mutableListOf<PersonVisualFactRecord>()
        val confidentlyAssociatedLabels = mutableSetOf<String>()
        val visibilityByLabel = mutableMapOf<String, PersonVisibility>()
        for (index in 0 until minOf(people.length(), MAX_PEOPLE)) {
            val person = people.optJSONObject(index) ?: continue
            val personRef = person.safeText("personRef", 20)
            val binding = bindingByLabel[personRef] ?: continue
            val association = enumValue<PersonAssociationStatus>(person.safeText("associationStatus", 40))
                ?: PersonAssociationStatus.AMBIGUOUS
            val visibility = enumValue<PersonVisibility>(person.safeText("visibility", 40)) ?: PersonVisibility.UNKNOWN
            visibilityByLabel[personRef] = visibility
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
                    generationId = generation?.generationId,
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
                    generationId = generation?.generationId,
                )?.let(personFacts::add)
            }
            if (activityIsObserved) {
                val actions = person.optJSONArray("actions") ?: JSONArray()
                for (actionIndex in 0 until minOf(actions.length(), 12)) {
                    val action = actions.safeTextAt(actionIndex, 120)
                    val relation = normalizeActionRelation(action) ?: continue
                    if (SensitiveContentClassifier.isSensitive(action)) continue
                    personFacts += PersonVisualFactRecord(
                        mediaId = job.representativeMediaId,
                        clusterId = binding.clusterId,
                        personRef = personRef,
                        relation = relation,
                        value = action,
                        bodyRegion = actionBodyRegion(relation),
                        confidence = person.opt("confidence").asConfidence() ?: captionConfidence ?: 0.6f,
                        faceRegion = binding.faceRegion(),
                        evidenceRegion = bodyBox,
                        verdict = inferredActionVerdict(relation, visibility, bodyBox),
                        modelVersion = modelVersion,
                        promptVersion = PROMPT_VERSION,
                        generationId = generation?.generationId,
                    )
                }
            }
        }
        if (activityIsObserved) {
            personFacts += decodeTopLevelActions(
                job = job,
                actions = root.optJSONArray("actions") ?: JSONArray(),
                bindingByLabel = bindingByLabel,
                confidentlyAssociatedLabels = confidentlyAssociatedLabels,
                visibilityByLabel = visibilityByLabel,
                modelVersion = modelVersion,
                defaultConfidence = captionConfidence,
                generationId = generation?.generationId,
            )
            personFacts += decodeInteractions(
                job = job,
                interactions = root.optJSONArray("interactions") ?: JSONArray(),
                bindingByLabel = bindingByLabel,
                confidentlyAssociatedLabels = confidentlyAssociatedLabels,
                visibilityByLabel = visibilityByLabel,
                modelVersion = modelVersion,
                defaultConfidence = captionConfidence,
                generationId = generation?.generationId,
            )
        }
        return SemanticEnrichmentResult(
            facts = facts,
            caption = caption?.copy(personRefs = refs.distinctBy(SemanticCaptionPersonRefRecord::clusterId)),
            personFacts = personFacts.distinctBy {
                "${it.clusterId}|${it.relation}|${it.category}|${it.itemType}|${it.value}|${it.bodyRegion}"
            },
            generation = generation,
        )
    }

    private fun decodeSceneActivityFacts(
        job: SemanticEnrichmentJobRecord,
        root: JSONObject,
        modelVersion: String,
        defaultConfidence: Float?,
        activityState: String,
        generation: SemanticGenerationProvenance?,
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
                    applicability = SemanticProvenanceApplicability.forGeneratedScope(job.scope, applicability),
                    modelVersion = modelVersion,
                    promptVersion = PROMPT_VERSION,
                    generationId = generation?.generationId,
                ),
            )
        }

        root.safeText("imageSubject", 300).takeIf(String::isNotBlank)?.let {
            addFact("image_subject", it, defaultConfidence)
        }
        activityState.takeIf(String::isNotBlank)?.let {
            if (it in ACTIVITY_STATES) addFact("activity_state", it, defaultConfidence)
        }
        root.safeText("observedActivity", 300).takeIf {
            it.isNotBlank() && activityState == "OBSERVED"
        }?.let {
            addFact("observed_activity", it, defaultConfidence)
        }
        root.safeText("sceneSummary", 600).takeIf(String::isNotBlank)?.let {
            addFact("scene_summary", it, defaultConfidence)
        }
        if (activityState == "OBSERVED") root.optJSONObject("primaryActivity")?.let { activity ->
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
        visibilityByLabel: Map<String, PersonVisibility>,
        modelVersion: String,
        defaultConfidence: Float?,
        generationId: String?,
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
            val relation = normalizeActionRelation(action) ?: continue
            if (hasNegativePredicate(objectRef)) continue
            val visibility = enumValue<PersonVisibility>(actionObject.safeText("visibility", 40))
                ?: visibilityByLabel[personRef]
                ?: PersonVisibility.UNKNOWN
            val evidenceRegion = actionObject.optJSONArray("region")?.normalizedRegion()
            val inferredVerdict = inferredActionVerdict(relation, visibility, evidenceRegion)
            val explicitAssociation = enumValue<PersonAssociationStatus>(actionObject.safeText("associationStatus", 40))
                ?: PersonAssociationStatus.CONFIDENT
            val verdict = if (explicitAssociation == PersonAssociationStatus.CONFIDENT) {
                safeVisualVerdict(
                    enumValue<PersonVisualVerdict>(actionObject.safeText("verdict", 40)),
                    inferredVerdict,
                )
            } else {
                PersonVisualVerdict.AMBIGUOUS
            }
            add(
                PersonVisualFactRecord(
                    mediaId = job.representativeMediaId,
                    clusterId = binding.clusterId,
                    personRef = personRef,
                    relation = relation,
                    itemType = objectRef.takeIf(String::isNotBlank),
                    value = value,
                    bodyRegion = actionBodyRegion(relation),
                    confidence = actionObject.opt("confidence").asConfidence() ?: defaultConfidence ?: 0.6f,
                    faceRegion = binding.faceRegion(),
                    evidenceRegion = evidenceRegion,
                    associationStatus = explicitAssociation,
                    verdict = verdict,
                    modelVersion = modelVersion,
                    promptVersion = PROMPT_VERSION,
                    generationId = generationId,
                ),
            )
        }
    }

    private fun decodeInteractions(
        job: SemanticEnrichmentJobRecord,
        interactions: JSONArray,
        bindingByLabel: Map<String, PersonVerificationBinding>,
        confidentlyAssociatedLabels: Set<String>,
        visibilityByLabel: Map<String, PersonVisibility>,
        modelVersion: String,
        defaultConfidence: Float?,
        generationId: String?,
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
            val relation = normalizeInteractionPredicate(predicate) ?: continue
            val subjectVisibility = visibilityByLabel[subjectRef] ?: PersonVisibility.UNKNOWN
            val targetVisibility = visibilityByLabel[targetRef] ?: PersonVisibility.UNKNOWN
            val inferredVerdict = inferredInteractionVerdict(subjectVisibility, targetVisibility)
            val explicitAssociation = enumValue<PersonAssociationStatus>(interaction.safeText("associationStatus", 40))
                ?: PersonAssociationStatus.CONFIDENT
            val verdict = if (explicitAssociation == PersonAssociationStatus.CONFIDENT) {
                safeVisualVerdict(
                    enumValue<PersonVisualVerdict>(interaction.safeText("verdict", 40)),
                    inferredVerdict,
                )
            } else {
                PersonVisualVerdict.AMBIGUOUS
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
                    associationStatus = explicitAssociation,
                    verdict = verdict,
                    targetClusterId = target.clusterId,
                    modelVersion = modelVersion,
                    promptVersion = PROMPT_VERSION,
                    generationId = generationId,
                ),
            )
        }
    }

    private fun normalizeActionRelation(raw: String): PersonVisualRelation? {
        if (raw.isBlank() || hasNegativePredicate(raw)) return null
        val normalized = normalizePredicate(raw)
        if (normalized.isBlank() || normalized in PLACEHOLDER_VALUES) return null
        val tokens = normalized.split(' ').filter(String::isNotBlank)
        return when {
            tokens.any { it in HOLDING_ACTIONS } -> PersonVisualRelation.HOLDING
            tokens.any { it in CARRYING_ACTIONS } -> PersonVisualRelation.CARRYING
            tokens.any { it in USING_ACTIONS } -> PersonVisualRelation.USING
            tokens.any { it in OBSERVED_ACTIONS } -> PersonVisualRelation.ACTION
            else -> null
        }
    }

    private fun actionBodyRegion(relation: PersonVisualRelation): BodyRegion = when (relation) {
        PersonVisualRelation.HOLDING,
        PersonVisualRelation.CARRYING,
        PersonVisualRelation.USING,
        -> BodyRegion.HAND
        else -> BodyRegion.FULL_BODY
    }

    private fun inferredActionVerdict(
        relation: PersonVisualRelation,
        visibility: PersonVisibility,
        evidenceRegion: List<Float>?,
    ): PersonVisualVerdict = when {
        visibility == PersonVisibility.UNKNOWN || visibility == PersonVisibility.FACE_ONLY ->
            PersonVisualVerdict.NOT_VISIBLE
        visibility == PersonVisibility.LOWER_BODY -> PersonVisualVerdict.NOT_VISIBLE
        visibility == PersonVisibility.PARTIAL || visibility == PersonVisibility.OCCLUDED ->
            PersonVisualVerdict.AMBIGUOUS
        relation in setOf(PersonVisualRelation.HOLDING, PersonVisualRelation.CARRYING, PersonVisualRelation.USING) &&
            visibility == PersonVisibility.UPPER_BODY && evidenceRegion == null ->
            PersonVisualVerdict.NOT_VISIBLE
        relation == PersonVisualRelation.ACTION && visibility != PersonVisibility.FULL_BODY ->
            PersonVisualVerdict.NOT_VISIBLE
        else -> PersonVisualVerdict.VERIFIED_TRUE
    }

    private fun inferredInteractionVerdict(
        subjectVisibility: PersonVisibility,
        targetVisibility: PersonVisibility,
    ): PersonVisualVerdict {
        val visibilities = setOf(subjectVisibility, targetVisibility)
        return when {
            visibilities.any { it == PersonVisibility.UNKNOWN || it == PersonVisibility.FACE_ONLY || it == PersonVisibility.LOWER_BODY } ->
                PersonVisualVerdict.NOT_VISIBLE
            visibilities.any { it == PersonVisibility.PARTIAL || it == PersonVisibility.OCCLUDED } ->
                PersonVisualVerdict.AMBIGUOUS
            else -> PersonVisualVerdict.VERIFIED_TRUE
        }
    }

    private fun safeVisualVerdict(
        explicit: PersonVisualVerdict?,
        inferred: PersonVisualVerdict,
    ): PersonVisualVerdict = if (inferred == PersonVisualVerdict.VERIFIED_TRUE) {
        explicit ?: inferred
    } else {
        inferred
    }

    private fun normalizeInteractionPredicate(raw: String): PersonVisualRelation? {
        if (raw.isBlank() || hasNegativePredicate(raw)) return null
        val normalized = normalizePredicate(raw)
        if (normalized.isBlank() || normalized in PLACEHOLDER_VALUES) return null
        val tokens = normalized.split(' ').filter(String::isNotBlank)
        return when {
            tokens == listOf("standing", "beside") ||
                tokens == listOf("standing", "next", "to") ||
                tokens == listOf("standing", "near") -> PersonVisualRelation.STANDING_BESIDE
            tokens == listOf("sitting", "beside") ||
                tokens == listOf("sitting", "next", "to") ||
                tokens == listOf("sitting", "near") -> PersonVisualRelation.SITTING_BESIDE
            tokens == listOf("interacting") || tokens == listOf("interacting", "with") -> PersonVisualRelation.INTERACTING_WITH
            else -> null
        }
    }

    private fun hasNegativePredicate(raw: String): Boolean =
        NEGATIVE_PREDICATE.containsMatchIn(raw.lowercase(Locale.ROOT)) ||
            normalizePredicate(raw).split(' ').any { it in NEGATIVE_TOKENS }

    private fun normalizePredicate(raw: String): String =
        raw.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    private fun JSONArray.safeTextAt(index: Int, maximumLength: Int): String {
        return SemanticCaptionValuePolicy.text(opt(index), maximumLength)
    }

    private val NEGATIVE_PREDICATE = Regex(
        """\b(?:not|never|without|no|isn't|isnt|aren't|arent|wasn't|wasnt|weren't|werent|doesn't|doesnt|don't|dont|can't|cant|cannot|won't|wont)\b""",
    )
    private val NEGATIVE_TOKENS = setOf("not", "never", "without", "no", "isnt", "arent", "wasnt", "werent", "doesnt", "dont", "cant", "cannot", "wont")
    private val PLACEHOLDER_VALUES = setOf("null", "undefined", "unknown", "n a", "na")
    private val HOLDING_ACTIONS = setOf("hold", "holds", "holding", "held")
    private val CARRYING_ACTIONS = setOf("carry", "carries", "carrying", "carried")
    private val USING_ACTIONS = setOf("use", "uses", "using", "used")
    private val OBSERVED_ACTIONS = setOf(
        "stand", "stands", "standing", "stood",
        "sit", "sits", "sitting", "sat",
        "pose", "poses", "posing", "posed",
        "walk", "walks", "walking", "walked",
        "run", "runs", "running", "ran",
        "cut", "cuts", "cutting",
        "eat", "eats", "eating", "ate",
        "drink", "drinks", "drinking", "drank",
        "dance", "dances", "dancing", "danced",
        "play", "plays", "playing", "played",
        "open", "opens", "opening", "opened",
        "close", "closes", "closing", "closed",
        "wave", "waves", "waving", "waved",
        "talk", "talks", "talking", "spoke", "speaking",
        "read", "reads", "reading",
        "write", "writes", "writing", "wrote",
        "look", "looks", "looking",
        "smile", "smiles", "smiling",
        "sleep", "sleeps", "sleeping",
        "feed", "feeds", "feeding",
    )

    private fun activityAwareCaption(sceneSummary: String, detailedCaption: String): String {
        if (detailedCaption.isBlank()) return ""
        val normalizedSummary = sceneSummary.trim().trimEnd('.', '!', '?')
        if (normalizedSummary.isBlank()) return detailedCaption
        val normalizedCaption = detailedCaption.trim()
        val opening = captionSentences(normalizedCaption).take(2).joinToString(" ")
        return if (
            normalizedCaption.startsWith(normalizedSummary, ignoreCase = true) ||
            captionOpeningCoversSummary(normalizedSummary, opening)
        ) {
            normalizedCaption
        } else {
            boundedCaption(
                listOf("$normalizedSummary.") + captionSentences(normalizedCaption),
            )
        }
    }

    private fun captionSentences(value: String): List<String> =
        Regex("[^.!?]+(?:[.!?]+|$)")
            .findAll(value)
            .map { it.value.trim() }
            .filter(String::isNotBlank)
            .toList()
            .ifEmpty { listOf(value.trim()) }

    private fun captionOpeningCoversSummary(summary: String, opening: String): Boolean {
        val summaryTokens = captionComparisonTokens(summary)
        val openingTokens = captionComparisonTokens(opening)
        if (summaryTokens.isEmpty() || openingTokens.isEmpty()) return false
        val overlap = summaryTokens.intersect(openingTokens).size.toDouble()
        val summaryCoverage = overlap / summaryTokens.size
        val openingCoverage = overlap / openingTokens.size
        return summaryCoverage >= 0.72 && openingCoverage >= 0.55
    }

    private fun captionComparisonTokens(value: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+")
            .findAll(value.lowercase(Locale.ROOT))
            .map { normalizeCaptionToken(it.value) }
            .filter { it.length > 1 }
            .toSet()

    private fun normalizeCaptionToken(token: String): String {
        val stemmed = when {
            token.endsWith("ing") && token.length > 5 -> token.dropLast(3)
            token.endsWith("ed") && token.length > 4 -> token.dropLast(2)
            token.endsWith("es") && token.length > 4 -> token.dropLast(2)
            token.endsWith("s") && token.length > 3 -> token.dropLast(1)
            else -> token
        }
        return if (stemmed.length > 3 && stemmed.endsWith('e')) stemmed.dropLast(1) else stemmed
    }

    private fun boundedCaption(sentences: List<String>): String {
        val result = StringBuilder()
        sentences.map(String::trim).filter(String::isNotBlank).forEach { sentence ->
            val candidate = if (result.isEmpty()) sentence else "$result $sentence"
            if (candidate.length <= MAX_CAPTION_LENGTH) result.replace(0, result.length, candidate)
        }
        return result.toString()
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
        generationId: String?,
    ): PersonVisualFactRecord? {
        val itemType = item.safeText("itemType", 120).takeIf(String::isNotBlank) ?: return null
        if (itemType.length > 120 || SensitiveContentClassifier.isSensitive(itemType)) return null
        val confidence = item.opt("confidence").asConfidence() ?: return null
        val category = enumValue<WornItemCategory>(item.safeText("category", 40)) ?: defaultCategory
        val bodyRegion = enumValue<BodyRegion>(item.safeText("bodyRegion", 40)) ?: inferredBodyRegion(category, visibility)
        val evidenceRegion = item.optJSONArray("region")?.normalizedRegion()
        val verdict = safeVisualVerdict(
            enumValue<PersonVisualVerdict>(item.safeText("verdict", 40)),
            inferredItemVerdict(bodyRegion, visibility, evidenceRegion),
        )
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
            verdict = verdict,
            modelVersion = modelVersion,
            promptVersion = PROMPT_VERSION,
            generationId = generationId,
        )
    }

    private fun inferredBodyRegion(category: WornItemCategory, visibility: PersonVisibility): BodyRegion = when (category) {
        WornItemCategory.FOOTWEAR -> BodyRegion.FEET
        WornItemCategory.HEADWEAR, WornItemCategory.EYEWEAR -> BodyRegion.HEAD
        WornItemCategory.JEWELRY -> BodyRegion.NECK
        else -> if (visibility == PersonVisibility.FULL_BODY) BodyRegion.FULL_BODY else BodyRegion.UPPER_BODY
    }

    private fun inferredItemVerdict(
        bodyRegion: BodyRegion,
        visibility: PersonVisibility,
        evidenceRegion: List<Float>?,
    ): PersonVisualVerdict = when {
        visibility == PersonVisibility.UNKNOWN -> PersonVisualVerdict.NOT_VISIBLE
        visibility == PersonVisibility.OCCLUDED || visibility == PersonVisibility.PARTIAL -> PersonVisualVerdict.AMBIGUOUS
        bodyRegion in setOf(BodyRegion.FEET, BodyRegion.LOWER_BODY) &&
            visibility !in setOf(PersonVisibility.FULL_BODY, PersonVisibility.LOWER_BODY) ->
            PersonVisualVerdict.NOT_VISIBLE
        bodyRegion in setOf(BodyRegion.UPPER_BODY, BodyRegion.FULL_BODY, BodyRegion.HAND) &&
            visibility == PersonVisibility.FACE_ONLY ->
            PersonVisualVerdict.NOT_VISIBLE
        bodyRegion == BodyRegion.HEAD && visibility == PersonVisibility.FACE_ONLY ->
            if (evidenceRegion != null) PersonVisualVerdict.VERIFIED_TRUE else PersonVisualVerdict.NOT_VISIBLE
        else -> PersonVisualVerdict.VERIFIED_TRUE
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
            val value = opt(index)
            if (value is String) value.trim().takeIf { it.isNotBlank() && it.length <= 120 }?.let(::add)
        }
    }

    private fun JSONObject.safeText(key: String, maxLength: Int): String {
        return SemanticCaptionValuePolicy.text(opt(key), maxLength)
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

    private val ACTIVITY_STATES = setOf("OBSERVED", "NONE_VISIBLE", "AMBIGUOUS", "NOT_APPLICABLE")
    private val ACTIVITY_FACT_PREDICATES = setOf(
        "activity",
        "activity_state",
        "observed_activity",
        "primary_activity",
        "activity_indicator",
    )

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
