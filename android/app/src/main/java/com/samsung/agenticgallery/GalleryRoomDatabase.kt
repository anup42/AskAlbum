package com.samsung.agenticgallery

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(
    tableName = "media_item",
    indices = [
        Index(name = "media_item_state_idx", value = ["index_state"]),
        Index(name = "media_item_capture_idx", value = ["captured_at"]),
        Index(name = "media_item_exact_digest_idx", value = ["exact_content_digest"]),
    ],
)
data class MediaItemEntity(
    @PrimaryKey val id: String,
    val filename: String,
    val title: String,
    val creator: String?,
    @ColumnInfo(defaultValue = "''") val location: String,
    @ColumnInfo(defaultValue = "''") val album: String,
    val latitude: Double?,
    val longitude: Double?,
    @ColumnInfo(defaultValue = "''") val tags: String,
    @ColumnInfo(defaultValue = "''") val description: String,
    @ColumnInfo(defaultValue = "''") val license: String,
    @ColumnInfo(name = "source_url", defaultValue = "''") val sourceUrl: String,
    @ColumnInfo(name = "asset_path") val assetPath: String?,
    @ColumnInfo(name = "content_uri") val contentUri: String?,
    @ColumnInfo(name = "preview_path") val previewPath: String?,
    @ColumnInfo(name = "source_kind", defaultValue = "'DEMO_ASSET'") val sourceKind: String,
    @ColumnInfo(name = "media_kind", defaultValue = "'IMAGE'") val mediaKind: String,
    @ColumnInfo(name = "mime_type", defaultValue = "'image/jpeg'") val mimeType: String,
    @ColumnInfo(name = "captured_at") val capturedAt: Long?,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(defaultValue = "0") val width: Int,
    @ColumnInfo(defaultValue = "0") val height: Int,
    @ColumnInfo(name = "size_bytes", defaultValue = "0") val sizeBytes: Long,
    @ColumnInfo(name = "ocr_text", defaultValue = "''") val ocrText: String,
    @ColumnInfo(name = "face_count", defaultValue = "0") val faceCount: Int,
    @ColumnInfo(name = "index_state", defaultValue = "'READY'") val indexState: String,
    @ColumnInfo(name = "index_error") val indexError: String?,
    @ColumnInfo(name = "indexed_at") val indexedAt: Long?,
    @ColumnInfo(name = "index_version") val indexVersion: String,
    @ColumnInfo(name = "access_state", defaultValue = "'ACCESSIBLE'") val accessState: String,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long?,
    @ColumnInfo(name = "perceptual_hash") val perceptualHash: String?,
    @ColumnInfo(name = "exact_content_digest") val exactContentDigest: String?,
    @ColumnInfo(name = "blur_score") val blurScore: Float?,
    @ColumnInfo(name = "exposure_score") val exposureScore: Float?,
    @ColumnInfo(name = "quality_score") val qualityScore: Float?,
)

@Fts4
@Entity(tableName = "media_fts")
data class MediaFtsEntity(
    @ColumnInfo(name = "media_id") val mediaId: String,
    val title: String,
    val location: String,
    val tags: String,
    val description: String,
    @ColumnInfo(name = "ocr_text") val ocrText: String,
)

@Entity(tableName = "media_tombstone", indices = [Index(name = "media_tombstone_uri_idx", value = ["content_uri"])])
data class MediaTombstoneEntity(
    @PrimaryKey @ColumnInfo(name = "stable_id") val stableId: String,
    @ColumnInfo(name = "content_uri") val contentUri: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
    val reason: String,
)

@Entity(
    tableName = "ocr_block",
    foreignKeys = [ForeignKey(
        entity = MediaItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["media_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(name = "ocr_block_media_idx", value = ["media_id"])],
)
data class OcrBlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "media_id") val mediaId: String,
    val text: String,
    @ColumnInfo(name = "normalized_text", defaultValue = "''") val normalizedText: String,
    val language: String?,
    @ColumnInfo(name = "page_index", defaultValue = "0") val pageIndex: Int,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long?,
    val confidence: Float,
    @ColumnInfo(name = "left_pos") val left: Float,
    @ColumnInfo(name = "top_pos") val top: Float,
    @ColumnInfo(name = "right_pos") val right: Float,
    @ColumnInfo(name = "bottom_pos") val bottom: Float,
)

@Entity(
    tableName = "ocr_entity",
    foreignKeys = [ForeignKey(
        entity = MediaItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["media_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(name = "ocr_entity_media_idx", value = ["media_id"]),
        Index(name = "ocr_entity_type_value_idx", value = ["entity_type", "normalized_value"]),
    ],
)
data class OcrEntityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "raw_text") val rawText: String,
    @ColumnInfo(name = "normalized_value") val normalizedValue: String,
    val label: String?,
    val confidence: Float,
    @ColumnInfo(name = "left_pos") val left: Float,
    @ColumnInfo(name = "top_pos") val top: Float,
    @ColumnInfo(name = "right_pos") val right: Float,
    @ColumnInfo(name = "bottom_pos") val bottom: Float,
    @ColumnInfo(name = "producer_version") val producerVersion: String,
)

@Entity(
    tableName = "video_keyframe",
    foreignKeys = [ForeignKey(
        entity = MediaItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["media_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(name = "video_keyframe_media_time_idx", value = ["media_id", "timestamp_ms"], unique = true),
        Index(name = "video_keyframe_embedding_idx", value = ["embedding_version"]),
    ],
)
data class VideoKeyframeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    @ColumnInfo(name = "preview_path") val previewPath: String,
    @ColumnInfo(defaultValue = "''") val labels: String,
    @ColumnInfo(name = "ocr_text", defaultValue = "''") val ocrText: String,
    @ColumnInfo(name = "perceptual_hash") val perceptualHash: String,
    @ColumnInfo(name = "quality_score") val qualityScore: Float,
    @ColumnInfo(name = "producer_version") val producerVersion: String,
    @ColumnInfo(name = "embedding_version") val embeddingVersion: String?,
)

@Entity(
    tableName = "gallery_event",
    indices = [
        Index(name = "gallery_event_start_idx", value = ["start_time"]),
        Index(name = "gallery_event_end_idx", value = ["end_time"]),
    ],
)
data class GalleryEventEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "start_time") val startTime: Long,
    @ColumnInfo(name = "end_time") val endTime: Long,
    val title: String,
    @ColumnInfo(name = "location_name") val locationName: String?,
    val latitude: Double?,
    val longitude: Double?,
    @ColumnInfo(name = "event_type", defaultValue = "'MEMORY'") val eventType: String,
    @ColumnInfo(name = "member_count") val memberCount: Int,
    @ColumnInfo(defaultValue = "0.5") val confidence: Float,
    @ColumnInfo(name = "search_text", defaultValue = "''") val searchText: String,
    @ColumnInfo(name = "representative_media_id") val representativeMediaId: String?,
    @ColumnInfo(name = "producer_version", defaultValue = "'legacy-day-v1'") val producerVersion: String,
    @ColumnInfo(name = "user_corrected", defaultValue = "0") val userCorrected: Boolean,
)

@Entity(
    tableName = "event_media",
    primaryKeys = ["event_id", "media_id"],
    foreignKeys = [
        ForeignKey(entity = GalleryEventEntity::class, parentColumns = ["id"], childColumns = ["event_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["media_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["media_id"])],
)
data class EventMediaEntity(
    @ColumnInfo(name = "event_id") val eventId: Long,
    @ColumnInfo(name = "media_id") val mediaId: String,
)

@Entity(tableName = "event_correction", indices = [Index(name = "event_correction_created_idx", value = ["created_at"])])
data class EventCorrectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operation: String,
    @ColumnInfo(name = "media_ids") val mediaIds: String,
    val title: String?,
    @ColumnInfo(name = "location_name") val locationName: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "people_settings")
data class PeopleSettingsEntity(
    @PrimaryKey @ColumnInfo(name = "singleton_id") val singletonId: Int = 1,
    @ColumnInfo(defaultValue = "0") val enabled: Boolean = false,
    @ColumnInfo(name = "consent_version", defaultValue = "0") val consentVersion: Int = 0,
    @ColumnInfo(name = "enabled_at") val enabledAt: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "person_cluster")
data class PersonClusterEntity(
    @PrimaryKey val id: String,
    val label: String?,
    val relationship: String?,
    @ColumnInfo(defaultValue = "''") val aliases: String = "",
    @ColumnInfo(defaultValue = "0") val reviewed: Boolean = false,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    @ColumnInfo(name = "include_in_personal_memory", defaultValue = "0") val includeInPersonalMemory: Boolean = false,
    @ColumnInfo(name = "representative_face_id") val representativeFaceId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "face_instance",
    foreignKeys = [
        ForeignKey(entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["media_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PersonClusterEntity::class, parentColumns = ["id"], childColumns = ["cluster_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index(name = "face_instance_media_idx", value = ["media_id"]),
        Index(name = "face_instance_cluster_idx", value = ["cluster_id"]),
    ],
)
data class FaceInstanceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "left_pos") val left: Float,
    @ColumnInfo(name = "top_pos") val top: Float,
    @ColumnInfo(name = "right_pos") val right: Float,
    @ColumnInfo(name = "bottom_pos") val bottom: Float,
    val quality: Float,
    @ColumnInfo(name = "embedding_offset") val embeddingOffset: Long?,
    @ColumnInfo(name = "embedding_dimension", defaultValue = "0") val embeddingDimension: Int = 0,
    @ColumnInfo(name = "cluster_id") val clusterId: String?,
    @ColumnInfo(name = "user_corrected", defaultValue = "0") val userCorrected: Boolean = false,
    @ColumnInfo(name = "producer_version") val producerVersion: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "person_attribute_fact",
    foreignKeys = [
        ForeignKey(entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["media_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PersonClusterEntity::class, parentColumns = ["id"], childColumns = ["cluster_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(name = "person_attribute_media_idx", value = ["media_id"]),
        Index(name = "person_attribute_cluster_idx", value = ["cluster_id"]),
    ],
)
data class PersonAttributeFactEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "cluster_id") val clusterId: String,
    val predicate: String,
    val value: String,
    val confidence: Float,
    val region: String,
    @ColumnInfo(name = "model_version") val modelVersion: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "person_ref", defaultValue = "''") val personRef: String = "",
    @ColumnInfo(defaultValue = "'ACTION'") val relation: String = "ACTION",
    @ColumnInfo(defaultValue = "'OTHER_WORN_ITEM'") val category: String = "OTHER_WORN_ITEM",
    @ColumnInfo(name = "item_type") val itemType: String? = null,
    @ColumnInfo(defaultValue = "'{}'") val attributes: String = "{}",
    @ColumnInfo(name = "body_region", defaultValue = "'UNKNOWN'") val bodyRegion: String = "UNKNOWN",
    @ColumnInfo(name = "face_region") val faceRegion: String? = null,
    @ColumnInfo(name = "association_status", defaultValue = "'CONFIDENT'") val associationStatus: String = "CONFIDENT",
    @ColumnInfo(defaultValue = "'VERIFIED_TRUE'") val verdict: String = "VERIFIED_TRUE",
    @ColumnInfo(name = "target_cluster_id") val targetClusterId: String? = null,
    @ColumnInfo(name = "prompt_version", defaultValue = "'legacy-person-attribute-v1'") val promptVersion: String = "legacy-person-attribute-v1",
)

@Entity(tableName = "query_turn")
data class QueryTurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    @ColumnInfo(name = "plan_summary") val planSummary: String,
    @ColumnInfo(name = "result_count") val resultCount: Int,
    @ColumnInfo(name = "elapsed_ms") val elapsedMs: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "result_set_id") val resultSetId: String?,
    @ColumnInfo(name = "base_result_set_id") val baseResultSetId: String?,
    @ColumnInfo(name = "plan_patch_summary") val planPatchSummary: String?,
)

@Entity(tableName = "query_session")
data class QuerySessionEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "active_result_set_id") val activeResultSetId: String?,
    @ColumnInfo(name = "last_query") val lastQuery: String?,
    @ColumnInfo(name = "referenced_people", defaultValue = "'[]'") val referencedPeople: String,
    @ColumnInfo(name = "referenced_events", defaultValue = "'[]'") val referencedEvents: String,
    @ColumnInfo(name = "time_start") val timeStart: Long?,
    @ColumnInfo(name = "time_end") val timeEnd: Long?,
    @ColumnInfo(name = "place_scope", defaultValue = "'[]'") val placeScope: String,
    @ColumnInfo(defaultValue = "'NONE'") val grouping: String,
    @ColumnInfo(name = "last_evidence_ids", defaultValue = "'[]'") val lastEvidenceIds: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "result_set",
    indices = [Index(name = "result_set_session_created_idx", value = ["session_id", "created_at"])],
)
data class ResultSetEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "parent_result_set_id") val parentResultSetId: String?,
    val query: String,
    val intent: String,
    val exactness: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "result_set_media",
    primaryKeys = ["result_set_id", "media_id"],
    foreignKeys = [
        ForeignKey(entity = ResultSetEntity::class, parentColumns = ["id"], childColumns = ["result_set_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["media_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(name = "result_set_media_media_idx", value = ["media_id"])],
)
data class ResultSetMediaEntity(
    @ColumnInfo(name = "result_set_id") val resultSetId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    val rank: Int,
    val score: Double,
)

@Entity(
    tableName = "media_index_stage",
    primaryKeys = ["media_id", "stage"],
    foreignKeys = [ForeignKey(entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["media_id"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(name = "media_index_stage_status_idx", value = ["status"]),
        Index(value = ["media_id"]),
        Index(name = "media_index_stage_queue_idx", value = ["stage", "status", "next_attempt_at"]),
    ],
)
data class MediaIndexStageEntity(
    @ColumnInfo(name = "media_id") val mediaId: String,
    val stage: String,
    val status: String,
    @ColumnInfo(name = "producer_version") val producerVersion: String,
    @ColumnInfo(name = "attempt_count", defaultValue = "0") val attemptCount: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val error: String?,
    @ColumnInfo(name = "lease_owner") val leaseOwner: String?,
    @ColumnInfo(name = "lease_expires_at") val leaseExpiresAt: Long?,
    @ColumnInfo(name = "next_attempt_at", defaultValue = "0") val nextAttemptAt: Long,
    @ColumnInfo(name = "last_progress_at") val lastProgressAt: Long?,
)

@Entity(
    tableName = "visual_group",
    indices = [Index(name = "visual_group_kind_idx", value = ["kind"])],
)
data class VisualGroupEntity(
    @PrimaryKey val id: String,
    val kind: String,
    @ColumnInfo(name = "canonical_media_id") val canonicalMediaId: String,
    @ColumnInfo(name = "producer_version") val producerVersion: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "visual_group_member",
    primaryKeys = ["group_id", "media_id"],
    foreignKeys = [
        ForeignKey(entity = VisualGroupEntity::class, parentColumns = ["id"], childColumns = ["group_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["media_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(name = "visual_group_member_media_idx", value = ["media_id"])],
)
data class VisualGroupMemberEntity(
    @ColumnInfo(name = "group_id") val groupId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    val role: String,
    @ColumnInfo(name = "diversity_score") val diversityScore: Float,
)

@Entity(
    tableName = "event_representative",
    primaryKeys = ["event_id", "media_id"],
    foreignKeys = [
        ForeignKey(entity = GalleryEventEntity::class, parentColumns = ["id"], childColumns = ["event_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["media_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(name = "event_representative_media_idx", value = ["media_id"])],
)
data class EventRepresentativeEntity(
    @ColumnInfo(name = "event_id") val eventId: Long,
    @ColumnInfo(name = "media_id") val mediaId: String,
    val rank: Int,
    val reason: String,
)

@Entity(
    tableName = "semantic_fact",
    indices = [
        Index(name = "semantic_fact_subject_idx", value = ["scope", "subject_id"]),
        Index(name = "semantic_fact_evidence_idx", value = ["evidence_media_id"]),
        Index(name = "semantic_fact_predicate_idx", value = ["predicate"]),
    ],
    foreignKeys = [
        ForeignKey(entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["evidence_media_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class SemanticFactEntity(
    @PrimaryKey val id: String,
    val scope: String,
    @ColumnInfo(name = "subject_id") val subjectId: String,
    val predicate: String,
    val value: String,
    val confidence: Float,
    @ColumnInfo(name = "evidence_media_id") val evidenceMediaId: String,
    val region: String?,
    val applicability: String,
    @ColumnInfo(name = "model_version") val modelVersion: String,
    @ColumnInfo(name = "prompt_version") val promptVersion: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "semantic_caption",
    indices = [
        Index(name = "semantic_caption_subject_idx", value = ["scope", "subject_id"]),
        Index(name = "semantic_caption_evidence_idx", value = ["evidence_media_id"]),
    ],
    foreignKeys = [
        ForeignKey(entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["evidence_media_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class SemanticCaptionEntity(
    @PrimaryKey val id: String,
    val scope: String,
    @ColumnInfo(name = "subject_id") val subjectId: String,
    val text: String,
    val confidence: Float,
    @ColumnInfo(name = "evidence_media_id") val evidenceMediaId: String,
    @ColumnInfo(name = "representative_media_id") val representativeMediaId: String?,
    @ColumnInfo(name = "source_type", defaultValue = "'GEMMA_DIRECT'") val sourceType: String,
    val applicability: String,
    @ColumnInfo(name = "body_region_version", defaultValue = "'person-body-regions-v1'") val bodyRegionVersion: String,
    @ColumnInfo(name = "model_version") val modelVersion: String,
    @ColumnInfo(name = "prompt_version") val promptVersion: String,
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "chunk_policy_version") val chunkPolicyVersion: String?,
    @ColumnInfo(name = "chunked_at") val chunkedAt: Long?,
)

@Entity(
    tableName = "semantic_caption_person_ref",
    primaryKeys = ["caption_id", "person_ref"],
    indices = [Index(name = "semantic_caption_person_cluster_idx", value = ["cluster_id"])],
    foreignKeys = [
        ForeignKey(entity = SemanticCaptionEntity::class, parentColumns = ["id"], childColumns = ["caption_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PersonClusterEntity::class, parentColumns = ["id"], childColumns = ["cluster_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class SemanticCaptionPersonRefEntity(
    @ColumnInfo(name = "caption_id") val captionId: String,
    @ColumnInfo(name = "person_ref") val personRef: String,
    @ColumnInfo(name = "cluster_id") val clusterId: String,
    @ColumnInfo(name = "face_region") val faceRegion: String,
    @ColumnInfo(name = "body_region") val bodyRegion: String?,
    @ColumnInfo(name = "association_status") val associationStatus: String,
)

@Entity(
    tableName = "semantic_caption_chunk",
    indices = [
        Index(name = "semantic_caption_chunk_caption_idx", value = ["caption_id"]),
        Index(name = "semantic_caption_chunk_media_idx", value = ["media_id"]),
        Index(name = "semantic_caption_chunk_evidence_idx", value = ["evidence_media_id"]),
        Index(name = "semantic_caption_chunk_cluster_idx", value = ["cluster_id"]),
        Index(name = "semantic_caption_chunk_queue_idx", value = ["embedding_state", "next_attempt_at"]),
    ],
    foreignKeys = [
        ForeignKey(entity = SemanticCaptionEntity::class, parentColumns = ["id"], childColumns = ["caption_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["media_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["evidence_media_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PersonClusterEntity::class, parentColumns = ["id"], childColumns = ["cluster_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class SemanticCaptionChunkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "caption_id") val captionId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    val scope: String,
    @ColumnInfo(name = "scope_id") val scopeId: String,
    @ColumnInfo(name = "evidence_media_id") val evidenceMediaId: String,
    @ColumnInfo(name = "cluster_id") val clusterId: String?,
    @ColumnInfo(name = "chunk_type") val chunkType: String,
    @ColumnInfo(name = "exact_text") val exactText: String,
    val confidence: Float,
    val applicability: String,
    @ColumnInfo(name = "caption_model_version") val captionModelVersion: String,
    @ColumnInfo(name = "caption_prompt_version") val captionPromptVersion: String,
    @ColumnInfo(name = "chunk_policy_version") val chunkPolicyVersion: String,
    @ColumnInfo(name = "embedding_model_version") val embeddingModelVersion: String?,
    @ColumnInfo(name = "embedding_state", defaultValue = "'PENDING'") val embeddingState: String,
    @ColumnInfo(name = "attempt_count", defaultValue = "0") val attemptCount: Int,
    val error: String?,
    @ColumnInfo(name = "lease_owner") val leaseOwner: String?,
    @ColumnInfo(name = "lease_expires_at") val leaseExpiresAt: Long?,
    @ColumnInfo(name = "next_attempt_at", defaultValue = "0") val nextAttemptAt: Long,
    @ColumnInfo(name = "last_progress_at") val lastProgressAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Fts4
@Entity(tableName = "semantic_caption_chunk_fts")
data class SemanticCaptionChunkFtsEntity(
    @ColumnInfo(name = "chunk_id") val chunkId: String,
    @ColumnInfo(name = "exact_text") val exactText: String,
)

@Entity(
    tableName = "semantic_enrichment_job",
    indices = [
        Index(name = "semantic_enrichment_status_idx", value = ["status", "updated_at"]),
        Index(name = "semantic_enrichment_media_idx", value = ["representative_media_id"]),
        Index(name = "semantic_enrichment_queue_idx", value = ["status", "next_attempt_at"]),
        Index(
            name = "semantic_enrichment_unique_idx",
            value = ["scope", "subject_id", "representative_media_id", "reason"],
            unique = true,
        ),
    ],
    foreignKeys = [
        ForeignKey(entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["representative_media_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class SemanticEnrichmentJobEntity(
    @PrimaryKey val id: String,
    val scope: String,
    @ColumnInfo(name = "subject_id") val subjectId: String,
    @ColumnInfo(name = "representative_media_id") val representativeMediaId: String,
    val reason: String,
    val status: String,
    @ColumnInfo(name = "attempt_count", defaultValue = "0") val attemptCount: Int,
    @ColumnInfo(name = "user_requested", defaultValue = "0") val userRequested: Boolean,
    @ColumnInfo(name = "model_version") val modelVersion: String?,
    val error: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "lease_owner") val leaseOwner: String?,
    @ColumnInfo(name = "lease_expires_at") val leaseExpiresAt: Long?,
    @ColumnInfo(name = "next_attempt_at", defaultValue = "0") val nextAttemptAt: Long,
    @ColumnInfo(name = "last_progress_at") val lastProgressAt: Long?,
)

@Database(
    entities = [
        MediaItemEntity::class,
        MediaFtsEntity::class,
        MediaTombstoneEntity::class,
        OcrBlockEntity::class,
        OcrEntityEntity::class,
        VideoKeyframeEntity::class,
        GalleryEventEntity::class,
        EventMediaEntity::class,
        EventCorrectionEntity::class,
        PeopleSettingsEntity::class,
        PersonClusterEntity::class,
        FaceInstanceEntity::class,
        PersonAttributeFactEntity::class,
        QueryTurnEntity::class,
        QuerySessionEntity::class,
        ResultSetEntity::class,
        ResultSetMediaEntity::class,
        MediaIndexStageEntity::class,
        VisualGroupEntity::class,
        VisualGroupMemberEntity::class,
        EventRepresentativeEntity::class,
        SemanticFactEntity::class,
        SemanticCaptionEntity::class,
        SemanticCaptionPersonRefEntity::class,
        SemanticCaptionChunkEntity::class,
        SemanticCaptionChunkFtsEntity::class,
        SemanticEnrichmentJobEntity::class,
    ],
    version = 18,
    exportSchema = true,
)
abstract class GalleryRoomDatabase : RoomDatabase() {
    companion object {
        const val NAME = "gallery-memory.db"

        fun open(context: Context, name: String = NAME): GalleryRoomDatabase = Room.databaseBuilder(
            context.applicationContext,
            GalleryRoomDatabase::class.java,
            name,
        ).addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
        ).build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "content_uri TEXT", "preview_path TEXT", "source_kind TEXT NOT NULL DEFAULT 'DEMO_ASSET'",
                    "media_kind TEXT NOT NULL DEFAULT 'IMAGE'", "mime_type TEXT NOT NULL DEFAULT 'image/jpeg'",
                    "captured_at INTEGER", "modified_at INTEGER", "duration_ms INTEGER", "width INTEGER NOT NULL DEFAULT 0",
                    "height INTEGER NOT NULL DEFAULT 0", "size_bytes INTEGER NOT NULL DEFAULT 0", "ocr_text TEXT NOT NULL DEFAULT ''",
                    "face_count INTEGER NOT NULL DEFAULT 0", "index_state TEXT NOT NULL DEFAULT 'READY'", "index_error TEXT",
                    "indexed_at INTEGER",
                ).forEach { db.execSQL("ALTER TABLE media_item ADD COLUMN $it") }
                db.execSQL("CREATE INDEX IF NOT EXISTS media_item_state_idx ON media_item(index_state)")
                db.execSQL("CREATE INDEX IF NOT EXISTS media_item_capture_idx ON media_item(captured_at)")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS media_fts USING fts4(media_id,title,location,tags,description,ocr_text)")
                db.execSQL("INSERT INTO media_fts(media_id,title,location,tags,description,ocr_text) SELECT id,title,location,tags,description,ocr_text FROM media_item")
                db.execSQL("CREATE TABLE IF NOT EXISTS ocr_block (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, media_id TEXT NOT NULL REFERENCES media_item(id) ON DELETE CASCADE, text TEXT NOT NULL, confidence REAL NOT NULL, left_pos REAL NOT NULL, top_pos REAL NOT NULL, right_pos REAL NOT NULL, bottom_pos REAL NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS ocr_block_media_idx ON ocr_block(media_id)")
                db.execSQL("CREATE TABLE IF NOT EXISTS gallery_event (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, day_start INTEGER NOT NULL, title TEXT NOT NULL, member_count INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_gallery_event_day_start ON gallery_event(day_start)")
                db.execSQL("CREATE TABLE IF NOT EXISTS event_media (event_id INTEGER NOT NULL REFERENCES gallery_event(id) ON DELETE CASCADE, media_id TEXT NOT NULL REFERENCES media_item(id) ON DELETE CASCADE, PRIMARY KEY(event_id, media_id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_event_media_media_id ON event_media(media_id)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = createTombstones(db)
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                rebuildLegacySchema(db)
                createStages(db)
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_item ADD COLUMN access_state TEXT NOT NULL DEFAULT 'ACCESSIBLE'")
                db.execSQL("ALTER TABLE media_item ADD COLUMN last_seen_at INTEGER")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_item ADD COLUMN perceptual_hash TEXT")
                db.execSQL("ALTER TABLE media_item ADD COLUMN blur_score REAL")
                db.execSQL("ALTER TABLE media_item ADD COLUMN exposure_score REAL")
                db.execSQL("ALTER TABLE media_item ADD COLUMN quality_score REAL")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ocr_block ADD COLUMN normalized_text TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE ocr_block ADD COLUMN language TEXT")
                db.execSQL("ALTER TABLE ocr_block ADD COLUMN page_index INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ocr_block ADD COLUMN timestamp_ms INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS ocr_entity (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, media_id TEXT NOT NULL, entity_type TEXT NOT NULL, raw_text TEXT NOT NULL, normalized_value TEXT NOT NULL, label TEXT, confidence REAL NOT NULL, left_pos REAL NOT NULL, top_pos REAL NOT NULL, right_pos REAL NOT NULL, bottom_pos REAL NOT NULL, producer_version TEXT NOT NULL, FOREIGN KEY(media_id) REFERENCES media_item(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS ocr_entity_media_idx ON ocr_entity(media_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS ocr_entity_type_value_idx ON ocr_entity(entity_type, normalized_value)")
                db.execSQL("UPDATE media_item SET index_state='PENDING', index_version='ocr-document-v2' WHERE source_kind!='DEMO_ASSET' AND access_state='ACCESSIBLE'")
                db.execSQL("UPDATE media_index_stage SET status='PENDING', producer_version='ocr-document-v2', error=NULL WHERE stage='OCR' AND media_id IN (SELECT id FROM media_item WHERE source_kind!='DEMO_ASSET' AND access_state='ACCESSIBLE')")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS people_settings (singleton_id INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 0, consent_version INTEGER NOT NULL DEFAULT 0, enabled_at INTEGER, updated_at INTEGER NOT NULL, PRIMARY KEY(singleton_id))")
                db.execSQL("INSERT OR IGNORE INTO people_settings(singleton_id,enabled,consent_version,enabled_at,updated_at) VALUES(1,0,0,NULL,${System.currentTimeMillis()})")
                db.execSQL("CREATE TABLE IF NOT EXISTS person_cluster (id TEXT NOT NULL, label TEXT, relationship TEXT, aliases TEXT NOT NULL DEFAULT '', reviewed INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS face_instance (id TEXT NOT NULL, media_id TEXT NOT NULL, left_pos REAL NOT NULL, top_pos REAL NOT NULL, right_pos REAL NOT NULL, bottom_pos REAL NOT NULL, quality REAL NOT NULL, embedding_offset INTEGER, embedding_dimension INTEGER NOT NULL DEFAULT 0, cluster_id TEXT, producer_version TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(media_id) REFERENCES media_item(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(cluster_id) REFERENCES person_cluster(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS face_instance_media_idx ON face_instance(media_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS face_instance_cluster_idx ON face_instance(cluster_id)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_item ADD COLUMN album TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE media_item SET album=location WHERE source_kind='DEMO_ASSET' AND album='' AND location<>''")
                db.execSQL("ALTER TABLE query_turn ADD COLUMN session_id TEXT")
                db.execSQL("ALTER TABLE query_turn ADD COLUMN result_set_id TEXT")
                db.execSQL("ALTER TABLE query_turn ADD COLUMN base_result_set_id TEXT")
                db.execSQL("ALTER TABLE query_turn ADD COLUMN plan_patch_summary TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS query_session (session_id TEXT NOT NULL, active_result_set_id TEXT, last_query TEXT, referenced_people TEXT NOT NULL DEFAULT '[]', referenced_events TEXT NOT NULL DEFAULT '[]', time_start INTEGER, time_end INTEGER, place_scope TEXT NOT NULL DEFAULT '[]', grouping TEXT NOT NULL DEFAULT 'NONE', last_evidence_ids TEXT NOT NULL DEFAULT '[]', updated_at INTEGER NOT NULL, PRIMARY KEY(session_id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS result_set (id TEXT NOT NULL, session_id TEXT NOT NULL, parent_result_set_id TEXT, query TEXT NOT NULL, intent TEXT NOT NULL, exactness TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS result_set_session_created_idx ON result_set(session_id, created_at)")
                db.execSQL("CREATE TABLE IF NOT EXISTS result_set_media (result_set_id TEXT NOT NULL, media_id TEXT NOT NULL, rank INTEGER NOT NULL, score REAL NOT NULL, PRIMARY KEY(result_set_id, media_id), FOREIGN KEY(result_set_id) REFERENCES result_set(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(media_id) REFERENCES media_item(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS result_set_media_media_idx ON result_set_media(media_id)")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA defer_foreign_keys=ON")
                db.execSQL("ALTER TABLE event_media RENAME TO event_media_v9")
                db.execSQL("ALTER TABLE gallery_event RENAME TO gallery_event_v9")
                db.execSQL("DROP INDEX IF EXISTS index_event_media_media_id")
                db.execSQL("CREATE TABLE gallery_event (id INTEGER NOT NULL, start_time INTEGER NOT NULL, end_time INTEGER NOT NULL, title TEXT NOT NULL, location_name TEXT, latitude REAL, longitude REAL, event_type TEXT NOT NULL DEFAULT 'MEMORY', member_count INTEGER NOT NULL, confidence REAL NOT NULL DEFAULT 0.5, search_text TEXT NOT NULL DEFAULT '', representative_media_id TEXT, producer_version TEXT NOT NULL DEFAULT 'legacy-day-v1', user_corrected INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(id))")
                db.execSQL("INSERT INTO gallery_event(id,start_time,end_time,title,event_type,member_count,confidence,search_text,producer_version,user_corrected) SELECT id,day_start,day_start,title,'MEMORY',member_count,0.5,title,'legacy-day-v1',0 FROM gallery_event_v9")
                db.execSQL("CREATE INDEX gallery_event_start_idx ON gallery_event(start_time)")
                db.execSQL("CREATE INDEX gallery_event_end_idx ON gallery_event(end_time)")
                db.execSQL("CREATE TABLE event_media (event_id INTEGER NOT NULL, media_id TEXT NOT NULL, PRIMARY KEY(event_id,media_id), FOREIGN KEY(event_id) REFERENCES gallery_event(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(media_id) REFERENCES media_item(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO event_media(event_id,media_id) SELECT event_id,media_id FROM event_media_v9")
                db.execSQL("CREATE INDEX index_event_media_media_id ON event_media(media_id)")
                db.execSQL("DROP TABLE event_media_v9")
                db.execSQL("DROP TABLE gallery_event_v9")
                db.execSQL("CREATE TABLE event_correction (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, operation TEXT NOT NULL, media_ids TEXT NOT NULL, title TEXT, location_name TEXT, created_at INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX event_correction_created_idx ON event_correction(created_at)")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS video_keyframe (id TEXT NOT NULL, media_id TEXT NOT NULL, timestamp_ms INTEGER NOT NULL, preview_path TEXT NOT NULL, labels TEXT NOT NULL DEFAULT '', ocr_text TEXT NOT NULL DEFAULT '', perceptual_hash TEXT NOT NULL, quality_score REAL NOT NULL, producer_version TEXT NOT NULL, embedding_version TEXT, PRIMARY KEY(id), FOREIGN KEY(media_id) REFERENCES media_item(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS video_keyframe_media_time_idx ON video_keyframe(media_id,timestamp_ms)")
                db.execSQL("CREATE INDEX IF NOT EXISTS video_keyframe_embedding_idx ON video_keyframe(embedding_version)")
                val now = System.currentTimeMillis()
                db.execSQL("INSERT OR IGNORE INTO media_index_stage(media_id,stage,status,producer_version,attempt_count,updated_at,error) SELECT id,'VIDEO_KEYFRAMES',CASE WHEN media_kind='VIDEO' AND source_kind!='DEMO_ASSET' THEN 'PENDING' ELSE 'SKIPPED' END,CASE WHEN media_kind='VIDEO' THEN 'video-keyframes-v1' ELSE 'not-video' END,0,$now,NULL FROM media_item")
                db.execSQL("UPDATE media_item SET index_state='PENDING',index_version='video-keyframes-v1' WHERE media_kind='VIDEO' AND source_kind!='DEMO_ASSET' AND access_state='ACCESSIBLE'")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE person_cluster ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE face_instance ADD COLUMN user_corrected INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS person_attribute_fact (" +
                        "id TEXT NOT NULL, media_id TEXT NOT NULL, cluster_id TEXT NOT NULL, predicate TEXT NOT NULL, " +
                        "value TEXT NOT NULL, confidence REAL NOT NULL, region TEXT NOT NULL, model_version TEXT NOT NULL, " +
                        "updated_at INTEGER NOT NULL, PRIMARY KEY(id), " +
                        "FOREIGN KEY(media_id) REFERENCES media_item(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(cluster_id) REFERENCES person_cluster(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS person_attribute_media_idx ON person_attribute_fact(media_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS person_attribute_cluster_idx ON person_attribute_fact(cluster_id)")
            }
        }

        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS visual_group (id TEXT NOT NULL, kind TEXT NOT NULL, canonical_media_id TEXT NOT NULL, producer_version TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS visual_group_kind_idx ON visual_group(kind)")
                db.execSQL("CREATE TABLE IF NOT EXISTS visual_group_member (group_id TEXT NOT NULL, media_id TEXT NOT NULL, role TEXT NOT NULL, diversity_score REAL NOT NULL, PRIMARY KEY(group_id,media_id), FOREIGN KEY(group_id) REFERENCES visual_group(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(media_id) REFERENCES media_item(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS visual_group_member_media_idx ON visual_group_member(media_id)")
                db.execSQL("CREATE TABLE IF NOT EXISTS event_representative (event_id INTEGER NOT NULL, media_id TEXT NOT NULL, rank INTEGER NOT NULL, reason TEXT NOT NULL, PRIMARY KEY(event_id,media_id), FOREIGN KEY(event_id) REFERENCES gallery_event(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(media_id) REFERENCES media_item(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS event_representative_media_idx ON event_representative(media_id)")
                db.execSQL("CREATE TABLE IF NOT EXISTS semantic_fact (id TEXT NOT NULL, scope TEXT NOT NULL, subject_id TEXT NOT NULL, predicate TEXT NOT NULL, value TEXT NOT NULL, confidence REAL NOT NULL, evidence_media_id TEXT NOT NULL, region TEXT, applicability TEXT NOT NULL, model_version TEXT NOT NULL, prompt_version TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(evidence_media_id) REFERENCES media_item(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS semantic_fact_subject_idx ON semantic_fact(scope,subject_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS semantic_fact_evidence_idx ON semantic_fact(evidence_media_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS semantic_fact_predicate_idx ON semantic_fact(predicate)")
                db.execSQL("CREATE TABLE IF NOT EXISTS semantic_enrichment_job (id TEXT NOT NULL, scope TEXT NOT NULL, subject_id TEXT NOT NULL, representative_media_id TEXT NOT NULL, reason TEXT NOT NULL, status TEXT NOT NULL, attempt_count INTEGER NOT NULL DEFAULT 0, user_requested INTEGER NOT NULL DEFAULT 0, model_version TEXT, error TEXT, updated_at INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(representative_media_id) REFERENCES media_item(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS semantic_enrichment_status_idx ON semantic_enrichment_job(status,updated_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS semantic_enrichment_media_idx ON semantic_enrichment_job(representative_media_id)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS semantic_enrichment_unique_idx ON semantic_enrichment_job(scope,subject_id,representative_media_id,reason)")
            }
        }

        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE person_cluster ADD COLUMN representative_face_id TEXT")
            }
        }

        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_index_stage ADD COLUMN lease_owner TEXT")
                db.execSQL("ALTER TABLE media_index_stage ADD COLUMN lease_expires_at INTEGER")
                db.execSQL("ALTER TABLE media_index_stage ADD COLUMN next_attempt_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media_index_stage ADD COLUMN last_progress_at INTEGER")
                db.execSQL("ALTER TABLE semantic_enrichment_job ADD COLUMN lease_owner TEXT")
                db.execSQL("ALTER TABLE semantic_enrichment_job ADD COLUMN lease_expires_at INTEGER")
                db.execSQL("ALTER TABLE semantic_enrichment_job ADD COLUMN next_attempt_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE semantic_enrichment_job ADD COLUMN last_progress_at INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS media_index_stage_queue_idx " +
                        "ON media_index_stage(stage,status,next_attempt_at)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS semantic_enrichment_queue_idx " +
                        "ON semantic_enrichment_job(status,next_attempt_at)",
                )
                db.execSQL(
                    "UPDATE media_index_stage SET status='PENDING',lease_owner=NULL,lease_expires_at=NULL," +
                        "error='upgrade_interrupted' WHERE status='RUNNING'",
                )
                db.execSQL(
                    "UPDATE semantic_enrichment_job SET status='PENDING'," +
                        "attempt_count=CASE WHEN attempt_count>0 THEN attempt_count-1 ELSE 0 END," +
                        "lease_owner=NULL,lease_expires_at=NULL,error='upgrade_interrupted' WHERE status='RUNNING'",
                )
                db.execSQL(
                    "UPDATE media_index_stage SET status='FAILED_EXHAUSTED'," +
                        "error='retry_exhausted:'||COALESCE(error,'unknown') " +
                        "WHERE status='FAILED_RETRYABLE' AND attempt_count>=3",
                )
                db.execSQL(
                    "UPDATE media_item SET index_state='FAILED_EXHAUSTED'," +
                        "index_error='retry_exhausted:'||COALESCE(index_error,'unknown') " +
                        "WHERE index_state='FAILED_RETRYABLE' AND id IN (" +
                        "SELECT media_id FROM media_index_stage WHERE stage='THUMBNAIL' AND status='FAILED_EXHAUSTED')",
                )
            }
        }

        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE person_attribute_fact ADD COLUMN person_ref TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE person_attribute_fact ADD COLUMN relation TEXT NOT NULL DEFAULT 'ACTION'")
                db.execSQL("ALTER TABLE person_attribute_fact ADD COLUMN category TEXT NOT NULL DEFAULT 'OTHER_WORN_ITEM'")
                db.execSQL("ALTER TABLE person_attribute_fact ADD COLUMN item_type TEXT")
                db.execSQL("ALTER TABLE person_attribute_fact ADD COLUMN attributes TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE person_attribute_fact ADD COLUMN body_region TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE person_attribute_fact ADD COLUMN face_region TEXT")
                db.execSQL("ALTER TABLE person_attribute_fact ADD COLUMN association_status TEXT NOT NULL DEFAULT 'CONFIDENT'")
                db.execSQL("ALTER TABLE person_attribute_fact ADD COLUMN verdict TEXT NOT NULL DEFAULT 'VERIFIED_TRUE'")
                db.execSQL("ALTER TABLE person_attribute_fact ADD COLUMN target_cluster_id TEXT")
                db.execSQL("ALTER TABLE person_attribute_fact ADD COLUMN prompt_version TEXT NOT NULL DEFAULT 'legacy-person-attribute-v1'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS semantic_caption (" +
                        "id TEXT NOT NULL, scope TEXT NOT NULL, subject_id TEXT NOT NULL, text TEXT NOT NULL, " +
                        "confidence REAL NOT NULL, evidence_media_id TEXT NOT NULL, applicability TEXT NOT NULL, " +
                        "model_version TEXT NOT NULL, prompt_version TEXT NOT NULL, updated_at INTEGER NOT NULL, " +
                        "PRIMARY KEY(id), FOREIGN KEY(evidence_media_id) REFERENCES media_item(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS semantic_caption_subject_idx ON semantic_caption(scope,subject_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS semantic_caption_evidence_idx ON semantic_caption(evidence_media_id)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS semantic_caption_person_ref (" +
                        "caption_id TEXT NOT NULL, person_ref TEXT NOT NULL, cluster_id TEXT NOT NULL, face_region TEXT NOT NULL, " +
                        "body_region TEXT, association_status TEXT NOT NULL, PRIMARY KEY(caption_id,person_ref), " +
                        "FOREIGN KEY(caption_id) REFERENCES semantic_caption(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(cluster_id) REFERENCES person_cluster(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS semantic_caption_person_cluster_idx ON semantic_caption_person_ref(cluster_id)")
            }
        }

        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_item ADD COLUMN exact_content_digest TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS media_item_exact_digest_idx ON media_item(exact_content_digest)")
                db.execSQL("ALTER TABLE person_cluster ADD COLUMN include_in_personal_memory INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE person_cluster SET include_in_personal_memory=1 WHERE reviewed=1 AND " +
                        "lower(trim(COALESCE(relationship,''))) IN " +
                        "('me','mother','mom','mum','father','dad','brother','sister','partner','spouse','wife','husband'," +
                        "'child','son','daughter','grandparent','grandmother','grandfather','grandma','grandpa')",
                )
                db.execSQL("ALTER TABLE semantic_caption ADD COLUMN representative_media_id TEXT")
                db.execSQL("ALTER TABLE semantic_caption ADD COLUMN source_type TEXT NOT NULL DEFAULT 'GEMMA_DIRECT'")
                db.execSQL("ALTER TABLE semantic_caption ADD COLUMN body_region_version TEXT NOT NULL DEFAULT 'person-body-regions-v1'")
                db.execSQL("ALTER TABLE semantic_caption ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE semantic_caption SET representative_media_id=evidence_media_id,created_at=updated_at")
                db.execSQL(
                    "UPDATE semantic_caption SET source_type=CASE scope " +
                        "WHEN 'VISUAL_GROUP' THEN 'LEGACY_VISUAL_GROUP_REPRESENTATIVE' " +
                        "WHEN 'EVENT' THEN 'LEGACY_EVENT_REPRESENTATIVE' ELSE 'LEGACY_MEDIA_DIRECT' END",
                )
                db.execSQL(
                    "UPDATE semantic_caption SET applicability='GROUP_CONTEXT_ONLY' " +
                        "WHERE scope IN ('VISUAL_GROUP','EVENT')",
                )
                db.execSQL(
                    "UPDATE semantic_fact SET scope='VISUAL_GROUP',applicability='LEGACY_GROUP_CONTEXT_ONLY' " +
                        "WHERE applicability='EXACT_DUPLICATE_SHARED'",
                )
                db.execSQL(
                    "UPDATE visual_group SET kind='PERCEPTUAL_SIMILARITY_LEGACY' WHERE kind='EXACT_DUPLICATE'",
                )
            }
        }

        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE semantic_caption ADD COLUMN chunk_policy_version TEXT")
                db.execSQL("ALTER TABLE semantic_caption ADD COLUMN chunked_at INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS semantic_caption_chunk (
                        id TEXT NOT NULL,
                        caption_id TEXT NOT NULL,
                        media_id TEXT NOT NULL,
                        scope TEXT NOT NULL,
                        scope_id TEXT NOT NULL,
                        evidence_media_id TEXT NOT NULL,
                        cluster_id TEXT,
                        chunk_type TEXT NOT NULL,
                        exact_text TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        applicability TEXT NOT NULL,
                        caption_model_version TEXT NOT NULL,
                        caption_prompt_version TEXT NOT NULL,
                        chunk_policy_version TEXT NOT NULL,
                        embedding_model_version TEXT,
                        embedding_state TEXT NOT NULL DEFAULT 'PENDING',
                        attempt_count INTEGER NOT NULL DEFAULT 0,
                        error TEXT,
                        lease_owner TEXT,
                        lease_expires_at INTEGER,
                        next_attempt_at INTEGER NOT NULL DEFAULT 0,
                        last_progress_at INTEGER,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(caption_id) REFERENCES semantic_caption(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(media_id) REFERENCES media_item(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(evidence_media_id) REFERENCES media_item(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(cluster_id) REFERENCES person_cluster(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS semantic_caption_chunk_caption_idx ON semantic_caption_chunk(caption_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS semantic_caption_chunk_media_idx ON semantic_caption_chunk(media_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS semantic_caption_chunk_evidence_idx ON semantic_caption_chunk(evidence_media_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS semantic_caption_chunk_cluster_idx ON semantic_caption_chunk(cluster_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS semantic_caption_chunk_queue_idx ON semantic_caption_chunk(embedding_state,next_attempt_at)")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS semantic_caption_chunk_fts USING FTS4(chunk_id,exact_text)")
            }
        }

        private fun rebuildLegacySchema(db: SupportSQLiteDatabase) {
            db.execSQL("PRAGMA defer_foreign_keys=ON")
            listOf("media_item", "media_tombstone", "ocr_block", "gallery_event", "event_media", "query_turn").forEach { table ->
                db.execSQL("CREATE TEMP TABLE `${table}_room_backup` AS SELECT * FROM `$table`")
            }
            listOf("event_media", "ocr_block", "gallery_event", "media_fts", "media_tombstone", "query_turn", "media_item").forEach { table ->
                db.execSQL("DROP TABLE `$table`")
            }

            db.execSQL("CREATE TABLE `media_item` (`id` TEXT NOT NULL, `filename` TEXT NOT NULL, `title` TEXT NOT NULL, `creator` TEXT, `location` TEXT NOT NULL DEFAULT '', `latitude` REAL, `longitude` REAL, `tags` TEXT NOT NULL DEFAULT '', `description` TEXT NOT NULL DEFAULT '', `license` TEXT NOT NULL DEFAULT '', `source_url` TEXT NOT NULL DEFAULT '', `asset_path` TEXT, `content_uri` TEXT, `preview_path` TEXT, `source_kind` TEXT NOT NULL DEFAULT 'DEMO_ASSET', `media_kind` TEXT NOT NULL DEFAULT 'IMAGE', `mime_type` TEXT NOT NULL DEFAULT 'image/jpeg', `captured_at` INTEGER, `modified_at` INTEGER, `duration_ms` INTEGER, `width` INTEGER NOT NULL DEFAULT 0, `height` INTEGER NOT NULL DEFAULT 0, `size_bytes` INTEGER NOT NULL DEFAULT 0, `ocr_text` TEXT NOT NULL DEFAULT '', `face_count` INTEGER NOT NULL DEFAULT 0, `index_state` TEXT NOT NULL DEFAULT 'READY', `index_error` TEXT, `indexed_at` INTEGER, `index_version` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("INSERT INTO media_item SELECT * FROM media_item_room_backup")
            db.execSQL("CREATE INDEX media_item_state_idx ON media_item(index_state)")
            db.execSQL("CREATE INDEX media_item_capture_idx ON media_item(captured_at)")

            db.execSQL("CREATE VIRTUAL TABLE `media_fts` USING FTS4(`media_id` TEXT NOT NULL, `title` TEXT NOT NULL, `location` TEXT NOT NULL, `tags` TEXT NOT NULL, `description` TEXT NOT NULL, `ocr_text` TEXT NOT NULL)")
            db.execSQL("INSERT INTO media_fts(media_id,title,location,tags,description,ocr_text) SELECT id,title,location,tags,description,ocr_text FROM media_item")

            db.execSQL("CREATE TABLE `media_tombstone` (`stable_id` TEXT NOT NULL, `content_uri` TEXT NOT NULL, `deleted_at` INTEGER NOT NULL, `reason` TEXT NOT NULL, PRIMARY KEY(`stable_id`))")
            db.execSQL("INSERT INTO media_tombstone SELECT * FROM media_tombstone_room_backup")
            db.execSQL("CREATE INDEX media_tombstone_uri_idx ON media_tombstone(content_uri)")

            db.execSQL("CREATE TABLE `ocr_block` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `media_id` TEXT NOT NULL, `text` TEXT NOT NULL, `confidence` REAL NOT NULL, `left_pos` REAL NOT NULL, `top_pos` REAL NOT NULL, `right_pos` REAL NOT NULL, `bottom_pos` REAL NOT NULL, FOREIGN KEY(`media_id`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("INSERT INTO ocr_block SELECT * FROM ocr_block_room_backup")
            db.execSQL("CREATE INDEX ocr_block_media_idx ON ocr_block(media_id)")

            db.execSQL("CREATE TABLE `gallery_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day_start` INTEGER NOT NULL, `title` TEXT NOT NULL, `member_count` INTEGER NOT NULL)")
            db.execSQL("INSERT INTO gallery_event SELECT * FROM gallery_event_room_backup")
            db.execSQL("CREATE UNIQUE INDEX index_gallery_event_day_start ON gallery_event(day_start)")

            db.execSQL("CREATE TABLE `event_media` (`event_id` INTEGER NOT NULL, `media_id` TEXT NOT NULL, PRIMARY KEY(`event_id`, `media_id`), FOREIGN KEY(`event_id`) REFERENCES `gallery_event`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`media_id`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("INSERT INTO event_media SELECT * FROM event_media_room_backup")
            db.execSQL("CREATE INDEX index_event_media_media_id ON event_media(media_id)")

            db.execSQL("CREATE TABLE `query_turn` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `query` TEXT NOT NULL, `plan_summary` TEXT NOT NULL, `result_count` INTEGER NOT NULL, `elapsed_ms` INTEGER NOT NULL, `created_at` INTEGER NOT NULL)")
            db.execSQL("INSERT INTO query_turn SELECT * FROM query_turn_room_backup")
            listOf("media_item", "media_tombstone", "ocr_block", "gallery_event", "event_media", "query_turn").forEach { table ->
                db.execSQL("DROP TABLE `${table}_room_backup`")
            }
        }

        private fun createTombstones(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS media_tombstone (stable_id TEXT NOT NULL PRIMARY KEY, content_uri TEXT NOT NULL, deleted_at INTEGER NOT NULL, reason TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS media_tombstone_uri_idx ON media_tombstone(content_uri)")
        }

        private fun createStages(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS media_index_stage (media_id TEXT NOT NULL, stage TEXT NOT NULL, status TEXT NOT NULL, producer_version TEXT NOT NULL, attempt_count INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL, error TEXT, PRIMARY KEY(media_id, stage), FOREIGN KEY(media_id) REFERENCES media_item(id) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS media_index_stage_status_idx ON media_index_stage(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_media_index_stage_media_id ON media_index_stage(media_id)")
        }
    }
}
