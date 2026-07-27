package com.samsung.agenticgallery

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.math.ln

enum class CaptionChunkType {
    SCENE,
    OCCASION,
    PLACE_CONTEXT,
    PERSON_APPEARANCE,
    PERSON_ACTION,
    PERSON_RELATION,
    OBJECTS,
    ANIMALS,
    FOOD,
    VEHICLES,
    BACKGROUND,
    COMPOSITION,
    VISIBLE_TEXT_CATEGORY,
    OTHER,
}

enum class CaptionEmbeddingState {
    PENDING,
    RUNNING,
    COMPLETE,
    FAILED_RETRYABLE,
    FAILED_EXHAUSTED,
    FAILED_PERMANENT,
}

data class SemanticCaptionChunkRecord(
    val id: String,
    val captionId: String,
    val mediaId: String,
    val scope: SemanticFactScope,
    val scopeId: String,
    val evidenceMediaId: String,
    val clusterId: String?,
    val chunkType: CaptionChunkType,
    val exactText: String,
    val confidence: Float,
    val applicability: String,
    val captionModelVersion: String,
    val captionPromptVersion: String,
    val chunkPolicyVersion: String,
    val embeddingModelVersion: String?,
    val embeddingState: CaptionEmbeddingState,
    val attemptCount: Int = 0,
    val error: String? = null,
    val leaseOwner: String? = null,
    val leaseExpiresAt: Long? = null,
    val nextAttemptAt: Long = 0L,
    val lastProgressAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

data class CaptionEmbeddingProgress(
    val captionedMediaCount: Int = 0,
    val chunkedMediaCount: Int = 0,
    val totalChunkCount: Int = 0,
    val embeddedChunkCount: Int = 0,
    val pendingChunkCount: Int = 0,
    val runningChunkCount: Int = 0,
    val delayedRetryCount: Int = 0,
    val failedChunkCount: Int = 0,
    val staleChunkCount: Int = 0,
)

data class CaptionVectorHit(
    val chunkId: String,
    val score: Float,
    val queryVariant: String,
)

data class CaptionVectorSearchReport(
    val status: ChannelStatus,
    val eligibleChunkCount: Int,
    val indexedChunkCount: Int,
    val searchedChunkCount: Int,
    val hits: List<CaptionVectorHit>,
    val modelVersion: String?,
    val errorCode: String? = null,
)

internal object SemanticCaptionChunker {
    const val POLICY_VERSION = "caption-chunks-v2"
    const val MAX_CHUNKS_PER_CAPTION = 24
    private const val MAX_WORDS_PER_CHUNK = 40
    private const val MAX_CHARS_PER_CHUNK = 360
    private val sentenceBoundary = Regex("(?<=[.!?])\\s+|[\\r\\n]+")
    private val words = Regex("[\\p{L}\\p{M}\\p{N}]+")

    fun generate(
        caption: SemanticCaptionRecord,
        facts: List<SemanticFactRecord>,
        personFacts: List<PersonVisualFactRecord>,
    ): List<SemanticCaptionChunkRecord> {
        if (caption.text.isBlank() || SensitiveContentClassifier.isSensitive(caption.text)) return emptyList()
        val candidates = mutableListOf<Candidate>()

        personFacts
            .filter {
                it.mediaId == caption.evidenceMediaId &&
                    it.verdict == PersonVisualVerdict.VERIFIED_TRUE &&
                    it.associationStatus == PersonAssociationStatus.CONFIDENT
            }
            .groupBy { it.clusterId }
            .forEach { (clusterId, clusterFacts) ->
                val appearance = clusterFacts.filter {
                    it.relation in setOf(
                        PersonVisualRelation.WEARING,
                        PersonVisualRelation.CARRYING,
                        PersonVisualRelation.HOLDING,
                        PersonVisualRelation.USING,
                    )
                }
                if (appearance.isNotEmpty()) {
                    candidates += Candidate(
                        CaptionChunkType.PERSON_APPEARANCE,
                        appearance.joinToString("; ") { fact ->
                            "${fact.relation.name.lowercase(Locale.ROOT).replace('_', ' ')} ${fact.value}"
                        },
                        appearance.maxOf(PersonVisualFactRecord::confidence),
                        clusterId,
                    )
                }
                val actions = clusterFacts.filter { it.relation == PersonVisualRelation.ACTION }
                if (actions.isNotEmpty()) {
                    candidates += Candidate(
                        CaptionChunkType.PERSON_ACTION,
                        actions.joinToString("; ") { it.value },
                        actions.maxOf(PersonVisualFactRecord::confidence),
                        clusterId,
                    )
                }
                clusterFacts.filter {
                    it.relation in setOf(
                        PersonVisualRelation.STANDING_BESIDE,
                        PersonVisualRelation.SITTING_BESIDE,
                        PersonVisualRelation.INTERACTING_WITH,
                    )
                }.forEach {
                    candidates += Candidate(
                        CaptionChunkType.PERSON_RELATION,
                        "${it.relation.name.lowercase(Locale.ROOT).replace('_', ' ')} another reviewed person",
                        it.confidence,
                        clusterId,
                    )
                }
            }

        facts
            .filter {
                it.evidenceMediaId == caption.evidenceMediaId &&
                    it.applicability !in setOf("STALE_PERSON_BINDING", "LEGACY_GROUP_CONTEXT_ONLY")
            }
            .groupBy { classify("${it.predicate} ${it.value}") }
            .forEach { (type, grouped) ->
                candidates += Candidate(
                    type,
                    grouped.joinToString("; ") { "${it.predicate.replace('_', ' ')}: ${it.value}" },
                    grouped.maxOf(SemanticFactRecord::confidence),
                )
            }

        caption.text.split(sentenceBoundary)
            .flatMap(::boundedPieces)
            .filter(String::isNotBlank)
            .forEach { candidates += Candidate(classify(it), it, caption.confidence) }

        val accepted = mutableListOf<Candidate>()
        candidates.forEach { candidate ->
            val text = candidate.text
                .replace(Regex("\\b(?:null|undefined)\\b", RegexOption.IGNORE_CASE), " ")
                .trim()
                .replace(Regex("\\s+"), " ")
                .take(MAX_CHARS_PER_CHUNK)
            if (text.isBlank() || SensitiveContentClassifier.isSensitive(text)) return@forEach
            val normalized = normalize(text)
            if (normalized.isBlank()) return@forEach
            if (accepted.any { duplicate(normalized, normalize(it.text)) }) return@forEach
            accepted += candidate.copy(text = text)
        }

        val now = System.currentTimeMillis()
        return accepted.take(MAX_CHUNKS_PER_CAPTION).mapIndexed { index, candidate ->
            val stable = listOf(
                caption.id,
                caption.evidenceMediaId,
                caption.scope.name,
                candidate.clusterId.orEmpty(),
                candidate.type.name,
                POLICY_VERSION,
                index.toString(),
                normalize(candidate.text),
            ).joinToString("|")
            SemanticCaptionChunkRecord(
                id = UUID.nameUUIDFromBytes(stable.toByteArray(Charsets.UTF_8)).toString(),
                captionId = caption.id,
                mediaId = caption.evidenceMediaId,
                scope = caption.scope,
                scopeId = caption.subjectId,
                evidenceMediaId = caption.evidenceMediaId,
                clusterId = candidate.clusterId,
                chunkType = candidate.type,
                exactText = candidate.text,
                confidence = candidate.confidence.coerceIn(0f, 1f),
                applicability = caption.applicability,
                captionModelVersion = caption.modelVersion,
                captionPromptVersion = caption.promptVersion,
                chunkPolicyVersion = POLICY_VERSION,
                embeddingModelVersion = null,
                embeddingState = CaptionEmbeddingState.PENDING,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    private fun boundedPieces(text: String): List<String> {
        val tokens = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (tokens.isEmpty()) return emptyList()
        return tokens.chunked(MAX_WORDS_PER_CHUNK).map { it.joinToString(" ").take(MAX_CHARS_PER_CHUNK) }
    }

    private fun classify(text: String): CaptionChunkType {
        val normalized = text.lowercase(Locale.ROOT)
        return when {
            listOf("birthday", "wedding", "celebration", "ceremony", "festival", "occasion").any(normalized::contains) ->
                CaptionChunkType.OCCASION
            listOf("location", "place", "venue", "beach", "park", "restaurant", "hotel").any(normalized::contains) ->
                CaptionChunkType.PLACE_CONTEXT
            listOf("animal", "dog", "cat", "bird", "pet").any(normalized::contains) -> CaptionChunkType.ANIMALS
            listOf("food", "meal", "cake", "drink", "dish").any(normalized::contains) -> CaptionChunkType.FOOD
            listOf("car", "vehicle", "bus", "train", "airplane", "motorcycle").any(normalized::contains) ->
                CaptionChunkType.VEHICLES
            listOf("background", "behind", "distant").any(normalized::contains) -> CaptionChunkType.BACKGROUND
            listOf("composition", "portrait", "selfie", "close-up", "wide shot", "group shot").any(normalized::contains) ->
                CaptionChunkType.COMPOSITION
            listOf("visible text", "sign", "screen", "poster").any(normalized::contains) ->
                CaptionChunkType.VISIBLE_TEXT_CATEGORY
            listOf("object", "table", "chair", "furniture", "decoration", "holding").any(normalized::contains) ->
                CaptionChunkType.OBJECTS
            listOf("scene", "setting", "indoor", "outdoor", "lighting", "weather").any(normalized::contains) ->
                CaptionChunkType.SCENE
            else -> CaptionChunkType.OTHER
        }
    }

    private fun normalize(text: String): String = words.findAll(text.lowercase(Locale.ROOT))
        .joinToString(" ") { it.value }

    private fun duplicate(left: String, right: String): Boolean {
        if (left == right || left.contains(right) || right.contains(left)) return true
        val leftTerms = left.split(' ').toSet()
        val rightTerms = right.split(' ').toSet()
        val union = leftTerms union rightTerms
        return union.isNotEmpty() && (leftTerms intersect rightTerms).size.toDouble() / union.size >= 0.88
    }

    private data class Candidate(
        val type: CaptionChunkType,
        val text: String,
        val confidence: Float,
        val clusterId: String? = null,
    )
}

internal object CaptionLexicalQueryBuilder {
    private val token = Regex("[\\p{L}\\p{M}\\p{N}]+")
    private val stopWords = setOf(
        "show", "photos", "photo", "pictures", "picture", "images", "image", "with", "where",
        "that", "this", "from", "have", "wearing", "please", "some", "में", "वाली", "दिखाओ",
    )

    fun variants(queries: Collection<String>): List<String> = queries
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .take(8)

    fun ftsExpression(query: String): String? {
        val terms = token.findAll(query.lowercase(Locale.ROOT))
            .map(MatchResult::value)
            .filter { it.length > 2 && it !in stopWords }
            .distinct()
            .take(16)
            .toList()
        if (terms.isEmpty()) return null
        val phrase = if (terms.size > 1) "\"${terms.joinToString(" ")}\" OR " else ""
        return phrase + terms.joinToString(" OR ") { "\"$it\"*" }
    }
}

internal object CaptionFtsRanker {
    fun bm25(matchInfo: ByteArray): Double {
        if (matchInfo.isEmpty() || matchInfo.size % Int.SIZE_BYTES != 0) return 0.0
        val values = IntArray(matchInfo.size / Int.SIZE_BYTES)
        ByteBuffer.wrap(matchInfo).order(ByteOrder.nativeOrder()).asIntBuffer().get(values)
        if (values.size < 5) return 0.0
        val phrases = values[0]
        val columns = values[1]
        val documents = values[2].coerceAtLeast(1)
        val statsOffset = 3 + columns * 2
        if (phrases <= 0 || columns <= 0 || values.size < statsOffset + phrases * columns * 3) return 0.0
        var score = 0.0
        for (phrase in 0 until phrases) {
            for (column in 0 until columns) {
                val currentLength = values[3 + columns + column].coerceAtLeast(1)
                val averageLength = values[3 + column].coerceAtLeast(1)
                val offset = statsOffset + (phrase * columns + column) * 3
                val termFrequency = values[offset].coerceAtLeast(0)
                val matchingDocuments = values[offset + 2].coerceIn(0, documents)
                if (termFrequency == 0) continue
                val idf = ln(1.0 + (documents - matchingDocuments + 0.5) / (matchingDocuments + 0.5))
                val normalization = 1.2 * (0.25 + 0.75 * currentLength.toDouble() / averageLength)
                score += idf * (termFrequency * 2.2) / (termFrequency + normalization)
            }
        }
        return score
    }
}

internal object PersonCaptionConstraintPolicy {
    fun requiredClusterIds(plan: GalleryQueryPlan): Set<String> {
        val peopleIds = plan.peopleClauses.filter(PersonClause::mustBePresent).mapTo(mutableSetOf(), PersonClause::personId)
        return plan.semanticClauses.asSequence()
            .filter { it.subject == SemanticSubject.PERSON && it.polarity == Polarity.POSITIVE }
            .mapNotNull(SemanticClause::relationToPerson)
            .filter { it in peopleIds }
            .toSet()
    }
}
