package com.askphotos.android

import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

enum class SemanticFactScope { MEDIA, VISUAL_GROUP, EVENT }
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

internal object AdaptiveRepresentativeSelector {
    fun buildPlan(
        items: List<GalleryItem>,
        eventMembership: Map<String, Long>,
        embeddingReadyIds: Set<String>,
        frequentlyRetrievedIds: List<String> = emptyList(),
    ): SemanticEnrichmentPlan {
        val eligible = items.filter {
            it.accessState == MediaAccessState.ACCESSIBLE &&
                it.indexState == IndexState.READY &&
                it.id in embeddingReadyIds &&
                (it.kind == MediaKind.IMAGE || it.kind == MediaKind.VIDEO || it.kind == MediaKind.PDF)
        }
        val byId = eligible.associateBy(GalleryItem::id)
        val exact = eligible.filter { it.perceptualHash != null }
            .groupBy { requireNotNull(it.perceptualHash) }
            .filterValues { it.size > 1 }
            .map { (hash, members) -> group("exact:$hash", "EXACT_DUPLICATE", members, 1) }
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
            .flatMap { (eventId, entries) ->
                val members = entries.mapNotNull { byId[it.key] }
                selectDiverse(members, if (members.size >= 40) 3 else 2).mapIndexed { rank, item ->
                    EventRepresentativePlan(eventId, item.id, rank, "quality_time_people_ocr_frame_diversity")
                }
            }
        val groupJobs = (exact + bursts).flatMap { group ->
            group.representatives.map { mediaId ->
                job(
                    scope = SemanticFactScope.VISUAL_GROUP,
                    subjectId = group.id,
                    mediaId = mediaId,
                    reason = if (group.kind == "EXACT_DUPLICATE") "exact_duplicate_canonical" else "diverse_group_representative",
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
        val represented = (groupJobs + eventJobs + ambiguousDocuments + frequent)
            .mapTo(hashSetOf(), SemanticEnrichmentJobRecord::representativeMediaId)
        val outliers = eligible.filterNot { it.id in represented }
            .sortedWith(compareByDescending<GalleryItem> { it.qualityScore ?: 0f }.thenBy { it.capturedAt ?: 0L })
            .take(MAX_OUTLIER_JOBS)
            .map { job(SemanticFactScope.MEDIA, it.id, it.id, "important_unique_outlier") }
        return SemanticEnrichmentPlan(
            groups = exact + bursts,
            eventRepresentatives = eventRepresentatives,
            jobs = (groupJobs + eventJobs + ambiguousDocuments + frequent + outliers)
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
    private const val MAX_DOCUMENT_JOBS = 16
    private const val MAX_FREQUENT_JOBS = 16
    private const val MAX_OUTLIER_JOBS = 16
    private const val MAX_TOTAL_JOBS = 128
}

class SemanticEnrichmentCoordinator(private val database: GalleryDatabase) {
    fun rebuildPlan(userRequested: Boolean = false): SemanticEnrichmentPlan {
        val plan = AdaptiveRepresentativeSelector.buildPlan(
            items = database.allItems(),
            eventMembership = database.eventMembership(),
            embeddingReadyIds = database.embeddingReadyMediaIds(),
            frequentlyRetrievedIds = database.frequentlyRetrievedMediaIds(),
        )
        val adjusted = if (!userRequested) plan else plan.copy(
            jobs = plan.jobs.map { it.copy(userRequested = true) },
        )
        database.replaceSemanticEnrichmentPlan(adjusted)
        return adjusted
    }
}
