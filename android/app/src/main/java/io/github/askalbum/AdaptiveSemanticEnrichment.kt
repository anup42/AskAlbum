package io.github.anup42.askalbum

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

enum class SemanticFactScope { MEDIA, EXACT_DUPLICATE_GROUP, VISUAL_GROUP, EVENT, QUERY_VERIFICATION }
enum class SemanticEnrichmentStatus { PENDING, RUNNING, COMPLETE, FAILED, AUTH_REQUIRED }

data class SemanticFactRecord(
    val scope: SemanticFactScope,
    val subjectId: String,
    val predicate: String,
    val value: String,
    val confidence: Float,
    val evidenceMediaId: String,
    val region: List<Float>? = null,
    val applicability: String = "EVIDENCE_MEDIA_ONLY",
    val modelVersion: String,
    val promptVersion: String,
)

data class SemanticEnrichmentJobRecord(
    val id: String,
    val scope: SemanticFactScope,
    val subjectId: String,
    val representativeMediaId: String,
    val reason: String,
    val status: SemanticEnrichmentStatus,
    val attemptCount: Int,
    val userRequested: Boolean,
    val modelVersion: String? = null,
    val error: String? = null,
)

data class SemanticMemoryProgress(
    val totalJobs: Int = 0,
    val pendingJobs: Int = 0,
    val runningJobs: Int = 0,
    val completedJobs: Int = 0,
    val failedJobs: Int = 0,
    val authenticationRequiredJobs: Int = 0,
    val factCount: Int = 0,
    val captionCount: Int = 0,
    val captionChunkCount: Int = 0,
    val embeddedCaptionChunkCount: Int = 0,
    val pendingCaptionChunkCount: Int = 0,
    val runningCaptionChunkCount: Int = 0,
    val failedCaptionChunkCount: Int = 0,
    val personVisualFactCount: Int = 0,
    val personalEligibleCount: Int = 0,
    val personalCompletedCount: Int = 0,
    val personalPendingCount: Int = 0,
    val personalFailedCount: Int = 0,
    val personalAuthenticationRequiredCount: Int = 0,
    val personalExactReuseCount: Int = 0,
    val personalStaleCount: Int = 0,
    val userRequestedPendingJobs: Int = 0,
    val latestError: String? = null,
) {
    val processedJobs: Int
        get() = completedJobs + failedJobs + authenticationRequiredJobs

    val hasActiveWork: Boolean
        get() = pendingJobs > 0 || runningJobs > 0
}

data class SemanticMemoryMedia(
    val item: GalleryItem,
    val facts: List<SemanticFactRecord>,
    val protectedFactCount: Int = 0,
    val captions: List<SemanticCaptionRecord> = emptyList(),
    val captionChunks: List<SemanticCaptionChunkRecord> = emptyList(),
    val personVisualFacts: List<PersonVisualFactRecord> = emptyList(),
)

data class VisualGroupPlan(
    val id: String,
    val kind: String,
    val canonicalMediaId: String,
    val members: List<String>,
    val representatives: List<String>,
)

data class EventRepresentativePlan(
    val eventId: Long,
    val mediaId: String,
    val rank: Int,
    val reason: String,
)

data class SemanticEnrichmentPlan(
    val groups: List<VisualGroupPlan>,
    val eventRepresentatives: List<EventRepresentativePlan>,
    val jobs: List<SemanticEnrichmentJobRecord>,
)

internal object PersonalSemanticMemoryPolicy {
    const val JOB_PREFIX = "personal_media:"
    const val CAPTION_POLICY_VERSION = "personal-caption-policy-v1"
    const val BODY_REGION_VERSION = "person-body-regions-v1"
    const val PROMPT_VERSION = "adaptive-comprehensive-caption-v4"

    private val familyRelationships = setOf(
        "me", "mother", "mom", "mum", "father", "dad", "brother", "sister",
        "partner", "spouse", "wife", "husband", "child", "son", "daughter",
        "grandparent", "grandmother", "grandfather", "grandma", "grandpa",
    )

    fun defaultEnabled(relationship: String?): Boolean =
        relationship?.trim()?.lowercase() in familyRelationships

    fun jobReason(modelVersion: String?): String {
        val model = modelVersion.orEmpty().ifBlank { "active-model" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(80)
        return "$JOB_PREFIX$model:$PROMPT_VERSION:$BODY_REGION_VERSION:$CAPTION_POLICY_VERSION"
    }

    fun isPersonalJob(reason: String): Boolean = reason.startsWith(JOB_PREFIX)

    fun isRecoverableStructuredOutputFailure(error: String?): Boolean = error in setOf(
        "Enrichment omitted the facts array",
        "Enrichment returned malformed JSON",
        "Enrichment must return one JSON object",
    )

    fun exactContentDigest(bytes: ByteArray, width: Int, height: Int): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(96) {
            append("sha256-file-v1:")
            append(width)
            append('x')
            append(height)
            append(':')
            digest.forEach { append("%02x".format(it.toInt() and 0xff)) }
        }
    }
}

internal object AdaptiveRepresentativeSelector {
    fun buildPlan(
        items: List<GalleryItem>,
        eventMembership: Map<String, Long>,
        embeddingReadyIds: Set<String>,
        frequentlyRetrievedIds: List<String> = emptyList(),
        reviewedPeopleByMedia: Map<String, Set<String>> = emptyMap(),
    ): SemanticEnrichmentPlan {
        val eligible = items.filter {
            it.accessState == MediaAccessState.ACCESSIBLE &&
                it.indexState == IndexState.READY &&
                it.id in embeddingReadyIds &&
                (it.kind == MediaKind.IMAGE || it.kind == MediaKind.VIDEO || it.kind == MediaKind.PDF)
        }
        val byId = eligible.associateBy(GalleryItem::id)
        val exact = eligible.filter { it.kind == MediaKind.IMAGE && it.exactContentDigest != null }
            .groupBy { requireNotNull(it.exactContentDigest) }
            .filterValues { it.size > 1 }
            .map { (digest, members) ->
                val stable = UUID.nameUUIDFromBytes(digest.toByteArray(StandardCharsets.UTF_8))
                group("exact:$stable", "EXACT_DUPLICATE", members, 1)
            }
        val exactMembers = exact.flatMapTo(hashSetOf(), VisualGroupPlan::members)
        val bursts = eligible.filterNot { it.id in exactMembers }
            .filter { it.capturedAt != null }
            .groupBy {
                val timeBucket = requireNotNull(it.capturedAt) / BURST_WINDOW_MS
                "${it.location.lowercase()}:$timeBucket"
            }
            .filterValues { it.size > 1 }
            .map { (key, members) -> group("burst:$key", "BURST_SCENE", members, if (members.size >= 12) 3 else 2) }
        val eventRepresentatives = eventMembership.entries.groupBy(Map.Entry<String, Long>::value)
            .entries
            .sortedByDescending { (_, entries) ->
                entries.maxOfOrNull { entry -> byId[entry.key]?.capturedAt ?: Long.MIN_VALUE } ?: Long.MIN_VALUE
            }
            .flatMap { (eventId, entries) ->
                val members = entries.mapNotNull { byId[it.key] }
                val peopleMembers = members.filter { reviewedPeopleByMedia[it.id].orEmpty().isNotEmpty() }
                val contextMembers = members.filter { reviewedPeopleByMedia[it.id].orEmpty().isEmpty() }
                val selected = if (peopleMembers.isEmpty()) {
                    selectDiverse(members, if (members.size >= 40) 3 else 2)
                } else {
                    (
                        selectDiverse(peopleMembers, 1) +
                            selectDiverse(contextMembers, 1) +
                            selectDiverse(members, if (members.size >= 40) 3 else 2)
                        ).distinctBy(GalleryItem::id).take(if (members.size >= 40) 3 else 2)
                }
                val peopleIds = peopleMembers.mapTo(hashSetOf(), GalleryItem::id)
                selected.mapIndexed { rank, item ->
                    val reason = when {
                        peopleMembers.isEmpty() -> "quality_time_people_ocr_frame_diversity"
                        item.id in peopleIds -> "personal_event_people_representative"
                        else -> "personal_event_context_representative"
                    }
                    EventRepresentativePlan(eventId, item.id, rank, reason)
                }
            }
        val exactJobs = exact.flatMap { group ->
            group.representatives.map { mediaId ->
                job(
                    scope = SemanticFactScope.EXACT_DUPLICATE_GROUP,
                    subjectId = group.id,
                    mediaId = mediaId,
                    reason = "exact_duplicate_canonical",
                )
            }
        }
        val burstJobs = bursts.flatMap { group ->
            group.representatives.map { mediaId ->
                job(
                    scope = SemanticFactScope.VISUAL_GROUP,
                    subjectId = group.id,
                    mediaId = mediaId,
                    reason = "diverse_group_representative",
                )
            }
        }
        val eventJobs = eventRepresentatives.map {
            job(SemanticFactScope.EVENT, it.eventId.toString(), it.mediaId, "diverse_event_representative")
        }
        val ambiguousDocuments = eligible.filter {
            it.kind == MediaKind.PDF || (it.ocrText.length >= 80 && it.tags.none { tag -> tag.contains("receipt", true) })
        }.sortedByDescending { it.qualityScore ?: 0f }.take(MAX_DOCUMENT_JOBS)
            .map { job(SemanticFactScope.MEDIA, it.id, it.id, "ambiguous_document") }
        val frequent = frequentlyRetrievedIds.mapNotNull(byId::get).take(MAX_FREQUENT_JOBS)
            .map { job(SemanticFactScope.MEDIA, it.id, it.id, "frequently_retrieved") }
        val represented = (exactJobs + burstJobs + eventJobs + ambiguousDocuments + frequent)
            .mapTo(hashSetOf(), SemanticEnrichmentJobRecord::representativeMediaId)
        val outliers = eligible.filterNot { it.id in represented }
            .sortedWith(compareByDescending<GalleryItem> { it.qualityScore ?: 0f }.thenBy { it.capturedAt ?: 0L })
            .take(MAX_OUTLIER_JOBS)
            .map { job(SemanticFactScope.MEDIA, it.id, it.id, "important_unique_outlier") }
        val prioritizedJobs = eventJobs.take(MAX_EVENT_JOBS) +
            burstJobs.take(MAX_BURST_JOBS) +
            exactJobs.take(MAX_EXACT_DUPLICATE_JOBS) +
            ambiguousDocuments +
            frequent +
            outliers
        val fallbackJobs = eventJobs +
            burstJobs +
            ambiguousDocuments +
            frequent +
            outliers +
            exactJobs.take(MAX_EXACT_DUPLICATE_JOBS)
        return SemanticEnrichmentPlan(
            groups = exact + bursts,
            eventRepresentatives = eventRepresentatives,
            jobs = (prioritizedJobs + fallbackJobs)
                .distinctBy(SemanticEnrichmentJobRecord::id)
                .take(MAX_TOTAL_JOBS),
        )
    }

    fun selectDiverse(items: List<GalleryItem>, maximum: Int): List<GalleryItem> {
        if (items.size <= maximum) return items.sortedByDescending { it.qualityScore ?: 0f }
        val selected = mutableListOf(items.maxBy { it.qualityScore ?: 0f })
        while (selected.size < maximum) {
            val next = items.filterNot { candidate -> selected.any { it.id == candidate.id } }.maxBy { candidate ->
                val quality = candidate.qualityScore ?: 0f
                val diversity = selected.minOf { chosen -> diversity(candidate, chosen) }
                quality * 0.35f + diversity * 0.65f
            }
            selected += next
        }
        return selected
    }

    private fun group(id: String, kind: String, members: List<GalleryItem>, maxReps: Int): VisualGroupPlan {
        val reps = selectDiverse(members, maxReps)
        return VisualGroupPlan(id, kind, reps.first().id, members.map(GalleryItem::id), reps.map(GalleryItem::id))
    }

    private fun diversity(first: GalleryItem, second: GalleryItem): Float {
        val hash = when {
            first.perceptualHash == null || second.perceptualHash == null -> 0.5f
            else -> java.lang.Long.bitCount(first.perceptualHash xor second.perceptualHash) / 64f
        }
        val time = minOf(1f, abs((first.capturedAt ?: 0L) - (second.capturedAt ?: 0L)).toFloat() / DAY_MS)
        val people = minOf(1f, abs(first.faceCount - second.faceCount) / 4f)
        val ocr = if (first.ocrText.isBlank() == second.ocrText.isBlank()) 0f else 1f
        val firstFrame = first.width.toFloat() / max(1, first.height)
        val secondFrame = second.width.toFloat() / max(1, second.height)
        val framing = minOf(1f, abs(firstFrame - secondFrame))
        return hash * 0.35f + time * 0.2f + people * 0.15f + ocr * 0.15f + framing * 0.15f
    }

    private fun job(
        scope: SemanticFactScope,
        subjectId: String,
        mediaId: String,
        reason: String,
        userRequested: Boolean = false,
    ): SemanticEnrichmentJobRecord {
        val stable = "$scope|$subjectId|$mediaId|$reason"
        return SemanticEnrichmentJobRecord(
            id = UUID.nameUUIDFromBytes(stable.toByteArray(StandardCharsets.UTF_8)).toString(),
            scope = scope,
            subjectId = subjectId,
            representativeMediaId = mediaId,
            reason = reason,
            status = SemanticEnrichmentStatus.PENDING,
            attemptCount = 0,
            userRequested = userRequested,
        )
    }

    private const val BURST_WINDOW_MS = 30L * 60L * 1000L
    private const val DAY_MS = 24f * 60f * 60f * 1000f
    private const val MAX_EVENT_JOBS = 48
    private const val MAX_BURST_JOBS = 20
    private const val MAX_EXACT_DUPLICATE_JOBS = 12
    private const val MAX_DOCUMENT_JOBS = 16
    private const val MAX_FREQUENT_JOBS = 16
    private const val MAX_OUTLIER_JOBS = 16
    private const val MAX_TOTAL_JOBS = 128
}

class SemanticEnrichmentCoordinator(private val database: GalleryDatabase) {
    fun rebuildPlan(userRequested: Boolean = false, modelVersion: String? = null): SemanticEnrichmentPlan {
        val plan = AdaptiveRepresentativeSelector.buildPlan(
            items = database.allItems(),
            eventMembership = database.eventMembership(),
            embeddingReadyIds = database.embeddingReadyMediaIds(),
            frequentlyRetrievedIds = database.frequentlyRetrievedMediaIds(),
            reviewedPeopleByMedia = database.reviewedPersonClusterIdsByMedia(),
        )
        val adjusted = if (!userRequested) plan else plan.copy(
            jobs = plan.jobs.map { it.copy(userRequested = true) },
        )
        database.replaceSemanticEnrichmentPlan(adjusted)
        database.queueEligiblePersonalSemanticMemoryJobs(modelVersion, userRequested)
        return adjusted
    }
}
