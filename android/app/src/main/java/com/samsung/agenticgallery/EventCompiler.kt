package com.samsung.agenticgallery

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Deterministic episodic compiler. Corrections are local and always override inferred grouping or labels. */
object EventCompiler {
    const val PRODUCER_VERSION = "episodic-event-v2"
    private const val MAX_CONTINUOUS_GAP_MS = 18L * 60 * 60 * 1_000
    private const val ALBUM_CHANGE_GAP_MS = 45L * 60 * 1_000
    private const val DISTANT_MOVE_GAP_MS = 30L * 60 * 1_000
    private const val DISTANT_MOVE_KM = 100.0

    fun compile(items: List<GalleryItem>, corrections: List<EventCorrectionRecord> = emptyList()): List<CompiledEvent> {
        val eligible = items.filter { it.capturedAt != null }.sortedWith(compareBy<GalleryItem> { it.capturedAt }.thenBy { it.id })
        if (eligible.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<GalleryItem>>()
        eligible.forEach { item ->
            val current = groups.lastOrNull()
            if (current == null || shouldSplit(current.last(), item)) groups += mutableListOf(item) else current += item
        }
        applyGroupingCorrections(groups, corrections.sortedBy { it.createdAt })
        return groups.filter { it.isNotEmpty() }
            .map { members -> compileGroup(members.sortedBy { it.capturedAt }, corrections) }
            .sortedBy { it.startTime }
    }

    private fun shouldSplit(previous: GalleryItem, next: GalleryItem): Boolean {
        val gap = requireNotNull(next.capturedAt) - requireNotNull(previous.capturedAt)
        if (gap > MAX_CONTINUOUS_GAP_MS) return true
        val previousAlbum = previous.album.trim()
        val nextAlbum = next.album.trim()
        if (gap > ALBUM_CHANGE_GAP_MS && previousAlbum.isNotEmpty() && nextAlbum.isNotEmpty() &&
            !previousAlbum.equals(nextAlbum, ignoreCase = true)
        ) return true
        val distance = distanceKm(previous.latitude, previous.longitude, next.latitude, next.longitude)
        return gap > DISTANT_MOVE_GAP_MS && distance != null && distance > DISTANT_MOVE_KM
    }

    private fun applyGroupingCorrections(
        groups: MutableList<MutableList<GalleryItem>>,
        corrections: List<EventCorrectionRecord>,
    ) {
        corrections.forEach { correction ->
            when (correction.operation) {
                EventCorrectionOperation.MERGE -> {
                    val affected = groups.filter { group -> group.any { it.id in correction.mediaIds } }
                    if (affected.size > 1) {
                        val merged = affected.flatten().distinctBy { it.id }.toMutableList()
                        groups.removeAll(affected.toSet())
                        groups += merged
                    }
                }
                EventCorrectionOperation.SPLIT -> {
                    val selected = mutableListOf<GalleryItem>()
                    groups.forEach { group ->
                        val moving = group.filter { it.id in correction.mediaIds }
                        selected += moving
                        group.removeAll(moving.toSet())
                    }
                    if (selected.isNotEmpty()) groups += selected.distinctBy { it.id }.toMutableList()
                }
                EventCorrectionOperation.RENAME, EventCorrectionOperation.LOCATION -> Unit
            }
        }
    }

    private fun compileGroup(members: List<GalleryItem>, corrections: List<EventCorrectionRecord>): CompiledEvent {
        val memberIds = members.map { it.id }.toSet()
        val labelCorrections = corrections.filter { correction ->
            correction.operation in setOf(EventCorrectionOperation.RENAME, EventCorrectionOperation.LOCATION) &&
                correction.mediaIds.isNotEmpty() && correction.mediaIds.all(memberIds::contains)
        }
        val correctedTitle = labelCorrections.lastOrNull { it.operation == EventCorrectionOperation.RENAME }?.title
        val correctedLocation = labelCorrections.lastOrNull { it.operation == EventCorrectionOperation.LOCATION }?.locationName
        val inferredLocation = dominant(members.map { it.location }.filter { it.isMeaningfulMetadata() })
        val title = correctedTitle ?: inferredLocation ?: inferTitle(members)
        val location = correctedLocation ?: inferredLocation
        val centroid = centroid(members)
        val searchText = buildSearchText(members, title, location)
        val corrected = labelCorrections.isNotEmpty() || corrections.any { correction ->
            correction.operation in setOf(EventCorrectionOperation.MERGE, EventCorrectionOperation.SPLIT) &&
                correction.mediaIds.any(memberIds::contains)
        }
        val sameAlbum = members.map { it.album.lowercase(Locale.ROOT) }.filter(String::isNotBlank).distinct().size <= 1
        val confidence = when {
            corrected -> 1f
            members.size >= 2 && sameAlbum && centroid != null -> .92f
            members.size >= 2 && sameAlbum -> .82f
            members.size >= 2 -> .72f
            else -> .58f
        }
        return CompiledEvent(
            id = stableId(memberIds),
            startTime = requireNotNull(members.first().capturedAt),
            endTime = requireNotNull(members.last().capturedAt),
            title = title,
            locationName = location,
            latitude = centroid?.first,
            longitude = centroid?.second,
            eventType = inferType(searchText, members),
            members = members,
            confidence = confidence,
            searchText = searchText,
            representativeMediaId = members.maxWithOrNull(
                compareBy<GalleryItem> { it.qualityScore ?: 0f }.thenBy { it.capturedAt ?: 0L },
            )?.id,
            producerVersion = PRODUCER_VERSION,
            userCorrected = corrected,
        )
    }

    private fun inferTitle(members: List<GalleryItem>): String {
        val tokenLists = members.map { tokenize(it.filename.substringBeforeLast('.')) }
        val frequency = tokenLists.flatten().groupingBy { it.lowercase(Locale.ROOT) }.eachCount()
        val minimum = if (members.size <= 2) 1 else maxOf(2, (members.size + 1) / 2)
        val ordered = tokenLists.flatten().distinctBy { it.lowercase(Locale.ROOT) }.filter { token ->
            (frequency[token.lowercase(Locale.ROOT)] ?: 0) >= minimum && token.lowercase(Locale.ROOT) !in STOP_TOKENS
        }.take(3)
        return ordered.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }.ifBlank { "Gallery memory" }
    }

    private fun buildSearchText(members: List<GalleryItem>, title: String, location: String?): String = buildString {
        append(title).append(' ').append(location.orEmpty()).append(' ')
        members.forEach { item ->
            append(item.filename).append(' ').append(item.title).append(' ').append(item.album).append(' ')
            append(item.location).append(' ').append(item.tags.joinToString(" ")).append(' ').append(item.description).append(' ')
        }
    }.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim().take(8_000)

    private fun inferType(searchText: String, members: List<GalleryItem>): String = when {
        listOf("travel", "trip", "singapore", "goa", "boarding", "hotel").any(searchText::contains) -> "TRIP"
        members.any { it.kind == MediaKind.PDF } -> "DOCUMENT_SET"
        members.size == 1 -> "MOMENT"
        else -> "MEMORY"
    }

    private fun stableId(mediaIds: Set<String>): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(mediaIds.sorted().joinToString("\u001f").toByteArray())
        return (ByteBuffer.wrap(digest).long and Long.MAX_VALUE).coerceAtLeast(1L)
    }

    private fun tokenize(value: String): List<String> = value.split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 && !it.matches(Regex("v\\d+|\\d+")) }

    private fun dominant(values: List<String>): String? = values.groupingBy { it.trim() }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .firstOrNull()?.key

    private fun String.isMeaningfulMetadata(): Boolean = isNotBlank() &&
        !equals("unknown", true) && !equals("unknown location", true)

    private fun centroid(items: List<GalleryItem>): Pair<Double, Double>? {
        val coordinates = items.mapNotNull { item ->
            if (item.latitude != null && item.longitude != null) item.latitude to item.longitude else null
        }
        return coordinates.takeIf { it.isNotEmpty() }?.let { points ->
            points.sumOf { it.first } / points.size to points.sumOf { it.second } / points.size
        }
    }

    private fun distanceKm(lat1: Double?, lon1: Double?, lat2: Double?, lon2: Double?): Double? {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return null
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 6_371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private val STOP_TOKENS = setOf(
        "img", "image", "photo", "photos", "picture", "legacy", "demo", "agenticgallerytest", "synthetic",
    )
}
