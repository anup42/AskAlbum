package com.samsung.agenticgallery

enum class QueryIntent {
    FIND_MEDIA,
    ANSWER_FACT,
    LIST,
    COUNT,
    SUM,
    MIN_MAX,
    COMPARE,
    TIMELINE,
    EVENT_SUMMARY,
    DOCUMENT_QA,
}

enum class MediaScope { ALL, IMAGES, VIDEOS, DOCUMENTS }
enum class Grouping { NONE, EVENT, PERSON, PLACE, DAY, MONTH, YEAR }
enum class AggregationOperation { COUNT, SUM, MIN, MAX, MIN_MAX }
enum class SortSpec { RELEVANCE, CAPTURE_TIME_ASC, CAPTURE_TIME_DESC, QUALITY, AMOUNT_ASC, AMOUNT_DESC }
enum class VerificationPolicy { NEVER, AUTO, REQUIRED }
enum class AnswerMode { RESULTS_ONLY, SUMMARY_ONLY, RESULTS_AND_SUMMARY }
enum class Polarity { POSITIVE, NEGATIVE }
enum class ConstraintStrength { HARD, SOFT }
enum class SemanticSubject { WHOLE_MEDIA, PERSON, EVENT, DOCUMENT }
enum class ResultExactness { EXACT, COMPLETE_MODEL_SCAN, ESTIMATED_FROM_RETRIEVAL, PARTIAL_INDEX }
enum class RetrievalChannel { LEXICAL, SEMANTIC, CAPTION, EVENT, OCR, PEOPLE, VISUAL_VERIFICATION }
enum class ChannelStatus { SUCCESS, UNAVAILABLE, FAILED, PARTIAL, NOT_REQUIRED }
enum class MediaSource { DEMO_ASSET, MEDIA_STORE, PHOTO_PICKER, SAF_DOCUMENT }
enum class MediaKind { IMAGE, VIDEO, PDF }
enum class IndexState { PENDING, INDEXING, READY, FAILED_RETRYABLE, FAILED_EXHAUSTED, FAILED_PERMANENT }
enum class MediaAccessState { ACCESSIBLE, INACCESSIBLE }
enum class IndexStage { DISCOVERY, METADATA, THUMBNAIL, VIDEO_KEYFRAMES, EMBEDDING, OCR, FACES, EVENTS, ENRICHMENT }
enum class StageStatus { PENDING, RUNNING, COMPLETE, SKIPPED, FAILED_RETRYABLE, FAILED_EXHAUSTED, FAILED_PERMANENT }
enum class OcrEntityType { AMOUNT, RECEIPT_TOTAL, DATE, PHONE, EMAIL, URL, ORDER_ID, FLIGHT_NUMBER, FLIGHT_TIME, MERCHANT, PASSWORD }

sealed interface FilterExpression {
    data object True : FilterExpression
    data class And(val clauses: List<FilterExpression>) : FilterExpression
    data class TimeRange(val startEpochMs: Long?, val endEpochMs: Long?) : FilterExpression
    data class MediaKindIs(val kind: MediaKind) : FilterExpression
    data class AlbumIs(val album: String) : FilterExpression
}

data class SemanticClause(
    val text: String,
    val canonicalText: String? = null,
    val polarity: Polarity = Polarity.POSITIVE,
    val hardness: ConstraintStrength = ConstraintStrength.SOFT,
    val subject: SemanticSubject = SemanticSubject.WHOLE_MEDIA,
    val relationToPerson: String? = null,
)

data class PersonClause(
    val personId: String,
    val mustBePresent: Boolean = true,
    val hardness: ConstraintStrength = ConstraintStrength.HARD,
    val alternativeGroup: String? = null,
)

data class OcrClause(
    val query: String? = null,
    val merchant: String? = null,
    val requestedField: String? = null,
)

data class AggregationSpec(
    val operation: AggregationOperation,
    val field: String? = null,
)

data class GalleryQueryPlan(
    val version: Int = 1,
    val originalQuery: String,
    val intent: QueryIntent,
    val mediaScope: MediaScope = MediaScope.ALL,
    val filter: FilterExpression = FilterExpression.True,
    val semanticClauses: List<SemanticClause> = emptyList(),
    val peopleClauses: List<PersonClause> = emptyList(),
    val ocrClause: OcrClause? = null,
    val grouping: Grouping = Grouping.NONE,
    val aggregation: AggregationSpec? = null,
    val sort: SortSpec = SortSpec.RELEVANCE,
    val verification: VerificationPolicy = VerificationPolicy.AUTO,
    val answerMode: AnswerMode = AnswerMode.RESULTS_AND_SUMMARY,
    // Compatibility fields used by the deterministic fixture repository until
    // hybrid channel executors replace its current term scorer.
    val terms: List<String> = emptyList(),
    val place: String? = null,
    val baseResultIds: Set<String>? = null,
    val limit: Int = 100,
)

/** App-created refinement contract. The model never supplies [baseResultSetId] or media IDs. */
data class PlanPatch(
    val version: Int = 1,
    val baseResultSetId: String,
    val changedFields: Set<String>,
    val replacementPlan: GalleryQueryPlan,
    val operations: List<PlanPatchOperation> = emptyList(),
)

enum class PlanPatchOperationType { ADD, REPLACE, REMOVE }

enum class PlanPatchField {
    INTENT,
    TIME,
    MEDIA_KIND,
    FILTER,
    PEOPLE,
    PLACE,
    SEMANTIC_CLAUSES,
    OCR,
    SORT,
    GROUPING,
    AGGREGATION,
    VERIFICATION,
    ANSWER_MODE,
    LIMIT,
    SCOPE,
}

data class PlanPatchOperation(
    val type: PlanPatchOperationType,
    val field: PlanPatchField,
)

data class ConversationSearchState(
    val sessionId: String,
    val activeResultSetId: String? = null,
    val activeResultIds: Set<String> = emptySet(),
    val lastQuery: String? = null,
    val referencedPeople: Set<String> = emptySet(),
    val referencedEvents: Set<Long> = emptySet(),
    val currentTimeScope: FilterExpression.TimeRange? = null,
    val currentPlaceScope: Set<String> = emptySet(),
    val grouping: Grouping = Grouping.NONE,
    val lastEvidenceIds: List<String> = emptyList(),
)

data class GroundedClaim(
    val text: String,
    val evidenceIds: List<String>,
    val confidence: Float,
)

data class GalleryItem(
    val id: String,
    val filename: String,
    val title: String,
    val creator: String?,
    val location: String,
    val album: String = "",
    val latitude: Double?,
    val longitude: Double?,
    val tags: List<String>,
    val description: String,
    val license: String,
    val sourceUrl: String,
    val assetPath: String?,
    val contentUri: String? = null,
    val previewPath: String? = null,
    val source: MediaSource = MediaSource.DEMO_ASSET,
    val kind: MediaKind = MediaKind.IMAGE,
    val mimeType: String = "image/jpeg",
    val capturedAt: Long? = null,
    val modifiedAt: Long? = null,
    val durationMs: Long? = null,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0,
    val ocrText: String = "",
    val faceCount: Int = 0,
    val indexState: IndexState = IndexState.READY,
    val indexError: String? = null,
    val accessState: MediaAccessState = MediaAccessState.ACCESSIBLE,
    val lastSeenAt: Long? = null,
    val perceptualHash: Long? = null,
    val exactContentDigest: String? = null,
    val blurScore: Float? = null,
    val exposureScore: Float? = null,
    val qualityScore: Float? = null,
)

data class EvidenceRecord(
    val id: String,
    val mediaId: String,
    val sourceField: String,
    val text: String,
    val confidence: Float,
    val producerVersion: String = "demo-sidecar-v1",
    val region: List<Float>? = null,
    val timestampMs: Long? = null,
    val pageIndex: Int? = null,
)

data class SearchHit(
    val item: GalleryItem,
    val score: Double,
    val evidence: List<EvidenceRecord>,
    val duplicateIds: List<String> = emptyList(),
)

data class RetrievalChannelReport<T>(
    val channel: RetrievalChannel,
    val status: ChannelStatus,
    val eligibleCount: Int,
    val indexedCount: Int,
    val searchedCount: Int,
    val hits: List<T>,
    val modelVersion: String? = null,
    val errorCode: String? = null,
)

data class VisualFeatures(
    val perceptualHash: Long,
    val blurScore: Float,
    val exposureScore: Float,
    val qualityScore: Float,
)

data class SearchAnswer(
    val headline: String,
    val detail: String,
    val evidenceIds: List<String>,
    val exactness: ResultExactness,
    val indexedEligibleCount: Int,
    val totalEligibleCount: Int,
    val claims: List<GroundedClaim> = emptyList(),
    val warnings: List<String> = emptyList(),
    val requiresAuthentication: Boolean = false,
    val channelReports: List<RetrievalChannelReport<SearchHit>> = emptyList(),
)

data class SearchOutcome(
    val plan: GalleryQueryPlan,
    val hits: List<SearchHit>,
    val answer: SearchAnswer,
    val elapsedMs: Long,
    val resultSetId: String? = null,
    val baseResultSetId: String? = null,
    val planPatch: PlanPatch? = null,
    val channelReports: List<RetrievalChannelReport<SearchHit>> = emptyList(),
)

sealed interface QueryProgress {
    data object Understanding : QueryProgress
    data class PlanReady(val plan: GalleryQueryPlan) : QueryProgress
    data class InitialResults(val plan: GalleryQueryPlan, val hits: List<SearchHit>) : QueryProgress
    data class Verifying(val candidateCount: Int) : QueryProgress
    data object ComposingAnswer : QueryProgress
    data class Completed(val outcome: SearchOutcome) : QueryProgress
}

data class IndexSummary(
    val discovered: Int = 0,
    val metadataReady: Int = 0,
    val semanticFactsReady: Int = 0,
    val ocrReady: Int = 0,
    val visualLabelsReady: Int = 0,
    val siglipVectorsReady: Int = 0,
    val videoKeyframesReady: Int = 0,
    val facesScanned: Int = 0,
    val faceEligible: Int = 0,
    val pending: Int = 0,
    val events: Int = 0,
    val failed: Int = 0,
    val storageBytes: Long = 0,
)

data class ScopedIndexCoverage(
    val mediaCount: Int,
    val indexStates: Map<IndexState, Int>,
    val stageStatuses: Map<IndexStage, Map<StageStatus, Int>>,
)

data class VideoKeyframeRecord(
    val id: String,
    val mediaId: String,
    val timestampMs: Long,
    val previewPath: String,
    val labels: List<String>,
    val ocrText: String,
    val perceptualHash: Long,
    val qualityScore: Float,
    val producerVersion: String,
    val embeddingVersion: String? = null,
)

data class PeopleIndexStatus(
    val enabled: Boolean = false,
    val consentVersion: Int = 0,
    val enabledAt: Long? = null,
    val faceInstanceCount: Int = 0,
    val personClusterCount: Int = 0,
    val reviewedClusterCount: Int = 0,
    val identityReadyFaceCount: Int = 0,
    val pendingMediaCount: Int = 0,
)

data class PersonClusterReviewItem(
    val id: String,
    val label: String?,
    val relationship: String?,
    val aliases: List<String>,
    val faceCount: Int,
    val sampleMediaId: String?,
    val reviewed: Boolean = false,
    val hidden: Boolean = false,
    val includeInPersonalSemanticMemory: Boolean = false,
    val representativeFaceId: String? = null,
    val representativeFace: PersonFaceReviewItem? = null,
    val supportingFaces: List<PersonFaceReviewItem> = emptyList(),
    val mediaCount: Int = 0,
)

data class PersonFaceReviewItem(
    val id: String,
    val mediaId: String,
    val item: GalleryItem,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val quality: Float,
    val userCorrected: Boolean,
)

data class PersonVerificationBinding(
    val faceId: String,
    val clusterId: String,
    val stableLabel: String,
    val identityTerms: Set<String>,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class FaceDetectionRecord(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val quality: Float,
)

data class ImportedMedia(
    val stableId: String,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val source: MediaSource,
    val capturedAt: Long?,
    val modifiedAt: Long?,
    val durationMs: Long?,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val album: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
)

data class OcrBlockRecord(
    val text: String,
    val normalizedText: String = text.lowercase().replace(Regex("\\s+"), " ").trim(),
    val language: String? = null,
    val pageIndex: Int = 0,
    val timestampMs: Long? = null,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class OcrEntityRecord(
    val type: OcrEntityType,
    val rawText: String,
    val normalizedValue: String,
    val label: String? = null,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val producerVersion: String = "document-facts-v2",
)

data class EventRecord(
    val id: Long,
    val startTime: Long,
    val endTime: Long,
    val title: String,
    val locationName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val eventType: String,
    val memberCount: Int,
    val confidence: Float,
    val searchText: String,
    val representativeMediaId: String?,
    val producerVersion: String,
    val userCorrected: Boolean,
)

data class EventInspection(
    val event: EventRecord,
    val media: List<GalleryItem>,
)

enum class EventCorrectionOperation { MERGE, SPLIT, RENAME, LOCATION }

data class EventCorrectionRecord(
    val id: Long = 0,
    val operation: EventCorrectionOperation,
    val mediaIds: Set<String>,
    val title: String? = null,
    val locationName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

data class CompiledEvent(
    val id: Long,
    val startTime: Long,
    val endTime: Long,
    val title: String,
    val locationName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val eventType: String,
    val members: List<GalleryItem>,
    val confidence: Float,
    val searchText: String,
    val representativeMediaId: String?,
    val producerVersion: String,
    val userCorrected: Boolean,
)

data class EventSearchHit(
    val event: EventRecord,
    val mediaIds: List<String>,
    val score: Double,
)

data class MediaRemovalResult(
    val requestedUris: Int,
    val matchedItems: Int,
    val deletedItems: Int,
    val tombstonesWritten: Int,
    val previewFilesDeleted: Int,
)

data class MediaIndexStageRecord(
    val mediaId: String,
    val stage: IndexStage,
    val status: StageStatus,
    val producerVersion: String,
    val attemptCount: Int,
    val updatedAt: Long,
    val error: String?,
)

data class IndexedPersonMetadata(
    val clusterId: String,
    val label: String?,
    val relationship: String?,
    val aliases: List<String>,
    val reviewed: Boolean,
    val hidden: Boolean,
    val faceCount: Int,
)

data class IndexedMediaMetadata(
    val stages: List<MediaIndexStageRecord>,
    val ocrBlocks: List<OcrBlockRecord>,
    val ocrEntities: List<OcrEntityRecord>,
    val people: List<IndexedPersonMetadata>,
    val event: EventRecord?,
    val videoKeyframes: List<VideoKeyframeRecord>,
    val semanticFacts: List<SemanticFactRecord>,
    val sensitiveContentLocked: Boolean,
    val protectedValueCount: Int,
    val semanticCaptions: List<SemanticCaptionRecord> = emptyList(),
    val personVisualFacts: List<PersonVisualFactRecord> = emptyList(),
)

data class MediaScanSnapshot(
    val items: List<ImportedMedia>,
    val fullyCoveredKinds: Set<MediaKind>,
)

data class MediaReconciliationPlan(
    val seenUris: Set<String>,
    val inaccessibleUris: Set<String>,
    val deletedUris: Set<String>,
)
