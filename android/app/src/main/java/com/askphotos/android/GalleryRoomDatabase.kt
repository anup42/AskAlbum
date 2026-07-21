package com.askphotos.android

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
    indices = [Index(name = "media_item_state_idx", value = ["index_state"]), Index(name = "media_item_capture_idx", value = ["captured_at"])],
)
data class MediaItemEntity(
    @PrimaryKey val id: String,
    val filename: String,
    val title: String,
    val creator: String?,
    @ColumnInfo(defaultValue = "''") val location: String,
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

@Entity(tableName = "gallery_event", indices = [Index(value = ["day_start"], unique = true)])
data class GalleryEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "day_start") val dayStart: Long,
    val title: String,
    @ColumnInfo(name = "member_count") val memberCount: Int,
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
    @ColumnInfo(name = "producer_version") val producerVersion: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "query_turn")
data class QueryTurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    @ColumnInfo(name = "plan_summary") val planSummary: String,
    @ColumnInfo(name = "result_count") val resultCount: Int,
    @ColumnInfo(name = "elapsed_ms") val elapsedMs: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "media_index_stage",
    primaryKeys = ["media_id", "stage"],
    foreignKeys = [ForeignKey(entity = MediaItemEntity::class, parentColumns = ["id"], childColumns = ["media_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(name = "media_index_stage_status_idx", value = ["status"]), Index(value = ["media_id"])],
)
data class MediaIndexStageEntity(
    @ColumnInfo(name = "media_id") val mediaId: String,
    val stage: String,
    val status: String,
    @ColumnInfo(name = "producer_version") val producerVersion: String,
    @ColumnInfo(name = "attempt_count", defaultValue = "0") val attemptCount: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val error: String?,
)

@Database(
    entities = [
        MediaItemEntity::class,
        MediaFtsEntity::class,
        MediaTombstoneEntity::class,
        OcrBlockEntity::class,
        OcrEntityEntity::class,
        GalleryEventEntity::class,
        EventMediaEntity::class,
        PeopleSettingsEntity::class,
        PersonClusterEntity::class,
        FaceInstanceEntity::class,
        QueryTurnEntity::class,
        MediaIndexStageEntity::class,
    ],
    version = 8,
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
