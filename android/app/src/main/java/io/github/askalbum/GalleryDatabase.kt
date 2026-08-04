package io.github.anup42.askalbum

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class GalleryDatabase(
    private val context: Context,
    private val databaseName: String = GalleryRoomDatabase.NAME,
) {
    private val room = GalleryRoomDatabase.open(context, databaseName)
    private val readableDatabase get() = GallerySqlDatabase(room.openHelper.readableDatabase)
    private val writableDatabase get() = GallerySqlDatabase(room.openHelper.writableDatabase)

    fun seedDemoIfEmpty(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM media_item WHERE source_kind='DEMO_ASSET'", null).use { cursor ->
            if (cursor.moveToFirst() && cursor.getInt(0) > 0) return cursor.getInt(0)
        }

        val manifest = context.assets.open("manifest.json").bufferedReader().use { it.readText() }
        val entries = JSONArray(manifest)
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (i in 0 until entries.length()) {
                val item = entries.getJSONObject(i)
                val tagArray = item.getJSONArray("tags")
                val tags = buildList {
                    for (tagIndex in 0 until tagArray.length()) add(tagArray.getString(tagIndex))
                }
                val location = item.optString("location_name", "Unknown location")
                val galleryItem = GalleryItem(
                    id = item.getString("id"),
                    filename = item.getString("filename"),
                    title = item.getString("title"),
                    creator = item.optString("creator").takeIf { it.isNotBlank() && it != "null" },
                    location = location,
                    album = item.optString("album", location),
                    latitude = item.optDouble("latitude").takeUnless(Double::isNaN),
                    longitude = item.optDouble("longitude").takeUnless(Double::isNaN),
                    tags = tags,
                    description = item.getString("alt_text"),
                    license = item.getString("license"),
                    sourceUrl = item.getString("source_url"),
                    assetPath = "images/${item.getString("filename")}",
                    width = item.optInt("width"),
                    height = item.optInt("height"),
                )
                insertOrReplace(db, galleryItem)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        rebuildEvents()
        return entries.length()
    }

    fun ensureStageRows() {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val complete = "source_kind='DEMO_ASSET' OR index_state='READY'"
        val defaultProducer = "CASE WHEN source_kind='DEMO_ASSET' THEN 'demo-sidecar-v1' ELSE 'media-compiler-v1' END"
        val stages = listOf(
            Triple(IndexStage.DISCOVERY, "'COMPLETE'", defaultProducer),
            Triple(IndexStage.METADATA, "'COMPLETE'", defaultProducer),
            Triple(IndexStage.THUMBNAIL, "CASE WHEN $complete THEN 'COMPLETE' ELSE 'PENDING' END", defaultProducer),
            Triple(
                IndexStage.VIDEO_KEYFRAMES,
                "CASE WHEN media_kind='VIDEO' AND NOT ($complete) THEN 'PENDING' ELSE 'SKIPPED' END",
                "CASE WHEN media_kind='VIDEO' THEN '${VideoKeyframePolicy.PRODUCER_VERSION}' ELSE 'not-video' END",
            ),
            Triple(IndexStage.EMBEDDING, "'PENDING'", "'not-installed'"),
            Triple(IndexStage.OCR, "CASE WHEN $complete THEN 'COMPLETE' ELSE 'PENDING' END", defaultProducer),
            Triple(IndexStage.FACES, "'SKIPPED'", "'disabled-until-opt-in'"),
            Triple(IndexStage.EVENTS, "CASE WHEN $complete THEN 'COMPLETE' ELSE 'PENDING' END", "'day-event-v1'"),
            Triple(IndexStage.ENRICHMENT, "CASE WHEN $complete THEN 'COMPLETE' ELSE 'PENDING' END", defaultProducer),
        )
        db.beginTransaction()
        try {
            stages.forEach { (stage, statusExpression, producerExpression) ->
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO media_index_stage(
                        media_id,stage,status,producer_version,attempt_count,updated_at,error
                    )
                    SELECT id,?,${statusExpression},${producerExpression},0,?,NULL
                    FROM media_item
                    """.trimIndent(),
                    arrayOf(stage.name, now),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsertImported(items: List<ImportedMedia>): Int {
        if (items.isEmpty()) return 0
        val db = writableDatabase
        var changed = 0
        db.beginTransaction()
        try {
            items.forEach { imported ->
                val existing = itemById(db, imported.stableId)
                val unchanged = existing != null && existing.contentUri == imported.uri &&
                    existing.modifiedAt == imported.modifiedAt && existing.sizeBytes == imported.sizeBytes &&
                    existing.album == imported.album && existing.capturedAt == imported.capturedAt &&
                    existing.latitude == imported.latitude && existing.longitude == imported.longitude
                if (unchanged) {
                    initializeStages(db, existing, replace = false)
                    return@forEach
                }
                val item = GalleryItem(
                    id = imported.stableId,
                    filename = imported.displayName,
                    title = imported.displayName.substringBeforeLast('.').ifBlank { "Untitled media" },
                    creator = null,
                    location = "",
                    album = imported.album,
                    latitude = imported.latitude,
                    longitude = imported.longitude,
                    tags = emptyList(),
                    description = "",
                    license = "Personal media",
                    sourceUrl = "",
                    assetPath = null,
                    contentUri = imported.uri,
                    previewPath = null,
                    source = imported.source,
                    kind = mediaKind(imported.mimeType),
                    mimeType = imported.mimeType,
                    capturedAt = imported.capturedAt,
                    modifiedAt = imported.modifiedAt,
                    durationMs = imported.durationMs,
                    width = imported.width,
                    height = imported.height,
                    sizeBytes = imported.sizeBytes,
                    ocrText = "",
                    faceCount = 0,
                    indexState = IndexState.PENDING,
                    indexError = null,
                    accessState = MediaAccessState.ACCESSIBLE,
                    lastSeenAt = System.currentTimeMillis(),
                )
                insertOrReplace(db, item)
                initializeStages(db, item, replace = true)
                changed++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    fun pendingItems(limit: Int): List<GalleryItem> {
        val now = System.currentTimeMillis()
        return readableDatabase.rawQuery(
            """
            SELECT m.* FROM media_item m
            JOIN media_index_stage s ON s.media_id=m.id AND s.stage='THUMBNAIL'
            WHERE m.source_kind!='DEMO_ASSET'
              AND (
                m.index_state='PENDING'
                OR (
                  m.index_state='FAILED_RETRYABLE'
                  AND s.attempt_count<?
                  AND s.next_attempt_at<=?
                )
              )
              AND (s.lease_expires_at IS NULL OR s.lease_expires_at<=?)
            ORDER BY CASE m.index_state WHEN 'PENDING' THEN 0 ELSE 1 END, m.modified_at DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                IndexingRetryPolicy.MAX_ITEM_ATTEMPTS.toString(),
                now.toString(),
                now.toString(),
                limit.toString(),
            ),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursorItem(cursor)) } }
    }

    fun pendingItemsForIds(mediaIds: Set<String>, limit: Int): List<GalleryItem> = queryScoped(mediaIds, limit) { ids, remaining ->
        val now = System.currentTimeMillis()
        readableDatabase.rawQuery(
            """
            SELECT m.* FROM media_item m
            JOIN media_index_stage s ON s.media_id=m.id AND s.stage='THUMBNAIL'
            WHERE m.id IN (${ids.joinToString(",") { "?" }})
              AND m.source_kind!='DEMO_ASSET'
              AND (
                m.index_state='PENDING'
                OR (m.index_state='FAILED_RETRYABLE' AND s.attempt_count<? AND s.next_attempt_at<=?)
              )
              AND (s.lease_expires_at IS NULL OR s.lease_expires_at<=?)
            ORDER BY CASE m.index_state WHEN 'PENDING' THEN 0 ELSE 1 END, m.modified_at DESC
            LIMIT ?
            """.trimIndent(),
            (ids + listOf(
                IndexingRetryPolicy.MAX_ITEM_ATTEMPTS.toString(),
                now.toString(),
                now.toString(),
                remaining.toString(),
            )).toTypedArray(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursorItem(cursor)) } }
    }

    fun requestGalleryReindex(mediaIds: Set<String>) {
        if (mediaIds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            mediaIds.chunked(SQLITE_ID_CHUNK).forEach { ids ->
                val placeholders = ids.joinToString(",") { "?" }
                db.execSQL(
                    "UPDATE media_item SET index_state='PENDING', index_error=NULL WHERE id IN ($placeholders)",
                    ids.toTypedArray(),
                )
                db.execSQL(
                    "UPDATE media_index_stage SET status='PENDING', producer_version='requested-reindex', error=NULL " +
                        "WHERE media_id IN ($placeholders) AND stage IN ('THUMBNAIL','OCR','EVENTS','ENRICHMENT')",
                    ids.toTypedArray(),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun requestOcrReindex(producerVersion: String): Int {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val stageProducerVersion = "$producerVersion+document-facts-v2"
        db.beginTransaction()
        return try {
            val changed = db.update(
                "media_item",
                ContentValues().apply { put("index_state", IndexState.PENDING.name); putNull("index_error") },
                "id IN (SELECT media_id FROM media_index_stage WHERE stage='OCR' AND status='COMPLETE' " +
                    "AND COALESCE(producer_version,'')!=?)",
                arrayOf(stageProducerVersion),
            )
            db.execSQL(
                "UPDATE media_index_stage SET status='PENDING',producer_version=?,updated_at=?,error=NULL " +
                    "WHERE stage='OCR' AND status='COMPLETE' AND COALESCE(producer_version,'')!=?",
                arrayOf(stageProducerVersion, now, stageProducerVersion),
            )
            db.setTransactionSuccessful()
            changed
        } finally {
            db.endTransaction()
        }
    }

    fun embeddingPendingItems(producerVersion: String, limit: Int): List<GalleryItem> {
        val now = System.currentTimeMillis()
        return readableDatabase.rawQuery(
        """SELECT m.* FROM media_item m
            JOIN media_index_stage s ON s.media_id=m.id AND s.stage='EMBEDDING'
            WHERE m.access_state='ACCESSIBLE'
              AND (
                s.status='PENDING'
                OR (s.status='FAILED_RETRYABLE' AND s.attempt_count<? AND s.next_attempt_at<=?)
                OR (s.status='COMPLETE' AND s.producer_version!=?)
              )
              AND (s.lease_expires_at IS NULL OR s.lease_expires_at<=?)
            ORDER BY COALESCE(m.captured_at,0) DESC, m.id LIMIT ?""".trimIndent(),
        arrayOf(
            IndexingRetryPolicy.MAX_ITEM_ATTEMPTS.toString(),
            now.toString(),
            producerVersion,
            now.toString(),
            limit.toString(),
        ),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursorItem(cursor)) } }
    }

    fun embeddingPendingItemsForIds(producerVersion: String, mediaIds: Set<String>, limit: Int): List<GalleryItem> =
        queryScoped(mediaIds, limit) { ids, remaining ->
            val now = System.currentTimeMillis()
            readableDatabase.rawQuery(
                """SELECT m.* FROM media_item m
                    JOIN media_index_stage s ON s.media_id=m.id AND s.stage='EMBEDDING'
                    WHERE m.access_state='ACCESSIBLE' AND m.id IN (${ids.joinToString(",") { "?" }})
                    AND (
                      s.status='PENDING'
                      OR (s.status='FAILED_RETRYABLE' AND s.attempt_count<? AND s.next_attempt_at<=?)
                      OR (s.status='COMPLETE' AND s.producer_version!=?)
                    )
                    AND (s.lease_expires_at IS NULL OR s.lease_expires_at<=?)
                    ORDER BY COALESCE(m.captured_at,0) DESC, m.id LIMIT ?""".trimIndent(),
                (ids + listOf(
                    IndexingRetryPolicy.MAX_ITEM_ATTEMPTS.toString(),
                    now.toString(),
                    producerVersion,
                    now.toString(),
                    remaining.toString(),
                )).toTypedArray(),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursorItem(cursor)) } }
        }

    fun accessibleIds(): Set<String> = readableDatabase.rawQuery(
        "SELECT id FROM media_item WHERE access_state='ACCESSIBLE'", null,
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun accessibleVectorIds(): Set<String> = readableDatabase.rawQuery(
        "SELECT id FROM media_item WHERE access_state='ACCESSIBLE' UNION SELECT v.id FROM video_keyframe v JOIN media_item m ON m.id=v.media_id WHERE m.access_state='ACCESSIBLE'",
        null,
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun vectorIdsForMedia(mediaIds: Set<String>): Set<String> {
        if (mediaIds.isEmpty()) return emptySet()
        val placeholders = mediaIds.joinToString(",") { "?" }
        val keyframes = readableDatabase.rawQuery(
            "SELECT id FROM video_keyframe WHERE media_id IN ($placeholders)", mediaIds.toTypedArray(),
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return mediaIds + keyframes
    }

    fun keyframeEmbeddingPendingItems(producerVersion: String, limit: Int): List<VideoKeyframeRecord> = readableDatabase.rawQuery(
        "SELECT v.* FROM video_keyframe v JOIN media_item m ON m.id=v.media_id WHERE m.access_state='ACCESSIBLE' AND (v.embedding_version IS NULL OR v.embedding_version!=?) ORDER BY COALESCE(m.captured_at,0) DESC,v.timestamp_ms LIMIT ?",
        arrayOf(producerVersion, limit.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursorVideoKeyframe(cursor)) } }

    fun keyframeEmbeddingPendingItemsForIds(
        producerVersion: String,
        mediaIds: Set<String>,
        limit: Int,
    ): List<VideoKeyframeRecord> = queryScoped(mediaIds, limit) { ids, remaining ->
        readableDatabase.rawQuery(
            "SELECT v.* FROM video_keyframe v JOIN media_item m ON m.id=v.media_id " +
                "WHERE m.access_state='ACCESSIBLE' AND m.id IN (${ids.joinToString(",") { "?" }}) " +
                "AND (v.embedding_version IS NULL OR v.embedding_version!=?) " +
                "ORDER BY COALESCE(m.captured_at,0) DESC,v.timestamp_ms LIMIT ?",
            (ids + producerVersion + remaining.toString()).toTypedArray(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursorVideoKeyframe(cursor)) } }
    }

    private fun <T> queryScoped(mediaIds: Set<String>, limit: Int, query: (List<String>, Int) -> List<T>): List<T> {
        require(limit > 0)
        if (mediaIds.isEmpty()) return emptyList()
        val result = ArrayList<T>(minOf(limit, mediaIds.size))
        for (ids in mediaIds.chunked(SQLITE_ID_CHUNK)) {
            result += query(ids, limit - result.size)
            if (result.size >= limit) break
        }
        return result
    }

    fun completeKeyframeEmbedding(id: String, producerVersion: String) {
        writableDatabase.update("video_keyframe", ContentValues().apply { put("embedding_version", producerVersion) }, "id=?", arrayOf(id))
    }

    fun videoKeyframes(mediaId: String): List<VideoKeyframeRecord> = readableDatabase.rawQuery(
        "SELECT * FROM video_keyframe WHERE media_id=? ORDER BY timestamp_ms", arrayOf(mediaId),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursorVideoKeyframe(cursor)) } }

    fun videoKeyframesByIds(ids: Set<String>): Map<String, VideoKeyframeRecord> {
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT * FROM video_keyframe WHERE id IN ($placeholders)", ids.toTypedArray(),
        ).use { cursor -> buildMap { while (cursor.moveToNext()) cursorVideoKeyframe(cursor).also { put(it.id, it) } } }
    }

    fun markEmbedding(
        id: String,
        producerVersion: String,
        owner: String = "database-direct",
    ): Boolean {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val changed = db.update(
            "media_index_stage",
            ContentValues().apply {
                put("status", StageStatus.RUNNING.name)
                put("producer_version", producerVersion)
                put("updated_at", now)
                put("last_progress_at", now)
                put("lease_owner", owner)
                put("lease_expires_at", now + IndexingRetryPolicy.LEASE_MILLIS)
                putNull("error")
            },
            "media_id=? AND stage=? AND (" +
                "status='PENDING' OR (status='FAILED_RETRYABLE' AND attempt_count<? AND next_attempt_at<=?) " +
                "OR (status='COMPLETE' AND producer_version!=?)) " +
                "AND (lease_expires_at IS NULL OR lease_expires_at<=?)",
            arrayOf(
                id,
                IndexStage.EMBEDDING.name,
                IndexingRetryPolicy.MAX_ITEM_ATTEMPTS.toString(),
                now.toString(),
                producerVersion,
                now.toString(),
            ),
        )
        if (changed > 0) {
            db.execSQL(
                "UPDATE media_index_stage SET attempt_count=attempt_count+1 WHERE media_id=? AND stage=?",
                arrayOf(id, IndexStage.EMBEDDING.name),
            )
        }
        return changed > 0
    }

    fun completeEmbedding(id: String, producerVersion: String) {
        updateStage(writableDatabase, id, IndexStage.EMBEDDING, StageStatus.COMPLETE, producerVersion)
        clearStageLease(writableDatabase, id, IndexStage.EMBEDDING)
    }

    fun failEmbedding(id: String, producerVersion: String, message: String, permanent: Boolean): StageStatus {
        val db = writableDatabase
        val attempts = stageAttemptCount(db, id, IndexStage.EMBEDDING)
        val status = IndexingRetryPolicy.failedStatus(permanent, attempts)
        updateStage(db, id, IndexStage.EMBEDDING, status, producerVersion, error = message)
        db.update("media_index_stage", ContentValues().apply {
            putNull("lease_owner")
            putNull("lease_expires_at")
            put("last_progress_at", System.currentTimeMillis())
            put(
                "next_attempt_at",
                if (status == StageStatus.FAILED_RETRYABLE) {
                    IndexingRetryPolicy.nextAttemptAt(System.currentTimeMillis(), attempts)
                } else {
                    0L
                },
            )
            if (status == StageStatus.FAILED_EXHAUSTED) put("error", "retry_exhausted:${message.take(220)}")
        }, "media_id=? AND stage=?", arrayOf(id, IndexStage.EMBEDDING.name))
        return status
    }

    fun markIndexing(id: String, owner: String): Boolean {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val changed = db.update("media_item", ContentValues().apply {
            put("index_state", IndexState.INDEXING.name)
            putNull("index_error")
        }, "id=? AND index_state IN (?,?)", arrayOf(
            id,
            IndexState.PENDING.name,
            IndexState.FAILED_RETRYABLE.name,
        ))
        if (changed == 0) return false
        listOf(IndexStage.THUMBNAIL, IndexStage.OCR, IndexStage.ENRICHMENT).forEach {
            updateStage(db, id, it, StageStatus.RUNNING, "mlkit-mobile-v1", incrementAttempt = true)
        }
        val item = itemById(db, id)
        updateStage(
            db,
            id,
            IndexStage.VIDEO_KEYFRAMES,
            if (item?.kind == MediaKind.VIDEO) StageStatus.RUNNING else StageStatus.SKIPPED,
            if (item?.kind == MediaKind.VIDEO) VideoKeyframePolicy.PRODUCER_VERSION else "not-video",
            incrementAttempt = item?.kind == MediaKind.VIDEO,
        )
        db.update("media_index_stage", ContentValues().apply {
            put("lease_owner", owner)
            put("lease_expires_at", now + IndexingRetryPolicy.LEASE_MILLIS)
            put("last_progress_at", now)
        }, "media_id=? AND status=?", arrayOf(id, StageStatus.RUNNING.name))
        return true
    }

    fun completeIndex(
        id: String,
        labels: List<String>,
        description: String,
        ocrText: String,
        faceCount: Int,
        previewPath: String?,
        blocks: List<OcrBlockRecord>,
        entities: List<OcrEntityRecord>,
        ocrAttempted: Boolean,
        ocrProducerVersion: String?,
        visualFeatures: VisualFeatures,
        keyframes: List<VideoKeyframeRecord>,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("media_item", ContentValues().apply {
                put("tags", labels.joinToString(TAG_SEPARATOR))
                put("description", description)
                put("ocr_text", ocrText)
                put("face_count", faceCount)
                put("preview_path", previewPath)
                put("index_state", IndexState.READY.name)
                putNull("index_error")
                put("indexed_at", System.currentTimeMillis())
                put("index_version", "media-compiler-v2")
                put("perceptual_hash", java.lang.Long.toUnsignedString(visualFeatures.perceptualHash, 16))
                put("blur_score", visualFeatures.blurScore)
                put("exposure_score", visualFeatures.exposureScore)
                put("quality_score", visualFeatures.qualityScore)
            }, "id=?", arrayOf(id))
            db.delete("ocr_block", "media_id=?", arrayOf(id))
            db.delete("ocr_entity", "media_id=?", arrayOf(id))
            db.delete("video_keyframe", "media_id=?", arrayOf(id))
            blocks.forEach { block ->
                db.insert("ocr_block", null, ContentValues().apply {
                    put("media_id", id)
                    put("text", block.text)
                    put("normalized_text", block.normalizedText)
                    if (block.language == null) putNull("language") else put("language", block.language)
                    put("page_index", block.pageIndex)
                    if (block.timestampMs == null) putNull("timestamp_ms") else put("timestamp_ms", block.timestampMs)
                    put("confidence", block.confidence)
                    put("left_pos", block.left)
                    put("top_pos", block.top)
                    put("right_pos", block.right)
                    put("bottom_pos", block.bottom)
                })
            }
            entities.forEach { entity ->
                db.insert("ocr_entity", null, ContentValues().apply {
                    put("media_id", id)
                    put("entity_type", entity.type.name)
                    put("raw_text", entity.rawText)
                    put("normalized_value", entity.normalizedValue)
                    if (entity.label == null) putNull("label") else put("label", entity.label)
                    put("confidence", entity.confidence)
                    put("left_pos", entity.left)
                    put("top_pos", entity.top)
                    put("right_pos", entity.right)
                    put("bottom_pos", entity.bottom)
                    put("producer_version", entity.producerVersion)
                })
            }
            keyframes.forEach { keyframe ->
                require(keyframe.mediaId == id) { "Keyframe parent mismatch" }
                db.insertOrThrow("video_keyframe", null, ContentValues().apply {
                    put("id", keyframe.id)
                    put("media_id", id)
                    put("timestamp_ms", keyframe.timestampMs)
                    put("preview_path", keyframe.previewPath)
                    put("labels", keyframe.labels.joinToString(TAG_SEPARATOR))
                    put("ocr_text", keyframe.ocrText)
                    put("perceptual_hash", java.lang.Long.toUnsignedString(keyframe.perceptualHash, 16))
                    put("quality_score", keyframe.qualityScore)
                    put("producer_version", keyframe.producerVersion)
                    if (keyframe.embeddingVersion == null) putNull("embedding_version") else put("embedding_version", keyframe.embeddingVersion)
                })
            }
            itemById(db, id)?.let { refreshFts(db, it) }
            listOf(IndexStage.THUMBNAIL, IndexStage.ENRICHMENT).forEach {
                updateStage(db, id, it, StageStatus.COMPLETE, "mlkit-mobile-v1")
            }
            updateStage(
                db,
                id,
                IndexStage.OCR,
                if (ocrAttempted) StageStatus.COMPLETE else StageStatus.SKIPPED,
                if (ocrAttempted) "${requireNotNull(ocrProducerVersion)}+document-facts-v2" else "ocr-likelihood-gate-v1",
            )
            updateStage(
                db,
                id,
                IndexStage.VIDEO_KEYFRAMES,
                if (keyframes.isNotEmpty()) StageStatus.COMPLETE else StageStatus.SKIPPED,
                if (keyframes.isNotEmpty()) VideoKeyframePolicy.PRODUCER_VERSION else "not-video",
            )
            if (peopleIndexEnabled(db)) {
                updateStage(db, id, IndexStage.FACES, StageStatus.PENDING, "mlkit-face-detection-v1")
            } else {
                updateStage(db, id, IndexStage.FACES, StageStatus.SKIPPED, "disabled-until-opt-in")
            }
            clearMediaIndexLeases(db, id)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun failIndex(id: String, message: String, permanent: Boolean): StageStatus {
        val db = writableDatabase
        val attempts = stageAttemptCount(db, id, IndexStage.THUMBNAIL)
        val status = IndexingRetryPolicy.failedStatus(permanent, attempts)
        db.update("media_item", ContentValues().apply {
            put("index_state", when (status) {
                StageStatus.FAILED_RETRYABLE -> IndexState.FAILED_RETRYABLE.name
                StageStatus.FAILED_EXHAUSTED -> IndexState.FAILED_EXHAUSTED.name
                else -> IndexState.FAILED_PERMANENT.name
            })
            put(
                "index_error",
                if (status == StageStatus.FAILED_EXHAUSTED) "retry_exhausted:${message.take(220)}" else message.take(300),
            )
        }, "id=?", arrayOf(id))
        listOf(IndexStage.THUMBNAIL, IndexStage.VIDEO_KEYFRAMES, IndexStage.OCR, IndexStage.ENRICHMENT).forEach {
            updateStage(db, id, it, status, "mlkit-mobile-v1", error = message)
        }
        db.update("media_index_stage", ContentValues().apply {
            putNull("lease_owner")
            putNull("lease_expires_at")
            put("last_progress_at", System.currentTimeMillis())
            put(
                "next_attempt_at",
                if (status == StageStatus.FAILED_RETRYABLE) {
                    IndexingRetryPolicy.nextAttemptAt(System.currentTimeMillis(), attempts)
                } else {
                    0L
                },
            )
            if (status == StageStatus.FAILED_EXHAUSTED) put("error", "retry_exhausted:${message.take(220)}")
        }, "media_id=?", arrayOf(id))
        return status
    }

    fun recoverInterruptedJobs() {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE media_item SET index_state='PENDING' WHERE index_state='INDEXING' AND id IN (" +
                    "SELECT media_id FROM media_index_stage WHERE stage='THUMBNAIL' AND status='RUNNING' " +
                    "AND lease_expires_at IS NOT NULL AND lease_expires_at<=?)",
                arrayOf(now),
            )
            db.execSQL(
                "UPDATE media_index_stage SET status='PENDING'," +
                    "attempt_count=CASE WHEN attempt_count>0 THEN attempt_count-1 ELSE 0 END," +
                    "updated_at=?,error='lease_expired',lease_owner=NULL,lease_expires_at=NULL " +
                    "WHERE status='RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at<=?",
                arrayOf(now, now),
            )
            db.execSQL(
                """
                UPDATE semantic_enrichment_job
                SET status='PENDING',
                    attempt_count=CASE WHEN attempt_count > 0 THEN attempt_count - 1 ELSE 0 END,
                    updated_at=$now,
                    error='lease_expired',
                    lease_owner=NULL,
                    lease_expires_at=NULL
                WHERE status='RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at<=$now
                """.trimIndent(),
            )
            db.execSQL(
                """
                UPDATE semantic_caption_chunk
                SET embedding_state='PENDING',
                    attempt_count=CASE WHEN attempt_count > 0 THEN attempt_count - 1 ELSE 0 END,
                    updated_at=$now,
                    error='lease_expired',
                    lease_owner=NULL,
                    lease_expires_at=NULL
                WHERE embedding_state='RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at<=$now
                """.trimIndent(),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun nextMediaRetryAt(): Long? = readableDatabase.rawQuery(
        "SELECT MIN(next_attempt_at) FROM media_index_stage WHERE stage='THUMBNAIL' " +
            "AND status='FAILED_RETRYABLE' AND attempt_count<?",
        arrayOf(IndexingRetryPolicy.MAX_ITEM_ATTEMPTS.toString()),
    ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }

    fun nextEmbeddingRetryAt(): Long? = readableDatabase.rawQuery(
        "SELECT MIN(next_attempt_at) FROM media_index_stage WHERE stage='EMBEDDING' " +
            "AND status='FAILED_RETRYABLE' AND attempt_count<?",
        arrayOf(IndexingRetryPolicy.MAX_ITEM_ATTEMPTS.toString()),
    ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }

    private fun clearMediaIndexLeases(db: GallerySqlDatabase, id: String) {
        db.update("media_index_stage", ContentValues().apply {
            putNull("lease_owner")
            putNull("lease_expires_at")
            put("next_attempt_at", 0L)
            put("last_progress_at", System.currentTimeMillis())
        }, "media_id=?", arrayOf(id))
    }

    private fun clearStageLease(db: GallerySqlDatabase, id: String, stage: IndexStage) {
        db.update("media_index_stage", ContentValues().apply {
            putNull("lease_owner")
            putNull("lease_expires_at")
            put("next_attempt_at", 0L)
            put("last_progress_at", System.currentTimeMillis())
        }, "media_id=? AND stage=?", arrayOf(id, stage.name))
    }

    fun allItems(): List<GalleryItem> = allItems(readableDatabase)

    fun mediaStoreItemsIncludingInaccessible(): List<GalleryItem> = queryItems(
        "source_kind=?", arrayOf(MediaSource.MEDIA_STORE.name), "COALESCE(captured_at,0) DESC", null,
    )

    fun applyReconciliation(plan: MediaReconciliationPlan): Int {
        val db = writableDatabase
        var changed = 0
        db.beginTransaction()
        try {
            plan.seenUris.forEach { uri ->
                changed += db.update("media_item", ContentValues().apply {
                    put("access_state", MediaAccessState.ACCESSIBLE.name)
                    put("last_seen_at", System.currentTimeMillis())
                }, "content_uri=? AND source_kind=?", arrayOf(uri, MediaSource.MEDIA_STORE.name))
            }
            plan.inaccessibleUris.forEach { uri ->
                changed += db.update("media_item", ContentValues().apply {
                    put("access_state", MediaAccessState.INACCESSIBLE.name)
                }, "content_uri=? AND source_kind=?", arrayOf(uri, MediaSource.MEDIA_STORE.name))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val removed = removeImportedByUris(plan.deletedUris, "mediastore_deleted")
        return changed + removed.deletedItems
    }

    fun removeImportedByUris(uris: Collection<String>, reason: String): MediaRemovalResult {
        val requested = uris.asSequence().map(String::trim).filter(String::isNotBlank).distinct().toList()
        require(requested.all { UriSafety.isMediaContentUri(it) }) { "Only MediaStore content URIs can be removed" }
        if (requested.isEmpty()) return MediaRemovalResult(0, 0, 0, 0, 0)
        val db = writableDatabase
        val previews = mutableListOf<String>()
        var matched = 0
        var deleted = 0
        var tombstones = 0
        db.beginTransaction()
        try {
            requested.forEach { uri ->
                db.query(
                    "media_item",
                    arrayOf("id", "content_uri", "preview_path"),
                    "content_uri=? AND source_kind != ?",
                    arrayOf(uri, MediaSource.DEMO_ASSET.name),
                    null,
                    null,
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        matched++
                        val id = cursor.getString(0)
                        val contentUri = cursor.getString(1)
                        if (!cursor.isNull(2)) previews += cursor.getString(2)
                        db.rawQuery("SELECT preview_path FROM video_keyframe WHERE media_id=?", arrayOf(id)).use { frames ->
                            while (frames.moveToNext()) previews += frames.getString(0)
                        }
                        val tombstoneValues = ContentValues().apply {
                            put("stable_id", id)
                            put("content_uri", contentUri)
                            put("deleted_at", System.currentTimeMillis())
                            put("reason", reason.take(120))
                        }
                        if (db.insertWithOnConflict("media_tombstone", null, tombstoneValues, SQLiteDatabase.CONFLICT_REPLACE) >= 0) tombstones++
                        db.delete("media_fts", "media_id=?", arrayOf(id))
                        deleted += db.delete("media_item", "id=?", arrayOf(id))
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        var previewFilesDeleted = 0
        previews.distinct().forEach { path ->
            val preview = java.io.File(path).canonicalFile
            val allowedRoots = listOf("previews", "video-keyframes", "pdf-pages").map { java.io.File(context.filesDir, it).canonicalFile }
            val pdfRoot = java.io.File(context.filesDir, "pdf-pages").canonicalFile
            if (preview.toPath().startsWith(pdfRoot.toPath()) && preview.parentFile?.parentFile == pdfRoot) {
                preview.parentFile?.listFiles()?.filter { it.isFile }?.forEach { file ->
                    if (file.delete()) previewFilesDeleted++
                }
                preview.parentFile?.delete()
            } else if (allowedRoots.any { preview.toPath().startsWith(it.toPath()) } && preview.exists() && preview.delete()) {
                previewFilesDeleted++
            }
        }
        rebuildEvents()
        return MediaRemovalResult(requested.size, matched, deleted, tombstones, previewFilesDeleted)
    }

    fun tombstoneCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM media_tombstone", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    fun peopleIndexStatus(): PeopleIndexStatus {
        val db = readableDatabase
        val settings = db.rawQuery(
            "SELECT enabled,consent_version,enabled_at FROM people_settings WHERE singleton_id=1",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) Triple(cursor.getInt(0) != 0, cursor.getInt(1), if (cursor.isNull(2)) null else cursor.getLong(2))
            else Triple(false, 0, null)
        }
        fun count(sql: String): Int = db.rawQuery(sql, null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        return PeopleIndexStatus(
            enabled = settings.first,
            consentVersion = settings.second,
            enabledAt = settings.third,
            faceInstanceCount = count("SELECT COUNT(*) FROM face_instance"),
            personClusterCount = count("SELECT COUNT(*) FROM person_cluster"),
            reviewedClusterCount = count("SELECT COUNT(*) FROM person_cluster WHERE reviewed=1 AND hidden=0"),
            identityReadyFaceCount = count(
                "SELECT COUNT(*) FROM face_instance f JOIN person_cluster p ON p.id=f.cluster_id " +
                    "WHERE p.reviewed=1 AND f.embedding_dimension>0 AND f.embedding_offset IS NOT NULL",
            ),
            pendingMediaCount = count("SELECT COUNT(*) FROM media_index_stage WHERE stage='FACES' AND status IN ('PENDING','RUNNING','FAILED_RETRYABLE')"),
        )
    }

    fun enablePeopleIndexing(consentVersion: Int): PeopleIndexStatus {
        require(consentVersion == PEOPLE_CONSENT_VERSION) { "Unsupported people-consent version" }
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.insertWithOnConflict("people_settings", null, ContentValues().apply {
                put("singleton_id", 1)
                put("enabled", 1)
                put("consent_version", consentVersion)
                put("enabled_at", now)
                put("updated_at", now)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.execSQL(
                "UPDATE media_index_stage SET status='PENDING',producer_version='mlkit-face-detection-v1',updated_at=$now,error=NULL " +
                    "WHERE stage='FACES' AND media_id IN (SELECT id FROM media_item WHERE media_kind='IMAGE' AND access_state='ACCESSIBLE' AND index_state='READY')",
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return peopleIndexStatus()
    }

    fun resetPeopleIndex(): PeopleIndexStatus {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.delete(
                "semantic_enrichment_job",
                "reason LIKE ? AND status<>?",
                arrayOf("${PersonalSemanticMemoryPolicy.JOB_PREFIX}%", SemanticEnrichmentStatus.COMPLETE.name),
            )
            db.delete("face_instance", null, null)
            db.delete("person_cluster", null, null)
            db.execSQL("UPDATE media_item SET face_count=0")
            db.execSQL("UPDATE media_index_stage SET status='SKIPPED',producer_version='disabled-until-opt-in',updated_at=$now,error=NULL WHERE stage='FACES'")
            db.insertWithOnConflict("people_settings", null, ContentValues().apply {
                put("singleton_id", 1)
                put("enabled", 0)
                put("consent_version", 0)
                putNull("enabled_at")
                put("updated_at", now)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return peopleIndexStatus()
    }

    fun saveReviewedPersonCluster(
        id: String,
        label: String,
        relationship: String?,
        aliases: List<String>,
        includeInPersonalSemanticMemory: Boolean? = null,
    ): PeopleIndexStatus {
        require(PERSON_ID.matches(id)) { "Invalid local person ID" }
        val safeLabel = label.trim().also { require(it.isNotBlank() && it.length <= 80) { "Invalid person label" } }
        val safeRelationship = relationship?.trim()?.takeIf(String::isNotBlank)?.also {
            require(it.length <= 80) { "Relationship is too long" }
        }
        val safeAliases = aliases.asSequence().map(String::trim).filter(String::isNotBlank).distinct().take(MAX_PERSON_ALIASES + 1).toList()
        require(safeAliases.size <= MAX_PERSON_ALIASES && safeAliases.all { it.length <= 80 }) { "Invalid person aliases" }
        val includeInPersonalMemory = includeInPersonalSemanticMemory
            ?: PersonalSemanticMemoryPolicy.defaultEnabled(safeRelationship)
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(peopleIndexEnabled(db)) { "People indexing is disabled" }
            val now = System.currentTimeMillis()
            db.insertWithOnConflict("person_cluster", null, ContentValues().apply {
                put("id", id)
                putNull("label")
                putNull("relationship")
                put("aliases", "[]")
                put("reviewed", 0)
                put("created_at", now)
                put("updated_at", now)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.update("person_cluster", ContentValues().apply {
                put("label", safeLabel)
                if (safeRelationship == null) putNull("relationship") else put("relationship", safeRelationship)
                put("aliases", JSONArray(safeAliases).toString())
                put("reviewed", 1)
                put("hidden", 0)
                put("include_in_personal_memory", if (includeInPersonalMemory) 1 else 0)
                put("updated_at", now)
            }, "id=?", arrayOf(id))
            if (safeRelationship.equals("me", ignoreCase = true)) {
                db.update(
                    "person_cluster",
                    ContentValues().apply { putNull("relationship"); put("updated_at", now) },
                    "id<>? AND reviewed=1 AND lower(relationship)='me'",
                    arrayOf(id),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return peopleIndexStatus()
    }

    fun facePendingItems(limit: Int): List<GalleryItem> {
        if (!peopleIndexStatus().enabled) return emptyList()
        return queryItems(
            "media_kind='IMAGE' AND access_state='ACCESSIBLE' AND index_state='READY' AND id IN " +
                "(SELECT media_id FROM media_index_stage WHERE stage='FACES' AND status IN ('PENDING','FAILED_RETRYABLE'))",
            null,
            "COALESCE(captured_at,0) DESC",
            limit.coerceIn(1, 100).toString(),
        )
    }

    fun markFaces(mediaId: String) {
        if (!peopleIndexStatus().enabled) return
        updateStage(writableDatabase, mediaId, IndexStage.FACES, StageStatus.RUNNING, "mlkit-face-detection-v1", incrementAttempt = true)
    }

    fun completeFaces(mediaId: String, detections: List<FaceDetectionRecord>, producerVersion: String) {
        require(detections.size <= MAX_FACES_PER_MEDIA) { "Too many face detections" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(peopleIndexEnabled(db)) { "People indexing was disabled" }
            db.delete("face_instance", "media_id=?", arrayOf(mediaId))
            val now = System.currentTimeMillis()
            detections.forEachIndexed { index, face ->
                require(face.left in 0f..1f && face.top in 0f..1f && face.right in 0f..1f && face.bottom in 0f..1f) {
                    "Face bounds must be normalized"
                }
                require(face.left < face.right && face.top < face.bottom) { "Face bounds are invalid" }
                db.insertOrThrow("face_instance", null, ContentValues().apply {
                    put("id", "$mediaId:$index")
                    put("media_id", mediaId)
                    put("left_pos", face.left)
                    put("top_pos", face.top)
                    put("right_pos", face.right)
                    put("bottom_pos", face.bottom)
                    put("quality", face.quality.coerceIn(0f, 1f))
                    putNull("embedding_offset")
                    put("embedding_dimension", 0)
                    putNull("cluster_id")
                    put("producer_version", producerVersion)
                    put("created_at", now)
                })
            }
            db.update("media_item", ContentValues().apply { put("face_count", detections.size) }, "id=?", arrayOf(mediaId))
            updateStage(db, mediaId, IndexStage.FACES, StageStatus.COMPLETE, producerVersion)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun completeEmbeddedFaces(
        mediaId: String,
        faces: List<FaceInstance>,
        clusterIds: List<String>,
        producerVersion: String,
    ) {
        require(faces.size == clusterIds.size && faces.size <= MAX_FACES_PER_MEDIA) { "Invalid embedded face batch" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(peopleIndexEnabled(db)) { "People indexing was disabled" }
            val correctedAssignments = db.rawQuery(
                "SELECT id,cluster_id FROM face_instance WHERE media_id=? AND user_corrected=1",
                arrayOf(mediaId),
            ).use { cursor ->
                buildMap<String, String?> {
                    while (cursor.moveToNext()) {
                        put(cursor.getString(0), if (cursor.isNull(1)) null else cursor.getString(1))
                    }
                }
            }
            db.delete("face_instance", "media_id=?", arrayOf(mediaId))
            val now = System.currentTimeMillis()
            faces.forEachIndexed { index, face ->
                require(face.bounds.size == 4 && face.bounds.all { it in 0f..1f }) { "Face bounds must be normalized" }
                require(face.bounds[0] < face.bounds[2] && face.bounds[1] < face.bounds[3]) { "Face bounds are invalid" }
                require(face.embedding.size == FaceModelCatalog.sface.embeddingDimension && face.embedding.all { it.isFinite() }) {
                    "Invalid SFace embedding"
                }
                db.insertOrThrow("face_instance", null, ContentValues().apply {
                    put("id", "$mediaId:$index")
                    put("media_id", mediaId)
                    put("left_pos", face.bounds[0])
                    put("top_pos", face.bounds[1])
                    put("right_pos", face.bounds[2])
                    put("bottom_pos", face.bounds[3])
                    put("quality", face.quality.coerceIn(0f, 1f))
                    put("embedding_offset", 0L)
                    put("embedding_dimension", face.embedding.size)
                    val faceId = "$mediaId:$index"
                    if (faceId in correctedAssignments) {
                        val correctedClusterId = correctedAssignments[faceId]
                        if (correctedClusterId == null) putNull("cluster_id") else put("cluster_id", correctedClusterId)
                        put("user_corrected", 1)
                    } else {
                        put("cluster_id", clusterIds[index])
                        put("user_corrected", 0)
                    }
                    put("producer_version", producerVersion)
                    put("created_at", now)
                })
            }
            db.update("media_item", ContentValues().apply { put("face_count", faces.size) }, "id=?", arrayOf(mediaId))
            updateStage(db, mediaId, IndexStage.FACES, StageStatus.COMPLETE, producerVersion)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun faceIdsForMedia(mediaId: String): List<String> = readableDatabase.rawQuery(
        "SELECT id FROM face_instance WHERE media_id=? ORDER BY id",
        arrayOf(mediaId),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun indexedPeopleForMedia(mediaId: String): List<IndexedPersonMetadata> = readableDatabase.rawQuery(
        """
        SELECT c.id,c.label,c.relationship,c.aliases,c.reviewed,c.hidden,COUNT(f.id) AS face_count
        FROM face_instance f
        JOIN person_cluster c ON c.id=f.cluster_id
        WHERE f.media_id=?
        GROUP BY c.id,c.label,c.relationship,c.aliases,c.reviewed,c.hidden
        ORDER BY c.reviewed DESC,COALESCE(c.label,c.relationship,c.id) COLLATE NOCASE
        """.trimIndent(),
        arrayOf(mediaId),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    IndexedPersonMetadata(
                        clusterId = cursor.getString(0),
                        label = if (cursor.isNull(1)) null else cursor.getString(1),
                        relationship = if (cursor.isNull(2)) null else cursor.getString(2),
                        aliases = decodeStrings(cursor.getString(3)).sorted(),
                        reviewed = cursor.getInt(4) != 0,
                        hidden = cursor.getInt(5) != 0,
                        faceCount = cursor.getInt(6),
                    ),
                )
            }
        }
    }

    fun allEmbeddedFaceIds(): Set<String> = readableDatabase.rawQuery(
        "SELECT id FROM face_instance WHERE embedding_dimension=? AND embedding_offset IS NOT NULL",
        arrayOf(FaceModelCatalog.sface.embeddingDimension.toString()),
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun markFaceEmbeddingAvailable(faceId: String, dimension: Int, producerVersion: String) {
        require(faceId.length in 3..240 && dimension == FaceModelCatalog.sface.embeddingDimension) {
            "Invalid repaired face embedding"
        }
        check(
            writableDatabase.update("face_instance", ContentValues().apply {
                put("embedding_offset", 0L)
                put("embedding_dimension", dimension)
                put("producer_version", producerVersion)
            }, "id=?", arrayOf(faceId)) == 1,
        ) { "Representative face is unavailable" }
    }

    fun clusterIdForFace(faceId: String): String? = readableDatabase.rawQuery(
        "SELECT cluster_id FROM face_instance WHERE id=?",
        arrayOf(faceId),
    ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null }

    fun faceClusterReferences(faceIds: List<String>): Map<String, FaceClusterReference> =
        faceIds.distinct().chunked(SQLITE_ID_CHUNK).flatMap { ids ->
            val placeholders = ids.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT f.id,c.id,c.reviewed,c.hidden,f.user_corrected FROM face_instance f " +
                    "JOIN person_cluster c ON c.id=f.cluster_id WHERE f.id IN ($placeholders)",
                ids.toTypedArray(),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            cursor.getString(0) to FaceClusterReference(
                                clusterId = cursor.getString(1),
                                reviewed = cursor.getInt(2) != 0,
                                hidden = cursor.getInt(3) != 0,
                                userCorrected = cursor.getInt(4) != 0,
                            ),
                        )
                    }
                }
            }
        }.toMap()

    fun faceClusterMemberships(clusterId: String): List<FaceClusterMembership> {
        require(PERSON_ID.matches(clusterId)) { "Invalid local person ID" }
        return readableDatabase.rawQuery(
            "SELECT id,user_corrected FROM face_instance WHERE cluster_id=? ORDER BY quality DESC,id",
            arrayOf(clusterId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(FaceClusterMembership(cursor.getString(0), cursor.getInt(1) != 0))
            }
        }
    }

    fun assignAutomaticFacesToReviewedCluster(clusterId: String, faceIds: Set<String>): Int {
        require(PERSON_ID.matches(clusterId)) { "Invalid reviewed person ID" }
        if (faceIds.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val targetIsReviewed = db.rawQuery(
                "SELECT reviewed=1 AND hidden=0 FROM person_cluster WHERE id=?",
                arrayOf(clusterId),
            ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) != 0 }
            check(targetIsReviewed) { "Reviewed person is unavailable" }
            val eligible = faceIds.chunked(SQLITE_ID_CHUNK).flatMap { ids ->
                val placeholders = ids.joinToString(",") { "?" }
                db.rawQuery(
                    "SELECT f.id,f.media_id,f.cluster_id FROM face_instance f " +
                        "LEFT JOIN person_cluster c ON c.id=f.cluster_id " +
                        "WHERE f.id IN ($placeholders) AND f.user_corrected=0 " +
                        "AND (f.cluster_id IS NULL OR (c.reviewed=0 AND c.hidden=0))",
                    ids.toTypedArray(),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(Triple(cursor.getString(0), cursor.getString(1), if (cursor.isNull(2)) null else cursor.getString(2)))
                        }
                    }
                }
            }
            eligible.map { it.first }.chunked(SQLITE_ID_CHUNK).forEach { ids ->
                val placeholders = ids.joinToString(",") { "?" }
                db.update(
                    "face_instance",
                    ContentValues().apply { put("cluster_id", clusterId) },
                    "id IN ($placeholders)",
                    ids.toTypedArray(),
                )
            }
            val now = System.currentTimeMillis()
            db.update("person_cluster", ContentValues().apply { put("updated_at", now) }, "id=?", arrayOf(clusterId))
            eligible.groupBy { it.third }.forEach { (sourceClusterId, movedFaces) ->
                if (sourceClusterId == null) return@forEach
                val movedIds = movedFaces.map { it.first }
                movedIds.chunked(SQLITE_ID_CHUNK).forEach { ids ->
                    val placeholders = ids.joinToString(",") { "?" }
                    db.update(
                        "person_cluster",
                        ContentValues().apply {
                            putNull("representative_face_id")
                            put("updated_at", now)
                        },
                        "id=? AND representative_face_id IN ($placeholders)",
                        arrayOf(sourceClusterId, *ids.toTypedArray()),
                    )
                }
                db.delete(
                    "person_cluster",
                    "id=? AND reviewed=0 AND NOT EXISTS (SELECT 1 FROM face_instance WHERE cluster_id=?)",
                    arrayOf(sourceClusterId, sourceClusterId),
                )
            }
            invalidatePersonalSemanticEvidence(db, eligible.mapTo(linkedSetOf()) { it.second })
            db.setTransactionSuccessful()
            eligible.size
        } finally {
            db.endTransaction()
        }
    }

    fun ensureAutomaticPersonCluster(id: String) {
        require(PERSON_ID.matches(id)) { "Invalid automatic cluster ID" }
        val now = System.currentTimeMillis()
        writableDatabase.insertWithOnConflict("person_cluster", null, ContentValues().apply {
            put("id", id)
            putNull("label")
            putNull("relationship")
            put("aliases", "[]")
            put("reviewed", 0)
            put("created_at", now)
            put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun requestFaceEmbeddingReindex(producerVersion: String) {
        if (!peopleIndexStatus().enabled) return
        val now = System.currentTimeMillis()
        writableDatabase.execSQL(
            "UPDATE media_index_stage SET status='PENDING',producer_version=?,updated_at=?,error=NULL " +
                "WHERE stage='FACES' AND media_id IN (SELECT id FROM media_item WHERE " +
                "media_kind='IMAGE' AND access_state='ACCESSIBLE' AND index_state='READY') " +
                "AND (producer_version IS NULL OR producer_version!=?)",
            arrayOf(producerVersion, now, producerVersion),
        )
    }

    fun mediaIdsForReviewedPeople(personIds: List<String>): Set<String> {
        if (personIds.isEmpty()) return emptySet()
        val db = readableDatabase
        val clusterSets = personIds.distinct().map { requested ->
            val needle = requested.trim().lowercase(Locale.ROOT)
            val clusterIds = db.rawQuery(
                "SELECT id,label,relationship,aliases FROM person_cluster WHERE reviewed=1 AND hidden=0",
                null,
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        val label = if (cursor.isNull(1)) null else cursor.getString(1)
                        val relationship = if (cursor.isNull(2)) null else cursor.getString(2)
                        val aliases = runCatching {
                            val json = JSONArray(cursor.getString(3))
                            List(json.length()) { json.getString(it) }
                        }.getOrDefault(emptyList())
                        if (listOfNotNull(id, label, relationship).plus(aliases).any { it.lowercase(Locale.ROOT) == needle }) add(id)
                    }
                }
            }
            if (clusterIds.isEmpty()) emptySet() else {
                val placeholders = clusterIds.joinToString(",") { "?" }
                db.rawQuery(
                    "SELECT DISTINCT media_id FROM face_instance WHERE cluster_id IN ($placeholders)",
                    clusterIds.toTypedArray(),
                ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            }
        }
        return clusterSets.reduceOrNull { current, next -> current intersect next }.orEmpty()
    }

    fun personClustersPendingReview(): List<PersonClusterReviewItem> =
        personClusterSummaries(includeHidden = false).filterNot(PersonClusterReviewItem::reviewed)

    fun personClusterSummaries(includeHidden: Boolean = false): List<PersonClusterReviewItem> {
        val summaries = readableDatabase.rawQuery(
            "SELECT c.id, c.label, c.relationship, c.aliases, COUNT(f.id) AS face_count, " +
                "COUNT(DISTINCT f.media_id) AS media_count, MAX(f.media_id) AS sample_media_id " +
                ", c.reviewed, c.hidden, c.representative_face_id, c.include_in_personal_memory " +
                "FROM person_cluster c LEFT JOIN face_instance f ON c.id = f.cluster_id " +
                (if (includeHidden) "" else "WHERE c.hidden = 0 ") +
                "GROUP BY c.id, c.label, c.relationship, c.aliases, c.reviewed, c.hidden, c.representative_face_id, c.include_in_personal_memory " +
                "ORDER BY c.hidden ASC, c.reviewed ASC, face_count DESC, c.updated_at DESC, c.id",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PersonClusterReviewItem(
                            id = cursor.getString(0),
                            label = if (cursor.isNull(1)) null else cursor.getString(1),
                            relationship = if (cursor.isNull(2)) null else cursor.getString(2),
                            aliases = runCatching {
                                val json = JSONArray(cursor.getString(3))
                                List(json.length()) { json.getString(it) }
                            }.getOrDefault(emptyList()),
                            faceCount = cursor.getInt(4),
                            mediaCount = cursor.getInt(5),
                            sampleMediaId = if (cursor.isNull(6)) null else cursor.getString(6),
                            reviewed = cursor.getInt(7) != 0,
                            hidden = cursor.getInt(8) != 0,
                            representativeFaceId = if (cursor.isNull(9)) null else cursor.getString(9),
                            includeInPersonalSemanticMemory = cursor.getInt(10) != 0,
                        ),
                    )
                }
            }
        }
        val visibleSummaries = summaries.filter { it.reviewed || it.mediaCount >= MIN_UNREVIEWED_PERSON_MEDIA }
        val facesByCluster = personFacesForClusters(
            visibleSummaries.mapTo(linkedSetOf(), PersonClusterReviewItem::id),
            limitPerCluster = 4,
        )
        return visibleSummaries.map { summary ->
            val faces = facesByCluster[summary.id].orEmpty()
            summary.copy(
                representativeFace = faces.firstOrNull { it.id == summary.representativeFaceId } ?: faces.firstOrNull(),
                supportingFaces = faces,
            )
        }
    }

    fun personFacesForCluster(clusterId: String, limit: Int = 60, offset: Int = 0): List<PersonFaceReviewItem> {
        require(PERSON_ID.matches(clusterId)) { "Invalid local person ID" }
        require(offset >= 0) { "Invalid face page offset" }
        val boundedLimit = limit.coerceIn(1, 200)
        data class PendingFace(
            val id: String,
            val mediaId: String,
            val left: Float,
            val top: Float,
            val right: Float,
            val bottom: Float,
            val quality: Float,
            val userCorrected: Boolean,
        )
        val pending = readableDatabase.rawQuery(
            "SELECT f.id,f.media_id,f.left_pos,f.top_pos,f.right_pos,f.bottom_pos,f.quality,f.user_corrected " +
                "FROM face_instance f JOIN media_item m ON m.id=f.media_id WHERE f.cluster_id=? " +
                "ORDER BY COALESCE(m.captured_at,m.modified_at,0) DESC, COALESCE(m.modified_at,0) DESC, " +
                "f.created_at DESC, f.quality DESC, f.id " +
                "LIMIT ? OFFSET ?",
            arrayOf(clusterId, boundedLimit.toString(), offset.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PendingFace(
                            id = cursor.getString(0),
                            mediaId = cursor.getString(1),
                            left = cursor.getFloat(2),
                            top = cursor.getFloat(3),
                            right = cursor.getFloat(4),
                            bottom = cursor.getFloat(5),
                            quality = cursor.getFloat(6),
                            userCorrected = cursor.getInt(7) != 0,
                        ),
                    )
                }
            }
        }
        val mediaById = pending.map(PendingFace::mediaId).distinct().chunked(SQLITE_ID_CHUNK).flatMap { ids ->
            val placeholders = ids.joinToString(",") { "?" }
            queryItems("id IN ($placeholders)", ids.toTypedArray(), null, null)
        }.associateBy(GalleryItem::id)
        return pending.mapNotNull { face ->
            val item = mediaById[face.mediaId] ?: return@mapNotNull null
            PersonFaceReviewItem(
                id = face.id,
                mediaId = face.mediaId,
                item = item,
                left = face.left,
                top = face.top,
                right = face.right,
                bottom = face.bottom,
                quality = face.quality,
                userCorrected = face.userCorrected,
            )
        }
    }

    fun personFace(faceId: String): PersonFaceReviewItem? {
        require(faceId.length in 3..240) { "Invalid face ID" }
        data class PendingFace(
            val mediaId: String,
            val left: Float,
            val top: Float,
            val right: Float,
            val bottom: Float,
            val quality: Float,
            val userCorrected: Boolean,
        )
        val face = readableDatabase.rawQuery(
            "SELECT media_id,left_pos,top_pos,right_pos,bottom_pos,quality,user_corrected FROM face_instance WHERE id=?",
            arrayOf(faceId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            PendingFace(
                mediaId = cursor.getString(0),
                left = cursor.getFloat(1),
                top = cursor.getFloat(2),
                right = cursor.getFloat(3),
                bottom = cursor.getFloat(4),
                quality = cursor.getFloat(5),
                userCorrected = cursor.getInt(6) != 0,
            )
        }
        val item = queryItems("id=?", arrayOf(face.mediaId), null, "1").singleOrNull() ?: return null
        return PersonFaceReviewItem(
            id = faceId,
            mediaId = face.mediaId,
            item = item,
            left = face.left,
            top = face.top,
            right = face.right,
            bottom = face.bottom,
            quality = face.quality,
            userCorrected = face.userCorrected,
        )
    }

    private fun personFacesForClusters(
        clusterIds: Set<String>,
        limitPerCluster: Int,
    ): Map<String, List<PersonFaceReviewItem>> {
        if (clusterIds.isEmpty()) return emptyMap()
        data class PendingFace(
            val id: String,
            val mediaId: String,
            val clusterId: String,
            val left: Float,
            val top: Float,
            val right: Float,
            val bottom: Float,
            val quality: Float,
            val userCorrected: Boolean,
        )

        val boundedLimit = limitPerCluster.coerceIn(1, 100)
        val pendingByCluster = linkedMapOf<String, MutableList<PendingFace>>()
        clusterIds.chunked(800).forEach { clusterChunk ->
            val placeholders = clusterChunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT f.id,f.media_id,f.cluster_id,f.left_pos,f.top_pos,f.right_pos,f.bottom_pos,f.quality,f.user_corrected " +
                    "FROM face_instance f JOIN person_cluster c ON c.id=f.cluster_id WHERE f.cluster_id IN ($placeholders) " +
                    "ORDER BY f.cluster_id,CASE WHEN f.id=c.representative_face_id THEN 0 ELSE 1 END,f.quality DESC,f.created_at DESC",
                clusterChunk.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val clusterId = cursor.getString(2)
                    val faces = pendingByCluster.getOrPut(clusterId) { mutableListOf() }
                    if (faces.size >= boundedLimit) continue
                    faces += PendingFace(
                        id = cursor.getString(0),
                        mediaId = cursor.getString(1),
                        clusterId = clusterId,
                        left = cursor.getFloat(3),
                        top = cursor.getFloat(4),
                        right = cursor.getFloat(5),
                        bottom = cursor.getFloat(6),
                        quality = cursor.getFloat(7),
                        userCorrected = cursor.getInt(8) != 0,
                    )
                }
            }
        }
        val mediaById = pendingByCluster.values.asSequence()
            .flatten()
            .map(PendingFace::mediaId)
            .distinct()
            .chunked(800)
            .flatMap { mediaChunk ->
                val placeholders = mediaChunk.joinToString(",") { "?" }
                queryItems(
                    selection = "id IN ($placeholders)",
                    args = mediaChunk.toTypedArray(),
                    order = null,
                    limit = null,
                ).asSequence()
            }
            .associateBy(GalleryItem::id)
        return pendingByCluster.mapValues { (_, faces) ->
            faces.mapNotNull { face ->
                val item = mediaById[face.mediaId] ?: return@mapNotNull null
                PersonFaceReviewItem(
                    id = face.id,
                    mediaId = face.mediaId,
                    item = item,
                    left = face.left,
                    top = face.top,
                    right = face.right,
                    bottom = face.bottom,
                    quality = face.quality,
                    userCorrected = face.userCorrected,
                )
            }
        }
    }

    fun setPersonClusterRepresentative(clusterId: String, faceId: String) {
        require(PERSON_ID.matches(clusterId) && faceId.length in 3..240) { "Invalid representative face" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val belongsToCluster = db.rawQuery(
                "SELECT 1 FROM face_instance WHERE id=? AND cluster_id=? LIMIT 1",
                arrayOf(faceId, clusterId),
            ).use(android.database.Cursor::moveToFirst)
            check(belongsToCluster) { "Representative face is not in this cluster" }
            db.update("person_cluster", ContentValues().apply {
                put("representative_face_id", faceId)
                put("updated_at", System.currentTimeMillis())
            }, "id=?", arrayOf(clusterId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun refineReviewedPersonCluster(clusterId: String, representativeFaceId: String, rejectedFaceIds: Set<String>): Int {
        require(PERSON_ID.matches(clusterId) && representativeFaceId.length in 3..240) { "Invalid cluster refinement" }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val clusterIsReviewed = db.rawQuery(
                "SELECT reviewed FROM person_cluster WHERE id=?",
                arrayOf(clusterId),
            ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) != 0 }
            check(clusterIsReviewed) { "Only a reviewed identity can be improved" }
            val representativeBelongs = db.rawQuery(
                "SELECT 1 FROM face_instance WHERE id=? AND cluster_id=? LIMIT 1",
                arrayOf(representativeFaceId, clusterId),
            ).use(android.database.Cursor::moveToFirst)
            check(representativeBelongs) { "Representative face is not in this cluster" }
            val now = System.currentTimeMillis()
            db.update("person_cluster", ContentValues().apply {
                put("representative_face_id", representativeFaceId)
                put("updated_at", now)
            }, "id=?", arrayOf(clusterId))
            var moved = 0
            if (rejectedFaceIds.isNotEmpty()) {
                val quarantineId = "person_${java.util.UUID.nameUUIDFromBytes(
                    "refinement:$clusterId:$representativeFaceId".toByteArray(),
                ).toString().replace("-", "")}"
                db.insertWithOnConflict("person_cluster", null, ContentValues().apply {
                    put("id", quarantineId)
                    putNull("label")
                    putNull("relationship")
                    put("aliases", "[]")
                    put("reviewed", 0)
                    put("hidden", 1)
                    put("created_at", now)
                    put("updated_at", now)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                rejectedFaceIds.chunked(SQLITE_ID_CHUNK).forEach { ids ->
                    val placeholders = ids.joinToString(",") { "?" }
                    val selectionArgs = (listOf(clusterId) + ids).toTypedArray()
                    val mediaIds = db.rawQuery(
                        "SELECT DISTINCT media_id FROM face_instance WHERE cluster_id=? AND user_corrected=0 AND id IN ($placeholders)",
                        selectionArgs,
                    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                    moved += db.update(
                        "face_instance",
                        ContentValues().apply { put("cluster_id", quarantineId) },
                        "cluster_id=? AND user_corrected=0 AND id IN ($placeholders)",
                        selectionArgs,
                    )
                    invalidatePersonalSemanticEvidence(db, mediaIds)
                }
                if (moved == 0) db.delete("person_cluster", "id=? AND reviewed=0", arrayOf(quarantineId))
            }
            db.setTransactionSuccessful()
            moved
        } finally {
            db.endTransaction()
        }
    }

    fun excludeFaceFromCluster(faceId: String): String {
        require(faceId.length in 3..240) { "Invalid face ID" }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val source = db.rawQuery(
                "SELECT cluster_id,media_id FROM face_instance WHERE id=?",
                arrayOf(faceId),
            ).use { cursor ->
                check(cursor.moveToFirst()) { "Face is unavailable" }
                check(!cursor.isNull(0)) { "Face is already excluded" }
                cursor.getString(0) to cursor.getString(1)
            }
            db.update("face_instance", ContentValues().apply {
                putNull("cluster_id")
                put("user_corrected", 1)
            }, "id=?", arrayOf(faceId))
            db.update("person_cluster", ContentValues().apply {
                putNull("representative_face_id")
                put("updated_at", System.currentTimeMillis())
            }, "id=? AND representative_face_id=?", arrayOf(source.first, faceId))
            invalidatePersonalSemanticEvidence(db, setOf(source.second))
            db.setTransactionSuccessful()
            source.first
        } finally {
            db.endTransaction()
        }
    }

    fun removePersonLabel(clusterId: String): PeopleIndexStatus {
        require(PERSON_ID.matches(clusterId)) { "Invalid local person ID" }
        val db = writableDatabase
        val affectedMediaIds = mediaIdsForClusters(db, setOf(clusterId))
        db.update("person_cluster", ContentValues().apply {
            putNull("label")
            putNull("relationship")
            put("aliases", "[]")
            put("reviewed", 0)
            put("include_in_personal_memory", 0)
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(clusterId))
        dropPendingPersonalJobs(db, affectedMediaIds)
        return peopleIndexStatus()
    }

    fun setPersonClusterHidden(clusterId: String, hidden: Boolean): PeopleIndexStatus {
        require(PERSON_ID.matches(clusterId)) { "Invalid local person ID" }
        val db = writableDatabase
        val affectedMediaIds = mediaIdsForClusters(db, setOf(clusterId))
        db.update("person_cluster", ContentValues().apply {
            put("hidden", if (hidden) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(clusterId))
        if (hidden) dropPendingPersonalJobs(db, affectedMediaIds)
        return peopleIndexStatus()
    }

    fun mergePersonClusters(targetClusterId: String, sourceClusterId: String): PeopleIndexStatus {
        require(PERSON_ID.matches(targetClusterId) && PERSON_ID.matches(sourceClusterId) && targetClusterId != sourceClusterId) {
            "Invalid cluster merge"
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(clusterExists(db, targetClusterId) && clusterExists(db, sourceClusterId)) { "Cluster merge target is unavailable" }
            val now = System.currentTimeMillis()
            data class ClusterIdentity(
                val label: String?,
                val relationship: String?,
                val aliases: List<String>,
                val representativeFaceId: String?,
                val includeInPersonalMemory: Boolean,
            )
            fun identity(clusterId: String): ClusterIdentity = db.rawQuery(
                "SELECT label,relationship,aliases,representative_face_id,include_in_personal_memory FROM person_cluster WHERE id=?",
                arrayOf(clusterId),
            ).use { cursor ->
                check(cursor.moveToFirst())
                ClusterIdentity(
                    label = if (cursor.isNull(0)) null else cursor.getString(0),
                    relationship = if (cursor.isNull(1)) null else cursor.getString(1),
                    aliases = runCatching {
                        val json = JSONArray(cursor.getString(2))
                        List(json.length()) { json.getString(it) }
                    }.getOrDefault(emptyList()),
                    representativeFaceId = if (cursor.isNull(3)) null else cursor.getString(3),
                    includeInPersonalMemory = cursor.getInt(4) != 0,
                )
            }
            val targetIdentity = identity(targetClusterId)
            val sourceIdentity = identity(sourceClusterId)
            val affectedMediaIds = db.rawQuery(
                "SELECT DISTINCT media_id FROM face_instance WHERE cluster_id IN (?,?)",
                arrayOf(targetClusterId, sourceClusterId),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            val aliases = (targetIdentity.aliases + sourceIdentity.aliases + listOfNotNull(sourceIdentity.label))
                .map(String::trim).filter(String::isNotBlank).distinct().take(MAX_PERSON_ALIASES)
            db.update("face_instance", ContentValues().apply {
                put("cluster_id", targetClusterId)
                put("user_corrected", 1)
            }, "cluster_id=?", arrayOf(sourceClusterId))
            db.update("person_attribute_fact", ContentValues().apply { put("cluster_id", targetClusterId) }, "cluster_id=?", arrayOf(sourceClusterId))
            db.delete("person_cluster", "id=?", arrayOf(sourceClusterId))
            db.update("person_cluster", ContentValues().apply {
                val label = targetIdentity.label ?: sourceIdentity.label
                val relationship = targetIdentity.relationship ?: sourceIdentity.relationship
                val representativeFaceId = targetIdentity.representativeFaceId ?: sourceIdentity.representativeFaceId
                if (label == null) putNull("label") else put("label", label)
                if (relationship == null) putNull("relationship") else put("relationship", relationship)
                if (representativeFaceId == null) putNull("representative_face_id") else put("representative_face_id", representativeFaceId)
                put("aliases", JSONArray(aliases).toString())
                put("reviewed", if (label != null) 1 else 0)
                put(
                    "include_in_personal_memory",
                    if (targetIdentity.includeInPersonalMemory || sourceIdentity.includeInPersonalMemory) 1 else 0,
                )
                put("updated_at", now)
            }, "id=?", arrayOf(targetClusterId))
            invalidatePersonalSemanticEvidence(db, affectedMediaIds)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return peopleIndexStatus()
    }

    fun moveFaceToCluster(faceId: String, requestedTargetClusterId: String? = null): String {
        require(faceId.length in 3..240) { "Invalid face ID" }
        val target = requestedTargetClusterId?.trim()?.takeIf(String::isNotBlank)
            ?: "person_${java.util.UUID.nameUUIDFromBytes("manual:$faceId:${System.currentTimeMillis()}".toByteArray()).toString().replace("-", "")}"
        require(PERSON_ID.matches(target)) { "Invalid target cluster ID" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val source = db.rawQuery("SELECT cluster_id,media_id FROM face_instance WHERE id=?", arrayOf(faceId)).use { cursor ->
                check(cursor.moveToFirst()) { "Face is unavailable" }
                (if (cursor.isNull(0)) null else cursor.getString(0)) to cursor.getString(1)
            }
            ensureAutomaticPersonCluster(target)
            db.update("face_instance", ContentValues().apply {
                put("cluster_id", target)
                put("user_corrected", 1)
            }, "id=?", arrayOf(faceId))
            invalidatePersonalSemanticEvidence(db, setOf(source.second))
            source.first?.let { sourceClusterId ->
                db.update("person_cluster", ContentValues().apply {
                    putNull("representative_face_id")
                    put("updated_at", System.currentTimeMillis())
                }, "id=? AND representative_face_id=?", arrayOf(sourceClusterId, faceId))
            }
            db.delete(
                "person_attribute_fact",
                "media_id=? AND cluster_id IN (?,?)",
                arrayOf(source.second, source.first.orEmpty(), target),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return target
    }

    fun resolveReviewedPersonIds(query: String): Set<String> =
        resolveReviewedPersonGroups(query).flatMapTo(linkedSetOf(), ReviewedPersonMatchGroup::personIds)

    internal fun resolveReviewedPersonGroups(query: String): List<ReviewedPersonMatchGroup> {
        val normalizedQuery = query.lowercase(Locale.ROOT)
        val candidates = readableDatabase.rawQuery(
            """
            SELECT c.id,c.label,c.relationship,c.aliases,
                (SELECT COUNT(*) FROM face_instance f WHERE f.cluster_id=c.id) AS face_count,
                c.updated_at
            FROM person_cluster c
            WHERE c.reviewed=1 AND c.hidden=0
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val terms = buildList {
                        if (!cursor.isNull(1)) add(cursor.getString(1))
                        if (!cursor.isNull(2)) add(cursor.getString(2))
                        val aliases = runCatching {
                            val json = JSONArray(cursor.getString(3))
                            List(json.length()) { json.getString(it) }
                        }.getOrDefault(emptyList())
                        addAll(aliases)
                    }
                    val matchedTerms = terms.filter { identityTermMatches(normalizedQuery, it) }
                    if (matchedTerms.isNotEmpty()) {
                        add(
                            ReviewedPersonMatchCandidate(
                                personId = id,
                                matchedIdentityTerms = matchedTerms,
                                faceCount = cursor.getInt(4),
                                updatedAt = cursor.getLong(5),
                            ),
                        )
                    }
                }
            }
        }
        return ReviewedPersonMatchSelector.group(candidates)
    }

    fun reviewedFaceBindings(mediaId: String, requestedPeople: Set<String>): List<PersonVerificationBinding> {
        if (requestedPeople.isEmpty()) return emptyList()
        val resolved = requestedPeople.flatMap { requested ->
            resolveReviewedPersonIds(requested).ifEmpty {
                if (PERSON_ID.matches(requested)) setOf(requested) else emptySet()
            }
        }.distinct()
        if (resolved.isEmpty()) return emptyList()
        val placeholders = resolved.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT f.id,f.cluster_id,f.left_pos,f.top_pos,f.right_pos,f.bottom_pos,p.label,p.relationship,p.aliases " +
                "FROM face_instance f JOIN person_cluster p ON p.id=f.cluster_id " +
                "WHERE f.media_id=? AND p.reviewed=1 AND p.hidden=0 AND f.cluster_id IN ($placeholders) " +
                "ORDER BY f.cluster_id,f.quality DESC,f.id",
            (listOf(mediaId) + resolved).toTypedArray(),
        ).use { cursor ->
            val stable = resolved.withIndex().associate { (index, id) -> id to "P${index + 1}" }
            buildList {
                while (cursor.moveToNext()) {
                    val clusterId = cursor.getString(1)
                    val aliases = runCatching {
                        val json = JSONArray(cursor.getString(8))
                        List(json.length()) { json.getString(it) }
                    }.getOrDefault(emptyList())
                    add(
                        PersonVerificationBinding(
                            faceId = cursor.getString(0),
                            clusterId = clusterId,
                            stableLabel = stable.getValue(clusterId),
                            identityTerms = setOfNotNull(
                                if (cursor.isNull(6)) null else cursor.getString(6),
                                if (cursor.isNull(7)) null else cursor.getString(7),
                            ) + aliases,
                            left = cursor.getFloat(2),
                            top = cursor.getFloat(3),
                            right = cursor.getFloat(4),
                            bottom = cursor.getFloat(5),
                        ),
                    )
                }
            }
        }
    }

    fun reviewedFaceBindingsForMedia(mediaId: String): List<PersonVerificationBinding> {
        val clusterIds = readableDatabase.rawQuery(
            "SELECT DISTINCT f.cluster_id FROM face_instance f JOIN person_cluster p ON p.id=f.cluster_id " +
                "WHERE f.media_id=? AND p.reviewed=1 AND p.hidden=0 ORDER BY f.cluster_id",
            arrayOf(mediaId),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return reviewedFaceBindings(mediaId, clusterIds.toSet()).distinctBy(PersonVerificationBinding::clusterId)
    }

    fun reviewedPersonClusterIdsByMedia(): Map<String, Set<String>> = readableDatabase.rawQuery(
        "SELECT f.media_id,f.cluster_id FROM face_instance f JOIN person_cluster p ON p.id=f.cluster_id " +
            "WHERE p.reviewed=1 AND p.hidden=0 ORDER BY f.media_id,f.cluster_id",
        emptyArray(),
    ).use { cursor ->
        buildMap<String, MutableSet<String>> {
            while (cursor.moveToNext()) getOrPut(cursor.getString(0), ::linkedSetOf).add(cursor.getString(1))
        }
    }

    fun saveVerifiedPersonAttributeFact(
        mediaId: String,
        clusterId: String,
        predicate: String,
        value: String,
        confidence: Float,
        region: List<Float>,
        modelVersion: String,
    ) {
        require(PERSON_ID.matches(clusterId) && predicate.isNotBlank() && value.isNotBlank()) { "Invalid person fact" }
        val id = StableRecordId.of(
            "person_attribute_fact",
            mediaId,
            clusterId,
            predicate.lowercase(Locale.ROOT),
            value,
        )
        writableDatabase.insertWithOnConflict("person_attribute_fact", null, ContentValues().apply {
            put("id", id)
            put("media_id", mediaId)
            put("cluster_id", clusterId)
            put("predicate", predicate.take(240))
            put("value", value.take(240))
            put("confidence", confidence.coerceIn(0f, 1f))
            put("region", JSONArray(region).toString())
            put("model_version", modelVersion.take(160))
            put("person_ref", "")
            put("relation", PersonVisualRelation.ACTION.name)
            put("category", WornItemCategory.OTHER_WORN_ITEM.name)
            put("attributes", "{}")
            put("body_region", BodyRegion.UNKNOWN.name)
            put("face_region", JSONArray(region).toString())
            put("association_status", PersonAssociationStatus.CONFIDENT.name)
            put("verdict", PersonVisualVerdict.VERIFIED_TRUE.name)
            put("prompt_version", "query-visual-verification-v2")
            put("updated_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun failFaces(mediaId: String, message: String, permanent: Boolean) {
        if (!peopleIndexStatus().enabled) return
        updateStage(
            writableDatabase,
            mediaId,
            IndexStage.FACES,
            if (permanent) StageStatus.FAILED_PERMANENT else StageStatus.FAILED_RETRYABLE,
            "mlkit-face-detection-v1",
            error = message,
        )
    }

    fun stageRecords(mediaId: String): List<MediaIndexStageRecord> = readableDatabase.query(
        "media_index_stage", null, "media_id=?", arrayOf(mediaId), null, null, "stage",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                MediaIndexStageRecord(
                    mediaId = cursor.getString(cursor.getColumnIndexOrThrow("media_id")),
                    stage = IndexStage.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("stage"))),
                    status = StageStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
                    producerVersion = cursor.getString(cursor.getColumnIndexOrThrow("producer_version")),
                    attemptCount = cursor.getInt(cursor.getColumnIndexOrThrow("attempt_count")),
                    updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                    error = cursor.getColumnIndexOrThrow("error").let { if (cursor.isNull(it)) null else cursor.getString(it) },
                ),
            )
        }
    }

    fun itemById(id: String): GalleryItem? = itemById(readableDatabase, id)

    fun ocrBlocks(mediaId: String): List<OcrBlockRecord> = readableDatabase.query(
        "ocr_block", null, "media_id=?", arrayOf(mediaId), null, null, "id",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                OcrBlockRecord(
                    text = cursor.getString(cursor.getColumnIndexOrThrow("text")),
                    normalizedText = cursor.getString(cursor.getColumnIndexOrThrow("normalized_text")),
                    language = cursor.getColumnIndexOrThrow("language").let { if (cursor.isNull(it)) null else cursor.getString(it) },
                    pageIndex = cursor.getInt(cursor.getColumnIndexOrThrow("page_index")),
                    timestampMs = cursor.getColumnIndexOrThrow("timestamp_ms").let { if (cursor.isNull(it)) null else cursor.getLong(it) },
                    confidence = cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
                    left = cursor.getFloat(cursor.getColumnIndexOrThrow("left_pos")),
                    top = cursor.getFloat(cursor.getColumnIndexOrThrow("top_pos")),
                    right = cursor.getFloat(cursor.getColumnIndexOrThrow("right_pos")),
                    bottom = cursor.getFloat(cursor.getColumnIndexOrThrow("bottom_pos")),
                ),
            )
        }
    }

    fun ocrEntities(mediaId: String, type: OcrEntityType? = null): List<OcrEntityRecord> = readableDatabase.query(
        "ocr_entity",
        null,
        if (type == null) "media_id=?" else "media_id=? AND entity_type=?",
        if (type == null) arrayOf(mediaId) else arrayOf(mediaId, type.name),
        null,
        null,
        "confidence DESC, id",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                OcrEntityRecord(
                    type = OcrEntityType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("entity_type"))),
                    rawText = cursor.getString(cursor.getColumnIndexOrThrow("raw_text")),
                    normalizedValue = cursor.getString(cursor.getColumnIndexOrThrow("normalized_value")),
                    label = cursor.getColumnIndexOrThrow("label").let { if (cursor.isNull(it)) null else cursor.getString(it) },
                    confidence = cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
                    left = cursor.getFloat(cursor.getColumnIndexOrThrow("left_pos")),
                    top = cursor.getFloat(cursor.getColumnIndexOrThrow("top_pos")),
                    right = cursor.getFloat(cursor.getColumnIndexOrThrow("right_pos")),
                    bottom = cursor.getFloat(cursor.getColumnIndexOrThrow("bottom_pos")),
                    producerVersion = cursor.getString(cursor.getColumnIndexOrThrow("producer_version")),
                ),
            )
        }
    }

    fun fullTextMatches(terms: List<String>, limit: Int = 500): Set<String> {
        val safe = terms.map { it.replace(Regex("[^\\p{L}\\p{N}]+"), "").trim() }.filter(String::isNotBlank)
        if (safe.isEmpty()) return emptySet()
        val expression = safe.joinToString(" OR ") { "\"$it\"" }
        return runCatching {
            readableDatabase.rawQuery(
                "SELECT media_id FROM media_fts WHERE media_fts MATCH ? LIMIT ?",
                arrayOf(expression, limit.toString()),
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        }.getOrDefault(emptySet())
    }

    fun rebuildEvents() {
        val ready = queryItems("index_state='READY' AND captured_at IS NOT NULL", null, "captured_at", null)
        val compiled = EventCompiler.compile(ready, eventCorrections())
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("event_media", null, null)
            db.delete("gallery_event", null, null)
            compiled.forEach { event ->
                db.insertOrThrow("gallery_event", null, ContentValues().apply {
                    put("id", event.id)
                    put("start_time", event.startTime)
                    put("end_time", event.endTime)
                    put("title", event.title)
                    put("location_name", event.locationName)
                    put("latitude", event.latitude)
                    put("longitude", event.longitude)
                    put("event_type", event.eventType)
                    put("member_count", event.members.size)
                    put("confidence", event.confidence)
                    put("search_text", event.searchText)
                    put("representative_media_id", event.representativeMediaId)
                    put("producer_version", event.producerVersion)
                    put("user_corrected", event.userCorrected)
                })
                event.members.forEach { member ->
                    db.insert("event_media", null, ContentValues().apply {
                        put("event_id", event.id)
                        put("media_id", member.id)
                    })
                }
            }
            ready.forEach { updateStage(db, it.id, IndexStage.EVENTS, StageStatus.COMPLETE, EventCompiler.PRODUCER_VERSION) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun events(): List<EventRecord> = readableDatabase.query("gallery_event", null, null, null, null, null, "start_time DESC").use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                EventRecord(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    startTime = cursor.getLong(cursor.getColumnIndexOrThrow("start_time")),
                    endTime = cursor.getLong(cursor.getColumnIndexOrThrow("end_time")),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    locationName = cursor.getStringOrNull("location_name"),
                    latitude = cursor.getDoubleOrNull("latitude"),
                    longitude = cursor.getDoubleOrNull("longitude"),
                    eventType = cursor.getString(cursor.getColumnIndexOrThrow("event_type")),
                    memberCount = cursor.getInt(cursor.getColumnIndexOrThrow("member_count")),
                    confidence = cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
                    searchText = cursor.getString(cursor.getColumnIndexOrThrow("search_text")),
                    representativeMediaId = cursor.getStringOrNull("representative_media_id"),
                    producerVersion = cursor.getString(cursor.getColumnIndexOrThrow("producer_version")),
                    userCorrected = cursor.getInt(cursor.getColumnIndexOrThrow("user_corrected")) != 0,
                ),
            )
        }
    }

    fun eventCorrections(): List<EventCorrectionRecord> = readableDatabase.query(
        "event_correction", null, null, null, null, null, "created_at,id",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val operation = runCatching { EventCorrectionOperation.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("operation"))) }
                    .getOrNull() ?: continue
                add(EventCorrectionRecord(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    operation = operation,
                    mediaIds = decodeStrings(cursor.getString(cursor.getColumnIndexOrThrow("media_ids"))),
                    title = cursor.getStringOrNull("title"),
                    locationName = cursor.getStringOrNull("location_name"),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                ))
            }
        }
    }

    fun saveEventCorrection(
        operation: EventCorrectionOperation,
        mediaIds: Set<String>,
        title: String? = null,
        locationName: String? = null,
    ): EventCorrectionRecord {
        require(mediaIds.isNotEmpty() && mediaIds.size <= MAX_EVENT_CORRECTION_MEDIA) { "Event correction requires 1..$MAX_EVENT_CORRECTION_MEDIA media IDs" }
        require(mediaIds.all { it in accessibleIds() }) { "Event correction contains inaccessible or unknown media" }
        val safeTitle = title?.trim()?.take(MAX_EVENT_LABEL_LENGTH)
        val safeLocation = locationName?.trim()?.take(MAX_EVENT_LABEL_LENGTH)
        if (operation == EventCorrectionOperation.RENAME) require(!safeTitle.isNullOrBlank()) { "Rename correction requires a title" }
        if (operation == EventCorrectionOperation.LOCATION) require(!safeLocation.isNullOrBlank()) { "Location correction requires a location" }
        val createdAt = System.currentTimeMillis()
        val id = writableDatabase.insertOrThrow("event_correction", null, ContentValues().apply {
            put("operation", operation.name)
            put("media_ids", encode(mediaIds.sorted()))
            put("title", safeTitle)
            put("location_name", safeLocation)
            put("created_at", createdAt)
        })
        rebuildEvents()
        return EventCorrectionRecord(id, operation, mediaIds, safeTitle, safeLocation, createdAt)
    }

    fun eventMembers(eventId: Long): List<String> = readableDatabase.rawQuery(
        "SELECT media_id FROM event_media WHERE event_id=? ORDER BY media_id", arrayOf(eventId.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun eventsForMedia(mediaIds: Collection<String>): Map<String, EventRecord> {
        if (mediaIds.isEmpty()) return emptyMap()
        val wanted = mediaIds.toSet()
        val byId = events().associateBy { it.id }
        return eventMembership().mapNotNull { (mediaId, eventId) ->
            if (mediaId in wanted) byId[eventId]?.let { mediaId to it } else null
        }.toMap()
    }

    fun searchEvents(terms: List<String>, allowedIds: Set<String>? = null): List<EventSearchHit> {
        val normalized = terms.map { it.trim().lowercase() }.filter { it.length > 1 }.distinct()
        if (normalized.isEmpty()) return emptyList()
        return events().mapNotNull { event ->
            val haystack = "${event.title} ${event.locationName.orEmpty()} ${event.searchText}".lowercase()
            val matched = normalized.count { it in haystack }
            if (matched == 0) return@mapNotNull null
            val members = eventMembers(event.id).filter { allowedIds == null || it in allowedIds }
            if (members.isEmpty()) return@mapNotNull null
            EventSearchHit(event, members, matched.toDouble() / normalized.size + if (normalized.all { it in event.title.lowercase() }) 0.25 else 0.0)
        }.sortedWith(compareByDescending<EventSearchHit> { it.score }.thenByDescending { it.event.startTime })
    }

    fun eventMembership(): Map<String, Long> = readableDatabase.rawQuery(
        "SELECT media_id,event_id FROM event_media", null,
    ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1)) } }

    fun summary(): IndexSummary {
        val mediaCounts = readableDatabase.rawQuery(
            """
            SELECT
                COUNT(*),
                SUM(CASE WHEN source_kind='DEMO_ASSET' OR index_state='READY' THEN 1 ELSE 0 END),
                SUM(CASE WHEN tags IS NOT NULL AND TRIM(tags) NOT IN ('', '[]') THEN 1 ELSE 0 END),
                SUM(CASE WHEN index_state IN ('PENDING', 'INDEXING') THEN 1 ELSE 0 END),
                SUM(CASE WHEN index_state IN ('FAILED_PERMANENT', 'FAILED_RETRYABLE') THEN 1 ELSE 0 END),
                SUM(CASE WHEN media_kind='IMAGE' AND access_state='ACCESSIBLE' AND index_state='READY' THEN 1 ELSE 0 END)
            FROM media_item
            """.trimIndent(),
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) IntArray(6) else IntArray(6) { cursor.getInt(it) }
        }
        return IndexSummary(
            discovered = mediaCounts[0],
            metadataReady = mediaCounts[0],
            semanticFactsReady = readableDatabase.rawQuery(
                "SELECT COUNT(DISTINCT evidence_media_id) FROM semantic_fact",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 },
            ocrReady = mediaCounts[1],
            visualLabelsReady = mediaCounts[2],
            siglipVectorsReady = readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM media_index_stage WHERE stage='EMBEDDING' AND status='COMPLETE'",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 },
            videoKeyframesReady = readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM video_keyframe", null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 },
            facesScanned = readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM media_index_stage s JOIN media_item m ON m.id=s.media_id " +
                    "WHERE s.stage='FACES' AND s.status='COMPLETE' AND m.media_kind='IMAGE' " +
                    "AND m.access_state='ACCESSIBLE' AND m.index_state='READY'",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 },
            faceEligible = mediaCounts[5],
            pending = mediaCounts[3],
            events = events().size,
            failed = mediaCounts[4],
            storageBytes = databaseBytes(),
        )
    }

    fun indexCoverageForContentUris(contentUris: Collection<String>): ScopedIndexCoverage {
        val distinctUris = contentUris.filter(String::isNotBlank).distinct()
        val indexStates = IndexState.entries.associateWith { 0 }.toMutableMap()
        val stageStatuses = IndexStage.entries.associateWith {
            StageStatus.entries.associateWith { 0 }.toMutableMap()
        }.toMutableMap()
        distinctUris.chunked(400).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT index_state,COUNT(*) FROM media_item WHERE content_uri IN ($placeholders) GROUP BY index_state",
                chunk.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val state = IndexState.valueOf(cursor.getString(0))
                    indexStates[state] = indexStates.getValue(state) + cursor.getInt(1)
                }
            }
            readableDatabase.rawQuery(
                """SELECT s.stage,s.status,COUNT(*) FROM media_index_stage s
                   JOIN media_item m ON m.id=s.media_id
                   WHERE m.content_uri IN ($placeholders)
                   GROUP BY s.stage,s.status""".trimIndent(),
                chunk.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val stage = IndexStage.valueOf(cursor.getString(0))
                    val status = StageStatus.valueOf(cursor.getString(1))
                    val counts = stageStatuses.getValue(stage)
                    counts[status] = counts.getValue(status) + cursor.getInt(2)
                }
            }
        }
        return ScopedIndexCoverage(
            mediaCount = indexStates.values.sum(),
            indexStates = indexStates.toMap(),
            stageStatuses = stageStatuses.mapValues { it.value.toMap() },
        )
    }

    fun recordQuery(outcome: SearchOutcome, sessionId: String? = null) {
        writableDatabase.insert("query_turn", null, ContentValues().apply {
            put("query", outcome.plan.originalQuery)
            val channels = outcome.channelReports.joinToString(",") { report ->
                "${report.channel}:${report.status}:${report.searchedCount}/${report.eligibleCount}"
            }
            put("plan_summary", "${outcome.plan.intent}:${outcome.plan.terms.joinToString(",")}|channels=$channels")
            put("result_count", outcome.hits.size)
            put("elapsed_ms", outcome.elapsedMs)
            put("created_at", System.currentTimeMillis())
            put("session_id", sessionId)
            put("result_set_id", outcome.resultSetId)
            put("base_result_set_id", outcome.baseResultSetId)
            put("plan_patch_summary", outcome.planPatch?.changedFields?.sorted()?.joinToString(","))
        })
    }

    fun conversationState(sessionId: String): ConversationSearchState {
        requireSessionId(sessionId)
        val session = readableDatabase.query(
            "query_session", null, "session_id=?", arrayOf(sessionId), null, null, null, "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return ConversationSearchState(sessionId)
            val active = cursor.nullableText("active_result_set_id")
            ConversationSearchState(
                sessionId = sessionId,
                activeResultSetId = active,
                activeResultIds = active?.let(::resultSetMediaIds).orEmpty(),
                lastQuery = cursor.nullableText("last_query"),
                referencedPeople = decodeStrings(cursor.text("referenced_people")),
                referencedEvents = decodeLongs(cursor.text("referenced_events")),
                currentTimeScope = if (cursor.isNull(cursor.getColumnIndexOrThrow("time_start")) && cursor.isNull(cursor.getColumnIndexOrThrow("time_end"))) {
                    null
                } else {
                    FilterExpression.TimeRange(cursor.nullableLong("time_start"), cursor.nullableLong("time_end"))
                },
                currentPlaceScope = decodeStrings(cursor.text("place_scope")),
                grouping = runCatching { Grouping.valueOf(cursor.text("grouping")) }.getOrDefault(Grouping.NONE),
                lastEvidenceIds = decodeStrings(cursor.text("last_evidence_ids")).toList(),
            )
        }
        return session
    }

    fun resultSetParent(resultSetId: String): String? = readableDatabase.query(
        "result_set", arrayOf("parent_result_set_id"), "id=?", arrayOf(resultSetId), null, null, null, "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.nullableText("parent_result_set_id") else null }

    /** Atomically advances one conversation. A stale concurrent follow-up cannot replace the active branch. */
    fun persistResultSet(
        sessionId: String,
        outcome: SearchOutcome,
        expectedParentResultSetId: String?,
    ): SearchOutcome {
        requireSessionId(sessionId)
        val resultSetId = "rs_${UUID.randomUUID().toString().replace("-", "")}"
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = db.query(
                "query_session", arrayOf("active_result_set_id"), "session_id=?", arrayOf(sessionId), null, null, null, "1",
            ).use { cursor -> if (cursor.moveToFirst()) cursor.nullableText("active_result_set_id") else null }
            if (expectedParentResultSetId != null) {
                check(current == expectedParentResultSetId) { "Conversation changed while follow-up was running" }
            }
            db.insertOrThrow("result_set", null, ContentValues().apply {
                put("id", resultSetId)
                put("session_id", sessionId)
                put("parent_result_set_id", expectedParentResultSetId)
                put("query", outcome.plan.originalQuery.take(2_000))
                put("intent", outcome.plan.intent.name)
                put("exactness", outcome.answer.exactness.name)
                put("created_at", now)
            })
            outcome.hits.distinctBy { it.item.id }.forEachIndexed { rank, hit ->
                db.insertOrThrow("result_set_media", null, ContentValues().apply {
                    put("result_set_id", resultSetId)
                    put("media_id", hit.item.id)
                    put("rank", rank)
                    put("score", hit.score)
                })
            }
            val timeRange = outcome.plan.filter.firstTimeRange()
            val eventIds = outcome.hits.mapNotNull { eventMembership()[it.item.id] }.toSet()
            db.insertWithOnConflict("query_session", null, ContentValues().apply {
                put("session_id", sessionId)
                put("active_result_set_id", resultSetId)
                put("last_query", outcome.plan.originalQuery.take(2_000))
                put("referenced_people", encode(outcome.plan.peopleClauses.map { it.personId }))
                put("referenced_events", encode(eventIds))
                put("time_start", timeRange?.startEpochMs)
                put("time_end", timeRange?.endEpochMs)
                put("place_scope", encode(listOfNotNull(outcome.plan.place)))
                put("grouping", outcome.plan.grouping.name)
                put("last_evidence_ids", encode(outcome.answer.evidenceIds.take(32)))
                put("updated_at", now)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.execSQL(
                "DELETE FROM result_set WHERE session_id=? AND id NOT IN (SELECT id FROM result_set WHERE session_id=? ORDER BY created_at DESC LIMIT ?)",
                arrayOf<Any?>(sessionId, sessionId, MAX_RESULT_SETS_PER_SESSION),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return outcome.copy(resultSetId = resultSetId, baseResultSetId = expectedParentResultSetId)
    }

    fun databaseBytes(): Long = context.getDatabasePath(databaseName).length()

    fun close() = room.close()

    private fun insertOrReplace(db: GallerySqlDatabase, item: GalleryItem) {
        db.insertWithOnConflict("media_item", null, values(item), SQLiteDatabase.CONFLICT_REPLACE)
        refreshFts(db, item)
    }

    private fun refreshFts(db: GallerySqlDatabase, item: GalleryItem) {
        db.delete("media_fts", "media_id=?", arrayOf(item.id))
        db.insert("media_fts", null, ContentValues().apply {
            put("media_id", item.id)
            put("title", item.title)
            put("location", item.location)
            put("tags", item.tags.joinToString(" "))
            put("description", item.description)
            put("ocr_text", item.ocrText)
        })
    }

    private fun values(item: GalleryItem) = ContentValues().apply {
        put("id", item.id)
        put("filename", item.filename)
        put("title", item.title)
        put("creator", item.creator)
        put("location", item.location)
        put("album", item.album)
        put("latitude", item.latitude)
        put("longitude", item.longitude)
        put("tags", item.tags.joinToString(TAG_SEPARATOR))
        put("description", item.description)
        put("license", item.license)
        put("source_url", item.sourceUrl)
        put("asset_path", item.assetPath)
        put("content_uri", item.contentUri)
        put("preview_path", item.previewPath)
        put("source_kind", item.source.name)
        put("media_kind", item.kind.name)
        put("mime_type", item.mimeType)
        put("captured_at", item.capturedAt)
        put("modified_at", item.modifiedAt)
        put("duration_ms", item.durationMs)
        put("width", item.width)
        put("height", item.height)
        put("size_bytes", item.sizeBytes)
        put("ocr_text", item.ocrText)
        put("face_count", item.faceCount)
        put("index_state", item.indexState.name)
        put("index_error", item.indexError)
        put("index_version", if (item.source == MediaSource.DEMO_ASSET) "demo-sidecar-v1" else "pending")
        put("access_state", item.accessState.name)
        put("last_seen_at", item.lastSeenAt)
        put("perceptual_hash", item.perceptualHash?.let { java.lang.Long.toUnsignedString(it, 16) })
        put("exact_content_digest", item.exactContentDigest)
        put("blur_score", item.blurScore)
        put("exposure_score", item.exposureScore)
        put("quality_score", item.qualityScore)
    }

    private fun allItems(db: GallerySqlDatabase): List<GalleryItem> = queryItems(
        db = db,
        selection = "access_state=?",
        args = arrayOf(MediaAccessState.ACCESSIBLE.name),
        order = "COALESCE(captured_at,0) DESC, title COLLATE NOCASE",
    )

    private fun queryItems(selection: String?, args: Array<String>?, order: String?, limit: String?): List<GalleryItem> =
        queryItems(readableDatabase, selection, args, order, limit)

    private fun queryItems(
        db: GallerySqlDatabase,
        selection: String? = null,
        args: Array<String>? = null,
        order: String? = null,
        limit: String? = null,
    ): List<GalleryItem> = db.query("media_item", null, selection, args, null, null, order, limit).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursorItem(cursor))
        }
    }

    private fun itemById(db: GallerySqlDatabase, id: String): GalleryItem? = db.query(
        "media_item", null, "id=?", arrayOf(id), null, null, null, "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursorItem(cursor) else null }

    private fun cursorItem(cursor: android.database.Cursor): GalleryItem {
        fun text(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
        fun nullableText(name: String) = cursor.getColumnIndexOrThrow(name).let { if (cursor.isNull(it)) null else cursor.getString(it) }
        fun nullableDouble(name: String) = cursor.getColumnIndexOrThrow(name).let { if (cursor.isNull(it)) null else cursor.getDouble(it) }
        fun nullableLong(name: String) = cursor.getColumnIndexOrThrow(name).let { if (cursor.isNull(it)) null else cursor.getLong(it) }
        return GalleryItem(
            id = text("id"),
            filename = text("filename"),
            title = text("title"),
            creator = nullableText("creator"),
            location = text("location"),
            album = text("album"),
            latitude = nullableDouble("latitude"),
            longitude = nullableDouble("longitude"),
            tags = text("tags").split(TAG_SEPARATOR).filter(String::isNotBlank),
            description = text("description"),
            license = text("license"),
            sourceUrl = text("source_url"),
            assetPath = nullableText("asset_path"),
            contentUri = nullableText("content_uri"),
            previewPath = nullableText("preview_path"),
            source = runCatching { MediaSource.valueOf(text("source_kind")) }.getOrDefault(MediaSource.DEMO_ASSET),
            kind = runCatching { MediaKind.valueOf(text("media_kind")) }.getOrDefault(MediaKind.IMAGE),
            mimeType = text("mime_type"),
            capturedAt = nullableLong("captured_at"),
            modifiedAt = nullableLong("modified_at"),
            durationMs = nullableLong("duration_ms"),
            width = cursor.getInt(cursor.getColumnIndexOrThrow("width")),
            height = cursor.getInt(cursor.getColumnIndexOrThrow("height")),
            sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow("size_bytes")),
            ocrText = text("ocr_text"),
            faceCount = cursor.getInt(cursor.getColumnIndexOrThrow("face_count")),
            indexState = runCatching { IndexState.valueOf(text("index_state")) }.getOrDefault(IndexState.PENDING),
            indexError = nullableText("index_error"),
            accessState = runCatching { MediaAccessState.valueOf(text("access_state")) }.getOrDefault(MediaAccessState.ACCESSIBLE),
            lastSeenAt = nullableLong("last_seen_at"),
            perceptualHash = nullableText("perceptual_hash")?.let { java.lang.Long.parseUnsignedLong(it, 16) },
            exactContentDigest = nullableText("exact_content_digest"),
            blurScore = cursor.getColumnIndexOrThrow("blur_score").let { if (cursor.isNull(it)) null else cursor.getFloat(it) },
            exposureScore = cursor.getColumnIndexOrThrow("exposure_score").let { if (cursor.isNull(it)) null else cursor.getFloat(it) },
            qualityScore = cursor.getColumnIndexOrThrow("quality_score").let { if (cursor.isNull(it)) null else cursor.getFloat(it) },
        )
    }

    private fun android.database.Cursor.getStringOrNull(name: String): String? =
        getColumnIndexOrThrow(name).let { index -> if (isNull(index)) null else getString(index) }

    private fun android.database.Cursor.getDoubleOrNull(name: String): Double? =
        getColumnIndexOrThrow(name).let { index -> if (isNull(index)) null else getDouble(index) }

    private fun mediaKind(mimeType: String) = when {
        mimeType == "application/pdf" -> MediaKind.PDF
        mimeType.startsWith("video/") -> MediaKind.VIDEO
        else -> MediaKind.IMAGE
    }

    private fun resultSetMediaIds(resultSetId: String): Set<String> = readableDatabase.rawQuery(
        "SELECT rsm.media_id FROM result_set_media rsm JOIN media_item m ON m.id=rsm.media_id WHERE rsm.result_set_id=? AND m.access_state=? ORDER BY rsm.rank",
        arrayOf(resultSetId, MediaAccessState.ACCESSIBLE.name),
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private fun requireSessionId(sessionId: String) {
        require(sessionId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid query session" }
    }

    private fun encode(values: Collection<Any>): String = JSONArray(values.toList()).toString()

    private fun decodeStrings(value: String): Set<String> = runCatching {
        val json = JSONArray(value)
        buildSet { for (index in 0 until json.length()) add(json.getString(index)) }
    }.getOrDefault(emptySet())

    private fun decodeLongs(value: String): Set<Long> = runCatching {
        val json = JSONArray(value)
        buildSet { for (index in 0 until json.length()) add(json.getLong(index)) }
    }.getOrDefault(emptySet())

    private fun FilterExpression.firstTimeRange(): FilterExpression.TimeRange? = when (this) {
        is FilterExpression.TimeRange -> this
        is FilterExpression.And -> clauses.firstNotNullOfOrNull { it.firstTimeRange() }
        else -> null
    }

    private fun android.database.Cursor.text(name: String): String = getString(getColumnIndexOrThrow(name))

    private fun android.database.Cursor.nullableText(name: String): String? = getColumnIndexOrThrow(name).let {
        if (isNull(it)) null else getString(it)
    }

    private fun android.database.Cursor.nullableLong(name: String): Long? = getColumnIndexOrThrow(name).let {
        if (isNull(it)) null else getLong(it)
    }

    private fun initializeStages(db: GallerySqlDatabase, item: GalleryItem, replace: Boolean) {
        val complete = item.source == MediaSource.DEMO_ASSET || item.indexState == IndexState.READY
        val values = mapOf(
            IndexStage.DISCOVERY to StageStatus.COMPLETE,
            IndexStage.METADATA to StageStatus.COMPLETE,
            IndexStage.THUMBNAIL to if (complete) StageStatus.COMPLETE else StageStatus.PENDING,
            IndexStage.VIDEO_KEYFRAMES to if (item.kind == MediaKind.VIDEO && !complete) StageStatus.PENDING else StageStatus.SKIPPED,
            IndexStage.EMBEDDING to StageStatus.PENDING,
            IndexStage.OCR to if (complete) StageStatus.COMPLETE else StageStatus.PENDING,
            IndexStage.FACES to StageStatus.SKIPPED,
            IndexStage.EVENTS to if (complete) StageStatus.COMPLETE else StageStatus.PENDING,
            IndexStage.ENRICHMENT to if (complete) StageStatus.COMPLETE else StageStatus.PENDING,
        )
        values.forEach { (stage, status) ->
            val content = ContentValues().apply {
                put("media_id", item.id)
                put("stage", stage.name)
                put("status", status.name)
                put("producer_version", when (stage) {
                    IndexStage.FACES -> "disabled-until-opt-in"
                    IndexStage.EVENTS -> "day-event-v1"
                    IndexStage.EMBEDDING -> "not-installed"
                    IndexStage.VIDEO_KEYFRAMES -> if (item.kind == MediaKind.VIDEO) VideoKeyframePolicy.PRODUCER_VERSION else "not-video"
                    else -> if (item.source == MediaSource.DEMO_ASSET) "demo-sidecar-v1" else "media-compiler-v1"
                })
                put("attempt_count", 0)
                put("updated_at", System.currentTimeMillis())
                putNull("error")
            }
            db.insertWithOnConflict(
                "media_index_stage", null, content,
                if (replace) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }

    private fun updateStage(
        db: GallerySqlDatabase,
        mediaId: String,
        stage: IndexStage,
        status: StageStatus,
        producerVersion: String,
        incrementAttempt: Boolean = false,
        error: String? = null,
    ) {
        val changed = db.update("media_index_stage", ContentValues().apply {
            put("status", status.name)
            put("producer_version", producerVersion)
            put("updated_at", System.currentTimeMillis())
            if (incrementAttempt) put("attempt_count", stageAttemptCount(db, mediaId, stage) + 1)
            if (error == null) putNull("error") else put("error", error.take(300))
        }, "media_id=? AND stage=?", arrayOf(mediaId, stage.name))
        if (changed == 0) {
            initializeStages(db, itemById(db, mediaId) ?: return, replace = false)
            updateStage(db, mediaId, stage, status, producerVersion, incrementAttempt, error)
        }
    }

    private fun stageAttemptCount(db: GallerySqlDatabase, mediaId: String, stage: IndexStage): Int = db.query(
        "media_index_stage", arrayOf("attempt_count"), "media_id=? AND stage=?", arrayOf(mediaId, stage.name),
        null, null, null, "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun cursorVideoKeyframe(cursor: android.database.Cursor) = VideoKeyframeRecord(
        id = cursor.text("id"),
        mediaId = cursor.text("media_id"),
        timestampMs = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp_ms")),
        previewPath = cursor.text("preview_path"),
        labels = cursor.text("labels").split(TAG_SEPARATOR).filter(String::isNotBlank),
        ocrText = cursor.text("ocr_text"),
        perceptualHash = java.lang.Long.parseUnsignedLong(cursor.text("perceptual_hash"), 16),
        qualityScore = cursor.getFloat(cursor.getColumnIndexOrThrow("quality_score")),
        producerVersion = cursor.text("producer_version"),
        embeddingVersion = cursor.nullableText("embedding_version"),
    )

    private fun peopleIndexEnabled(db: GallerySqlDatabase): Boolean = db.rawQuery(
        "SELECT enabled FROM people_settings WHERE singleton_id=1",
        null,
    ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) != 0 }

    private fun clusterExists(db: GallerySqlDatabase, clusterId: String): Boolean = db.rawQuery(
        "SELECT 1 FROM person_cluster WHERE id=? LIMIT 1",
        arrayOf(clusterId),
    ).use(android.database.Cursor::moveToFirst)

    private fun identityTermMatches(normalizedQuery: String, rawTerm: String): Boolean {
        val term = rawTerm.trim().lowercase(Locale.ROOT)
        if (term.isEmpty()) return false
        return Regex("(^|[^\\p{L}\\p{M}\\p{N}])${Regex.escape(term)}([^\\p{L}\\p{M}\\p{N}]|$)")
            .containsMatchIn(normalizedQuery)
    }

    fun embeddingReadyMediaIds(): Set<String> = readableDatabase.rawQuery(
        "SELECT media_id FROM media_index_stage WHERE stage=? AND status=?",
        arrayOf(IndexStage.EMBEDDING.name, StageStatus.COMPLETE.name),
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun frequentlyRetrievedMediaIds(limit: Int = 32): List<String> = readableDatabase.rawQuery(
        "SELECT media_id,COUNT(*) AS uses FROM result_set_media GROUP BY media_id ORDER BY uses DESC LIMIT ?",
        arrayOf(limit.coerceIn(1, 128).toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun queueEligiblePersonalSemanticMemoryJobs(
        modelVersion: String?,
        userRequested: Boolean = false,
        mediaIds: Set<String>? = null,
    ): Int = writableDatabase.transaction { db ->
        val reason = PersonalSemanticMemoryPolicy.jobReason(modelVersion)
        val now = System.currentTimeMillis()
        db.update(
            "semantic_enrichment_job",
            ContentValues().apply {
                put("status", SemanticEnrichmentStatus.COMPLETE.name)
                put("model_version", "superseded:${PersonalSemanticMemoryPolicy.PROMPT_VERSION}")
                putNull("lease_owner")
                putNull("lease_expires_at")
                put("next_attempt_at", 0L)
                put("last_progress_at", now)
                put("updated_at", now)
            },
            "reason LIKE ? AND reason<>? AND status<>?",
            arrayOf(
                "${PersonalSemanticMemoryPolicy.JOB_PREFIX}%",
                reason,
                SemanticEnrichmentStatus.COMPLETE.name,
            ),
        )
        db.update(
            "semantic_enrichment_job",
            ContentValues().apply {
                put("status", SemanticEnrichmentStatus.PENDING.name)
                put("attempt_count", 0)
                putNull("error")
                putNull("lease_owner")
                putNull("lease_expires_at")
                put("next_attempt_at", 0L)
                put("updated_at", now)
            },
            "reason=? AND status=? AND model_version IS NULL AND error IN (?,?,?)",
            arrayOf(
                reason,
                SemanticEnrichmentStatus.FAILED.name,
                "Enrichment omitted the facts array",
                "Enrichment returned malformed JSON",
                "Enrichment must return one JSON object",
            ),
        )
        val queued = personalSemanticMemoryEligibleMediaIds(db, mediaIds).sumOf { mediaId ->
            val stable = "${SemanticFactScope.MEDIA}|$mediaId|$mediaId|$reason"
            val inserted = db.insertWithOnConflict("semantic_enrichment_job", null, ContentValues().apply {
                put("id", UUID.nameUUIDFromBytes(stable.toByteArray(Charsets.UTF_8)).toString())
                put("scope", SemanticFactScope.MEDIA.name)
                put("subject_id", mediaId)
                put("representative_media_id", mediaId)
                put("reason", reason)
                put("status", SemanticEnrichmentStatus.PENDING.name)
                put("attempt_count", 0)
                put("user_requested", userRequested)
                putNull("model_version")
                putNull("error")
                putNull("lease_owner")
                putNull("lease_expires_at")
                put("next_attempt_at", 0L)
                putNull("last_progress_at")
                put("updated_at", now)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (userRequested) {
                db.update("semantic_enrichment_job", ContentValues().apply {
                    put("user_requested", true)
                    put("updated_at", now)
                }, "id=? AND status<>?", arrayOf(
                    UUID.nameUUIDFromBytes(stable.toByteArray(Charsets.UTF_8)).toString(),
                    SemanticEnrichmentStatus.COMPLETE.name,
                ))
            }
            if (inserted >= 0L) 1 else 0
        }
        db.update(
            "semantic_enrichment_job",
            ContentValues().apply {
                put("status", SemanticEnrichmentStatus.COMPLETE.name)
                put("model_version", "caption-v4:recovered-existing-caption")
                putNull("error")
                putNull("lease_owner")
                putNull("lease_expires_at")
                put("next_attempt_at", 0L)
                put("last_progress_at", now)
                put("updated_at", now)
            },
            """
            reason=? AND status=? AND EXISTS (
                SELECT 1 FROM semantic_caption c
                WHERE c.evidence_media_id=semantic_enrichment_job.representative_media_id
                  AND c.scope=?
                  AND c.source_type IN (?,?)
                  AND c.applicability<>'STALE_PERSON_BINDING'
                  AND c.prompt_version=?
            )
            """.trimIndent(),
            arrayOf(
                reason,
                SemanticEnrichmentStatus.PENDING.name,
                SemanticFactScope.MEDIA.name,
                "GEMMA_MEDIA_DIRECT",
                "EXACT_DUPLICATE_REUSE",
                SemanticEnrichmentCodec.PROMPT_VERSION,
            ),
        )
        queued
    }

    fun recordExactContentDigest(mediaId: String, digest: String) {
        require(digest.startsWith("sha256-file-v1:") && digest.length <= 128) { "Invalid exact-content digest" }
        writableDatabase.update(
            "media_item",
            ContentValues().apply { put("exact_content_digest", digest) },
            "id=?",
            arrayOf(mediaId),
        )
    }

    fun reuseExactDuplicateSemanticEnrichment(
        job: SemanticEnrichmentJobRecord,
        targetBindings: List<PersonVerificationBinding>,
        digest: String,
    ): Boolean {
        if (job.scope != SemanticFactScope.MEDIA || !PersonalSemanticMemoryPolicy.isPersonalJob(job.reason)) return false
        val sourceIds = readableDatabase.rawQuery(
            """
            SELECT DISTINCT m.id
            FROM media_item m
            JOIN semantic_enrichment_job j ON j.representative_media_id=m.id
            WHERE m.exact_content_digest=? AND m.id<>? AND j.reason=? AND j.status='COMPLETE'
            ORDER BY j.updated_at DESC
            """.trimIndent(),
            arrayOf(digest, job.representativeMediaId, job.reason),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        for (sourceId in sourceIds) {
            val sourceBindings = reviewedFaceBindingsForMedia(sourceId)
            if (!equivalentReviewedBindings(sourceBindings, targetBindings)) continue
            val caption = semanticCaptionsForMedia(sourceId).firstOrNull {
                it.scope == SemanticFactScope.MEDIA &&
                    it.subjectId == sourceId &&
                    it.applicability != "STALE_PERSON_BINDING" &&
                    it.promptVersion == SemanticEnrichmentCodec.PROMPT_VERSION
            } ?: continue
            val targetByLabel = targetBindings.associateBy(PersonVerificationBinding::stableLabel)
            val copiedRefs = caption.personRefs.mapNotNull { sourceRef ->
                val target = targetByLabel[sourceRef.personRef]
                    ?.takeIf { it.clusterId == sourceRef.clusterId }
                    ?: return@mapNotNull null
                sourceRef.copy(
                    faceRegion = listOf(target.left, target.top, target.right, target.bottom),
                )
            }
            if (copiedRefs.size != caption.personRefs.size) continue
            val copiedFacts = semanticFacts(listOf(sourceId))
                .filter {
                    it.scope == SemanticFactScope.MEDIA &&
                        it.subjectId == sourceId &&
                        it.applicability !in setOf(
                            "GROUP_CONTEXT_ONLY",
                            "LEGACY_GROUP_CONTEXT_ONLY",
                            "STALE_PERSON_BINDING",
                        )
                }
                .map {
                    it.copy(
                        subjectId = job.representativeMediaId,
                        evidenceMediaId = job.representativeMediaId,
                        applicability = "EXACT_DUPLICATE_SHARED",
                    )
                }
            val sourcePersonFacts = personVisualFactsForMedia(sourceId)
            val copiedPersonFacts = sourcePersonFacts.mapNotNull { sourceFact ->
                val target = targetBindings.firstOrNull {
                    it.clusterId == sourceFact.clusterId && it.stableLabel == sourceFact.personRef
                } ?: return@mapNotNull null
                sourceFact.copy(
                    id = "",
                    mediaId = job.representativeMediaId,
                    faceRegion = listOf(target.left, target.top, target.right, target.bottom),
                    updatedAt = 0L,
                )
            }
            if (copiedPersonFacts.size != sourcePersonFacts.size) continue
            completeSemanticEnrichment(
                job,
                SemanticEnrichmentResult(
                    facts = copiedFacts,
                    caption = caption.copy(
                        id = "",
                        scope = SemanticFactScope.MEDIA,
                        subjectId = job.representativeMediaId,
                        evidenceMediaId = job.representativeMediaId,
                        representativeMediaId = sourceId,
                        sourceType = "EXACT_DUPLICATE_REUSE",
                        applicability = "EXACT_DUPLICATE_SHARED",
                        createdAt = System.currentTimeMillis(),
                        updatedAt = 0L,
                        personRefs = copiedRefs,
                    ),
                    personFacts = copiedPersonFacts,
                ),
            )
            return true
        }
        return false
    }

    private fun personalSemanticMemoryEligibleMediaIds(
        db: GallerySqlDatabase,
        mediaIds: Set<String>? = null,
    ): List<String> {
        if (mediaIds != null && mediaIds.isEmpty()) return emptyList()
        val mediaFilter = if (mediaIds == null) {
            ""
        } else {
            " AND m.id IN (${mediaIds.joinToString(",") { "?" }})"
        }
        return db.rawQuery(
            """
        SELECT DISTINCT m.id
        FROM media_item m
        JOIN face_instance f ON f.media_id=m.id
        JOIN person_cluster p ON p.id=f.cluster_id
        WHERE m.media_kind='IMAGE' AND m.access_state='ACCESSIBLE' AND m.index_state='READY'
          AND p.reviewed=1 AND p.hidden=0 AND p.include_in_personal_memory=1
          $mediaFilter
        ORDER BY COALESCE(m.captured_at,m.modified_at,0) DESC,m.id
            """.trimIndent(),
            mediaIds?.toTypedArray() ?: emptyArray(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }

    private fun invalidatePersonalSemanticEvidence(db: GallerySqlDatabase, mediaIds: Collection<String>) {
        mediaIds.distinct().chunked(SQLITE_ID_CHUNK).forEach { ids ->
            if (ids.isEmpty()) return@forEach
            val placeholders = ids.joinToString(",") { "?" }
            val args = ids.toTypedArray()
            val personChunkIds = db.rawQuery(
                "SELECT id FROM semantic_caption_chunk WHERE media_id IN ($placeholders) AND cluster_id IS NOT NULL",
                args,
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            personChunkIds.chunked(SQLITE_ID_CHUNK).forEach { chunkIds ->
                val chunkPlaceholders = chunkIds.joinToString(",") { "?" }
                db.delete("semantic_caption_chunk_fts", "chunk_id IN ($chunkPlaceholders)", chunkIds.toTypedArray())
                db.delete("semantic_caption_chunk", "id IN ($chunkPlaceholders)", chunkIds.toTypedArray())
            }
            db.delete("person_attribute_fact", "media_id IN ($placeholders)", args)
            db.delete(
                "semantic_caption_person_ref",
                "caption_id IN (SELECT id FROM semantic_caption WHERE evidence_media_id IN ($placeholders))",
                args,
            )
            db.update(
                "semantic_caption",
                ContentValues().apply { put("applicability", "STALE_PERSON_BINDING") },
                "scope='MEDIA' AND evidence_media_id IN ($placeholders) AND source_type IN ('GEMMA_MEDIA_DIRECT','EXACT_DUPLICATE_REUSE')",
                args,
            )
            val now = System.currentTimeMillis()
            db.execSQL(
                """
                UPDATE semantic_enrichment_job
                SET status='PENDING',attempt_count=0,error=NULL,lease_owner=NULL,lease_expires_at=NULL,
                    next_attempt_at=0,last_progress_at=NULL,updated_at=$now
                WHERE representative_media_id IN ($placeholders) AND reason LIKE ?
                  AND EXISTS (
                    SELECT 1 FROM face_instance f JOIN person_cluster p ON p.id=f.cluster_id
                    WHERE f.media_id=semantic_enrichment_job.representative_media_id
                      AND p.reviewed=1 AND p.hidden=0 AND p.include_in_personal_memory=1
                  )
                """.trimIndent(),
                arrayOf<Any?>(*args, "${PersonalSemanticMemoryPolicy.JOB_PREFIX}%"),
            )
        }
    }

    private fun mediaIdsForClusters(db: GallerySqlDatabase, clusterIds: Collection<String>): List<String> {
        if (clusterIds.isEmpty()) return emptyList()
        val placeholders = clusterIds.joinToString(",") { "?" }
        return db.rawQuery(
            "SELECT DISTINCT media_id FROM face_instance WHERE cluster_id IN ($placeholders)",
            clusterIds.toTypedArray(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }

    private fun dropPendingPersonalJobs(db: GallerySqlDatabase, mediaIds: Collection<String>) {
        mediaIds.distinct().chunked(SQLITE_ID_CHUNK).forEach { ids ->
            if (ids.isEmpty()) return@forEach
            val placeholders = ids.joinToString(",") { "?" }
            db.delete(
                "semantic_enrichment_job",
                "representative_media_id IN ($placeholders) AND reason LIKE ? AND status<>?",
                ids.toTypedArray() + arrayOf(
                    "${PersonalSemanticMemoryPolicy.JOB_PREFIX}%",
                    SemanticEnrichmentStatus.COMPLETE.name,
                ),
            )
        }
    }

    private fun equivalentReviewedBindings(
        source: List<PersonVerificationBinding>,
        target: List<PersonVerificationBinding>,
    ): Boolean {
        if (source.isEmpty() || source.size != target.size) return false
        val targetByLabel = target.associateBy(PersonVerificationBinding::stableLabel)
        return source.all { first ->
            val second = targetByLabel[first.stableLabel] ?: return@all false
            first.clusterId == second.clusterId &&
                listOf(
                    kotlin.math.abs(first.left - second.left),
                    kotlin.math.abs(first.top - second.top),
                    kotlin.math.abs(first.right - second.right),
                    kotlin.math.abs(first.bottom - second.bottom),
                ).all { it <= 0.0001f }
        }
    }

    fun replaceSemanticEnrichmentPlan(plan: SemanticEnrichmentPlan) {
        writableDatabase.transaction { db ->
            db.delete("event_representative", null, null)
            db.delete("visual_group_member", null, null)
            db.delete("visual_group", null, null)
            val now = System.currentTimeMillis()
            plan.groups.forEach { group ->
                db.insertOrThrow("visual_group", null, ContentValues().apply {
                    put("id", group.id)
                    put("kind", group.kind)
                    put("canonical_media_id", group.canonicalMediaId)
                    put("producer_version", "adaptive-groups-v1")
                    put("updated_at", now)
                })
                group.members.forEach { mediaId ->
                    db.insertOrThrow("visual_group_member", null, ContentValues().apply {
                        put("group_id", group.id)
                        put("media_id", mediaId)
                        put("role", if (mediaId in group.representatives) "REPRESENTATIVE" else "MEMBER")
                        put("diversity_score", if (mediaId in group.representatives) 1f else 0f)
                    })
                }
            }
            plan.eventRepresentatives.forEach { representative ->
                db.insertWithOnConflict("event_representative", null, ContentValues().apply {
                    put("event_id", representative.eventId)
                    put("media_id", representative.mediaId)
                    put("rank", representative.rank)
                    put("reason", representative.reason)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            plan.jobs.forEach { job ->
                db.insertWithOnConflict("semantic_enrichment_job", null, ContentValues().apply {
                    put("id", job.id)
                    put("scope", job.scope.name)
                    put("subject_id", job.subjectId)
                    put("representative_media_id", job.representativeMediaId)
                    put("reason", job.reason)
                    put("status", job.status.name)
                    put("attempt_count", job.attemptCount)
                    put("user_requested", job.userRequested)
                    putNull("model_version")
                    putNull("error")
                    put("updated_at", now)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                if (job.userRequested) {
                    db.update("semantic_enrichment_job", ContentValues().apply {
                        put("status", SemanticEnrichmentStatus.PENDING.name)
                        put("attempt_count", 0)
                        put("user_requested", true)
                        putNull("error")
                        put("updated_at", now)
                    }, "id=? AND status IN (?,?)", arrayOf(
                        job.id,
                        SemanticEnrichmentStatus.PENDING.name,
                        SemanticEnrichmentStatus.FAILED.name,
                    ))
                }
            }
        }
    }

    fun claimSemanticEnrichmentJob(
        userRequestedOnly: Boolean = false,
        owner: String = "semantic-enrichment",
    ): SemanticEnrichmentJobRecord? {
        var selected: SemanticEnrichmentJobRecord? = null
        writableDatabase.transaction { db ->
            val where = if (userRequestedOnly) " AND user_requested=1" else ""
            selected = db.rawQuery(
                "SELECT * FROM semantic_enrichment_job WHERE status=? AND next_attempt_at<=?$where " +
                    "ORDER BY user_requested DESC," +
                    "CASE WHEN reason LIKE '${PersonalSemanticMemoryPolicy.JOB_PREFIX}%' THEN 0 ELSE 1 END," +
                    "updated_at LIMIT 1",
                arrayOf(SemanticEnrichmentStatus.PENDING.name, System.currentTimeMillis().toString()),
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else SemanticEnrichmentJobRecord(
                    id = cursor.text("id"),
                    scope = SemanticFactScope.valueOf(cursor.text("scope")),
                    subjectId = cursor.text("subject_id"),
                    representativeMediaId = cursor.text("representative_media_id"),
                    reason = cursor.text("reason"),
                    status = SemanticEnrichmentStatus.RUNNING,
                    attemptCount = cursor.getInt(cursor.getColumnIndexOrThrow("attempt_count")) + 1,
                    userRequested = cursor.getInt(cursor.getColumnIndexOrThrow("user_requested")) != 0,
                    modelVersion = cursor.nullableText("model_version"),
                    error = cursor.nullableText("error"),
                )
            }
            selected?.let { job ->
                db.update("semantic_enrichment_job", ContentValues().apply {
                    put("status", SemanticEnrichmentStatus.RUNNING.name)
                    put("attempt_count", job.attemptCount)
                    put("updated_at", System.currentTimeMillis())
                    put("last_progress_at", System.currentTimeMillis())
                    put("lease_owner", owner)
                    put("lease_expires_at", System.currentTimeMillis() + IndexingRetryPolicy.LEASE_MILLIS)
                    putNull("error")
                }, "id=? AND status=?", arrayOf(job.id, SemanticEnrichmentStatus.PENDING.name))
            }
        }
        return selected
    }

    fun hasPendingSemanticEnrichmentJobs(): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM semantic_enrichment_job WHERE status=? LIMIT 1",
        arrayOf(SemanticEnrichmentStatus.PENDING.name),
    ).use(android.database.Cursor::moveToFirst)

    fun hasUserRequestedPendingSemanticEnrichmentJobs(): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM semantic_enrichment_job WHERE status=? AND user_requested=1 LIMIT 1",
        arrayOf(SemanticEnrichmentStatus.PENDING.name),
    ).use(android.database.Cursor::moveToFirst)

    fun semanticEnrichmentPlanNeedsRebuild(): Boolean {
        val activeStatuses = setOf(
            SemanticEnrichmentStatus.PENDING.name,
            SemanticEnrichmentStatus.RUNNING.name,
            SemanticEnrichmentStatus.FAILED.name,
        )
        val placeholders = activeStatuses.joinToString(",") { "?" }
        val counts = readableDatabase.rawQuery(
            """
            SELECT COUNT(*),
                   COALESCE(SUM(CASE WHEN reason='exact_duplicate_canonical' THEN 1 ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN reason='diverse_event_representative' THEN 1 ELSE 0 END),0)
            FROM semantic_enrichment_job
            WHERE status IN ($placeholders)
            """.trimIndent(),
            activeStatuses.toTypedArray(),
        ).use { cursor ->
            if (!cursor.moveToFirst()) IntArray(3) else IntArray(3) { cursor.getInt(it) }
        }
        val hasEventRepresentatives = readableDatabase.rawQuery(
            "SELECT 1 FROM event_representative LIMIT 1",
            emptyArray(),
        ).use(android.database.Cursor::moveToFirst)
        return hasEventRepresentatives &&
            counts[0] >= 32 &&
            counts[1] == counts[0] &&
            counts[2] == 0
    }

    fun semanticMemoryProgress(activeModelVersion: String? = null): SemanticMemoryProgress {
        val db = readableDatabase
        val personalReason = PersonalSemanticMemoryPolicy.jobReason(activeModelVersion)
        val counts = db.rawQuery(
            """
            SELECT COUNT(*),
                   COALESCE(SUM(CASE WHEN status='PENDING' THEN 1 ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN status='RUNNING' THEN 1 ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN status='COMPLETE' THEN 1 ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN status='AUTH_REQUIRED' THEN 1 ELSE 0 END),0)
            FROM semantic_enrichment_job
            """.trimIndent(),
            emptyArray(),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                IntArray(6)
            } else {
                IntArray(6) { index -> cursor.getInt(index) }
            }
        }
        val factCount = db.rawQuery("SELECT COUNT(*) FROM semantic_fact", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        val captionCount = db.rawQuery("SELECT COUNT(*) FROM semantic_caption", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        val captionEmbedding = captionEmbeddingProgress()
        val personVisualFactCount = db.rawQuery("SELECT COUNT(*) FROM person_attribute_fact", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        val personalEligibleCount = personalSemanticMemoryEligibleMediaIds(db).size
        val personalCounts = db.rawQuery(
            """
            SELECT
                COALESCE(SUM(CASE WHEN status='COMPLETE' THEN 1 ELSE 0 END),0),
                COALESCE(SUM(CASE WHEN status IN ('PENDING','RUNNING') THEN 1 ELSE 0 END),0),
                COALESCE(SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),0),
                COALESCE(SUM(CASE WHEN status='AUTH_REQUIRED' THEN 1 ELSE 0 END),0)
            FROM semantic_enrichment_job
            WHERE reason=?
            """.trimIndent(),
            arrayOf(personalReason),
        ).use { cursor ->
            if (!cursor.moveToFirst()) IntArray(4) else IntArray(4) { cursor.getInt(it) }
        }
        val userRequestedPendingJobs = db.rawQuery(
            "SELECT COUNT(*) FROM semantic_enrichment_job WHERE status IN ('PENDING','RUNNING') AND user_requested=1",
            emptyArray(),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        val personalExactReuseCount = db.rawQuery(
            "SELECT COUNT(DISTINCT evidence_media_id) FROM semantic_caption WHERE source_type='EXACT_DUPLICATE_REUSE'",
            emptyArray(),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        val personalStaleCount = db.rawQuery(
            "SELECT COUNT(DISTINCT evidence_media_id) FROM semantic_caption WHERE applicability='STALE_PERSON_BINDING'",
            emptyArray(),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        val latestError = db.rawQuery(
            "SELECT error FROM semantic_enrichment_job WHERE error IS NOT NULL AND error<>'' ORDER BY updated_at DESC LIMIT 1",
            emptyArray(),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        return SemanticMemoryProgress(
            totalJobs = counts[0],
            pendingJobs = counts[1],
            runningJobs = counts[2],
            completedJobs = counts[3],
            failedJobs = counts[4],
            authenticationRequiredJobs = counts[5],
            factCount = factCount,
            captionCount = captionCount,
            captionChunkCount = captionEmbedding.totalChunkCount,
            embeddedCaptionChunkCount = captionEmbedding.embeddedChunkCount,
            pendingCaptionChunkCount = captionEmbedding.pendingChunkCount + captionEmbedding.delayedRetryCount,
            runningCaptionChunkCount = captionEmbedding.runningChunkCount,
            failedCaptionChunkCount = captionEmbedding.failedChunkCount,
            personVisualFactCount = personVisualFactCount,
            personalEligibleCount = personalEligibleCount,
            personalCompletedCount = personalCounts[0],
            personalPendingCount = personalCounts[1],
            personalFailedCount = personalCounts[2],
            personalAuthenticationRequiredCount = personalCounts[3],
            personalExactReuseCount = personalExactReuseCount,
            personalStaleCount = personalStaleCount,
            userRequestedPendingJobs = userRequestedPendingJobs,
            latestError = latestError,
        )
    }

    fun queueLegacySemanticCaptionJobs(): Int = writableDatabase.update(
        "semantic_enrichment_job",
        ContentValues().apply {
            put("status", SemanticEnrichmentStatus.PENDING.name)
            put("attempt_count", 0)
            putNull("error")
            putNull("lease_owner")
            putNull("lease_expires_at")
            put("next_attempt_at", 0L)
            put("updated_at", System.currentTimeMillis())
        },
        "status=? AND reason NOT LIKE ? AND (model_version IS NULL OR model_version NOT LIKE ?)",
        arrayOf(
            SemanticEnrichmentStatus.COMPLETE.name,
            "${PersonalSemanticMemoryPolicy.JOB_PREFIX}%",
            "caption-v4:%",
        ),
    )

    fun completeSemanticEnrichment(job: SemanticEnrichmentJobRecord, facts: List<SemanticFactRecord>) =
        completeSemanticEnrichment(job, SemanticEnrichmentResult(facts))

    fun completeSemanticEnrichment(job: SemanticEnrichmentJobRecord, result: SemanticEnrichmentResult) {
        writableDatabase.transaction { db ->
            val now = System.currentTimeMillis()
            val facts = result.facts
            var storedCaption: SemanticCaptionRecord? = null
            facts.forEach { fact ->
                val targets = if (fact.applicability == "SAFE_FOR_EXACT_DUPLICATES") {
                    exactDuplicateMediaIds(db, fact.evidenceMediaId)
                } else {
                    setOf(fact.subjectId)
                }
                targets.forEach { subjectId ->
                    val stable = "${fact.scope}|$subjectId|${fact.predicate}|${fact.value}|${fact.modelVersion}|${fact.promptVersion}"
                    val id = UUID.nameUUIDFromBytes(stable.toByteArray(Charsets.UTF_8)).toString()
                    db.insertWithOnConflict("semantic_fact", null, ContentValues().apply {
                        put("id", id)
                    put(
                        "scope",
                        if (fact.applicability == "SAFE_FOR_EXACT_DUPLICATES") {
                            SemanticFactScope.MEDIA.name
                        } else if (subjectId == fact.evidenceMediaId) {
                            fact.scope.name
                        } else {
                            SemanticFactScope.MEDIA.name
                        },
                    )
                        put("subject_id", subjectId)
                        put("predicate", fact.predicate)
                        put("value", fact.value)
                        put("confidence", fact.confidence)
                        put("evidence_media_id", fact.evidenceMediaId)
                        if (fact.region == null) putNull("region") else put("region", JSONArray(fact.region).toString())
                        put("applicability", if (subjectId == fact.evidenceMediaId) fact.applicability else "EXACT_DUPLICATE_SHARED")
                        put("model_version", fact.modelVersion)
                        put("prompt_version", fact.promptVersion)
                        put("updated_at", now)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            result.caption?.let { caption ->
                val stable = "${caption.scope}|${caption.subjectId}|${caption.evidenceMediaId}|${caption.modelVersion}|${caption.promptVersion}"
                val captionId = UUID.nameUUIDFromBytes(stable.toByteArray(Charsets.UTF_8)).toString()
                db.insertWithOnConflict("semantic_caption", null, ContentValues().apply {
                    put("id", captionId)
                    put("scope", caption.scope.name)
                    put("subject_id", caption.subjectId)
                    put("text", caption.text.take(4_000))
                    put("confidence", caption.confidence.coerceIn(0f, 1f))
                    put("evidence_media_id", caption.evidenceMediaId)
                    if (caption.representativeMediaId == null) {
                        putNull("representative_media_id")
                    } else {
                        put("representative_media_id", caption.representativeMediaId)
                    }
                    put("source_type", caption.sourceType)
                    put("applicability", caption.applicability)
                    put("body_region_version", caption.bodyRegionVersion)
                    put("model_version", caption.modelVersion)
                    put("prompt_version", caption.promptVersion)
                    put("created_at", caption.createdAt.takeIf { it > 0L } ?: now)
                    put("updated_at", now)
                    putNull("chunk_policy_version")
                    putNull("chunked_at")
                }, SQLiteDatabase.CONFLICT_REPLACE)
                db.delete("semantic_caption_person_ref", "caption_id=?", arrayOf(captionId))
                caption.personRefs.forEach { ref ->
                    db.insertWithOnConflict("semantic_caption_person_ref", null, ContentValues().apply {
                        put("caption_id", captionId)
                        put("person_ref", ref.personRef)
                        put("cluster_id", ref.clusterId)
                        put("face_region", JSONArray(ref.faceRegion).toString())
                        if (ref.bodyRegion == null) putNull("body_region") else put("body_region", JSONArray(ref.bodyRegion).toString())
                        put("association_status", ref.associationStatus.name)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                storedCaption = caption.copy(
                    id = captionId,
                    createdAt = caption.createdAt.takeIf { it > 0L } ?: now,
                    updatedAt = now,
                )
            }
            result.personFacts.forEach { fact ->
                val stable = "${fact.mediaId}|${fact.clusterId}|${fact.relation}|${fact.category}|${fact.itemType}|${fact.value}|${fact.modelVersion}|${fact.promptVersion}"
                db.insertWithOnConflict("person_attribute_fact", null, ContentValues().apply {
                    put("id", UUID.nameUUIDFromBytes(stable.toByteArray(Charsets.UTF_8)).toString())
                    put("media_id", fact.mediaId)
                    put("cluster_id", fact.clusterId)
                    put("predicate", fact.relation.name.lowercase(Locale.ROOT))
                    put("value", fact.value.take(240))
                    put("confidence", fact.confidence.coerceIn(0f, 1f))
                    put("region", JSONArray(fact.evidenceRegion ?: fact.faceRegion).toString())
                    put("model_version", fact.modelVersion)
                    put("person_ref", fact.personRef)
                    put("relation", fact.relation.name)
                    put("category", fact.category?.name ?: WornItemCategory.OTHER_WORN_ITEM.name)
                    if (fact.itemType == null) putNull("item_type") else put("item_type", fact.itemType.take(120))
                    put("attributes", encodeAttributes(fact.attributes))
                    put("body_region", fact.bodyRegion.name)
                    put("face_region", JSONArray(fact.faceRegion).toString())
                    put("association_status", fact.associationStatus.name)
                    put("verdict", fact.verdict.name)
                    if (fact.targetClusterId == null) putNull("target_cluster_id") else put("target_cluster_id", fact.targetClusterId)
                    put("prompt_version", fact.promptVersion)
                    put("updated_at", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            storedCaption?.let { replaceCaptionChunks(db, it, facts, result.personFacts) }
            val producer = result.caption?.modelVersion ?: facts.firstOrNull()?.modelVersion
            val completionPrefix = "caption-v4"
            db.update("semantic_enrichment_job", ContentValues().apply {
                put("status", SemanticEnrichmentStatus.COMPLETE.name)
                put("model_version", producer?.let { "$completionPrefix:$it" } ?: "$completionPrefix:no-accepted-output")
                putNull("error")
                putNull("lease_owner")
                putNull("lease_expires_at")
                put("next_attempt_at", 0L)
                put("last_progress_at", now)
                put("updated_at", now)
            }, "id=?", arrayOf(job.id))
            updateStage(
                db,
                job.representativeMediaId,
                IndexStage.ENRICHMENT,
                StageStatus.COMPLETE,
                producer ?: "adaptive-no-facts-v1",
            )
        }
    }

    fun failSemanticEnrichment(
        job: SemanticEnrichmentJobRecord,
        error: String,
        retryable: Boolean,
        authenticationRequired: Boolean = false,
    ) {
        writableDatabase.transaction { db ->
            val status = when {
                authenticationRequired -> SemanticEnrichmentStatus.AUTH_REQUIRED
                retryable && job.attemptCount < 3 -> SemanticEnrichmentStatus.PENDING
                else -> SemanticEnrichmentStatus.FAILED
            }
            db.update("semantic_enrichment_job", ContentValues().apply {
                put("status", status.name)
                put("error", error.take(240))
                putNull("lease_owner")
                putNull("lease_expires_at")
                put("last_progress_at", System.currentTimeMillis())
                put(
                    "next_attempt_at",
                    if (status == SemanticEnrichmentStatus.PENDING) {
                        IndexingRetryPolicy.nextAttemptAt(System.currentTimeMillis(), job.attemptCount)
                    } else {
                        0L
                    },
                )
                put("updated_at", System.currentTimeMillis())
            }, "id=?", arrayOf(job.id))
            if (status == SemanticEnrichmentStatus.FAILED) {
                updateStage(
                    db,
                    job.representativeMediaId,
                    IndexStage.ENRICHMENT,
                    StageStatus.FAILED_PERMANENT,
                    "adaptive-enrichment-v1",
                    error = error,
                )
            }
        }
    }

    fun semanticFacts(mediaIds: Collection<String>): List<SemanticFactRecord> = mediaIds.chunked(SQLITE_ID_CHUNK).flatMap { ids ->
        if (ids.isEmpty()) return@flatMap emptyList()
        readableDatabase.rawQuery(
            "SELECT * FROM semantic_fact WHERE subject_id IN (${ids.joinToString(",") { "?" }})",
            ids.toTypedArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        SemanticFactRecord(
                            scope = SemanticFactScope.valueOf(cursor.text("scope")),
                            subjectId = cursor.text("subject_id"),
                            predicate = cursor.text("predicate"),
                            value = cursor.text("value"),
                            confidence = cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
                            evidenceMediaId = cursor.text("evidence_media_id"),
                            region = cursor.nullableText("region")?.let { encoded ->
                                JSONArray(encoded).let { array -> List(array.length()) { array.getDouble(it).toFloat() } }
                            },
                            applicability = cursor.text("applicability"),
                            modelVersion = cursor.text("model_version"),
                            promptVersion = cursor.text("prompt_version"),
                        ),
                    )
                }
            }
        }
    }

    fun allSemanticFacts(): List<SemanticFactRecord> = readableDatabase.rawQuery(
        "SELECT * FROM semantic_fact ORDER BY updated_at DESC, predicate, value",
        emptyArray(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    SemanticFactRecord(
                        scope = SemanticFactScope.valueOf(cursor.text("scope")),
                        subjectId = cursor.text("subject_id"),
                        predicate = cursor.text("predicate"),
                        value = cursor.text("value"),
                        confidence = cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
                        evidenceMediaId = cursor.text("evidence_media_id"),
                        region = cursor.nullableText("region")?.let { encoded ->
                            JSONArray(encoded).let { array -> List(array.length()) { array.getDouble(it).toFloat() } }
                        },
                        applicability = cursor.text("applicability"),
                        modelVersion = cursor.text("model_version"),
                        promptVersion = cursor.text("prompt_version"),
                    ),
                )
            }
        }
    }

    fun semanticFactsForMedia(mediaId: String): List<SemanticFactRecord> = readableDatabase.rawQuery(
        "SELECT * FROM semantic_fact WHERE subject_id=? OR evidence_media_id=? ORDER BY predicate,value",
        arrayOf(mediaId, mediaId),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    SemanticFactRecord(
                        scope = SemanticFactScope.valueOf(cursor.text("scope")),
                        subjectId = cursor.text("subject_id"),
                        predicate = cursor.text("predicate"),
                        value = cursor.text("value"),
                        confidence = cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
                        evidenceMediaId = cursor.text("evidence_media_id"),
                        region = cursor.nullableText("region")?.let { encoded ->
                            JSONArray(encoded).let { array -> List(array.length()) { array.getDouble(it).toFloat() } }
                        },
                        applicability = cursor.text("applicability"),
                        modelVersion = cursor.text("model_version"),
                        promptVersion = cursor.text("prompt_version"),
                    ),
                )
            }
        }
    }

    fun allSemanticCaptions(): List<SemanticCaptionRecord> = readableDatabase.rawQuery(
        "SELECT * FROM semantic_caption ORDER BY updated_at DESC",
        emptyArray(),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(readSemanticCaption(cursor)) } }

    fun semanticCaptionsForMedia(mediaId: String): List<SemanticCaptionRecord> = readableDatabase.rawQuery(
        """
        SELECT DISTINCT c.* FROM semantic_caption c
        LEFT JOIN visual_group_member gm ON c.scope IN ('VISUAL_GROUP','EXACT_DUPLICATE_GROUP') AND c.subject_id=gm.group_id
        LEFT JOIN event_media em ON c.scope='EVENT' AND c.subject_id=CAST(em.event_id AS TEXT)
        WHERE c.evidence_media_id=? OR c.subject_id=? OR gm.media_id=? OR em.media_id=?
        ORDER BY c.updated_at DESC
        """.trimIndent(),
        arrayOf(mediaId, mediaId, mediaId, mediaId),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(readSemanticCaption(cursor)) } }

    fun semanticCaptionChunksForMedia(mediaId: String): List<SemanticCaptionChunkRecord> = readableDatabase.rawQuery(
        """
        SELECT DISTINCT c.* FROM semantic_caption_chunk c
        LEFT JOIN visual_group_member gm ON c.scope IN ('VISUAL_GROUP','EXACT_DUPLICATE_GROUP') AND c.scope_id=gm.group_id
        LEFT JOIN event_media em ON c.scope='EVENT' AND c.scope_id=CAST(em.event_id AS TEXT)
        WHERE c.media_id=? OR c.evidence_media_id=? OR gm.media_id=? OR em.media_id=?
        ORDER BY c.updated_at DESC,c.chunk_type,c.id
        """.trimIndent(),
        arrayOf(mediaId, mediaId, mediaId, mediaId),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(readCaptionChunk(cursor)) } }

    fun allSemanticCaptionChunks(): List<SemanticCaptionChunkRecord> = readableDatabase.rawQuery(
        "SELECT * FROM semantic_caption_chunk ORDER BY updated_at DESC,chunk_type,id",
        emptyArray(),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(readCaptionChunk(cursor)) } }

    fun captionEmbeddingProgress(): CaptionEmbeddingProgress {
        val db = readableDatabase
        val captioned = db.rawQuery("SELECT COUNT(DISTINCT evidence_media_id) FROM semantic_caption", emptyArray())
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        val chunked = db.rawQuery(
            "SELECT COUNT(DISTINCT evidence_media_id) FROM semantic_caption WHERE chunk_policy_version=?",
            arrayOf(SemanticCaptionChunker.POLICY_VERSION),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        val now = System.currentTimeMillis()
        val counts = db.rawQuery(
            """
            SELECT COUNT(*),
              COALESCE(SUM(CASE WHEN embedding_state='COMPLETE' THEN 1 ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN embedding_state IN ('PENDING','FAILED_RETRYABLE') AND next_attempt_at<=? THEN 1 ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN embedding_state='RUNNING' THEN 1 ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN embedding_state='FAILED_RETRYABLE' AND next_attempt_at>? THEN 1 ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN embedding_state IN ('FAILED_EXHAUSTED','FAILED_PERMANENT') THEN 1 ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN applicability='STALE_PERSON_BINDING' THEN 1 ELSE 0 END),0)
            FROM semantic_caption_chunk
            """.trimIndent(),
            arrayOf(now.toString(), now.toString()),
        ).use { cursor -> if (!cursor.moveToFirst()) IntArray(7) else IntArray(7) { cursor.getInt(it) } }
        return CaptionEmbeddingProgress(
            captionedMediaCount = captioned,
            chunkedMediaCount = chunked,
            totalChunkCount = counts[0],
            embeddedChunkCount = counts[1],
            pendingChunkCount = counts[2],
            runningChunkCount = counts[3],
            delayedRetryCount = counts[4],
            failedChunkCount = counts[5],
            staleChunkCount = counts[6],
        )
    }

    fun materializeCaptionChunkBackfill(limit: Int): Int = writableDatabase.transaction { db ->
        val captions = db.rawQuery(
            """
            SELECT * FROM semantic_caption
            WHERE COALESCE(chunk_policy_version,'')<>? AND applicability<>'STALE_PERSON_BINDING'
            ORDER BY updated_at DESC LIMIT ?
            """.trimIndent(),
            arrayOf(SemanticCaptionChunker.POLICY_VERSION, limit.coerceIn(1, 32).toString()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(readSemanticCaption(cursor)) } }
        captions.forEach { caption ->
            replaceCaptionChunks(
                db,
                caption,
                semanticFactsForMedia(caption.evidenceMediaId),
                personVisualFactsForMedia(caption.evidenceMediaId),
            )
        }
        captions.size
    }

    fun prepareCaptionEmbeddingVersion(producerVersion: String) {
        writableDatabase.execSQL(
            """
            UPDATE semantic_caption_chunk
            SET embedding_state='PENDING',embedding_model_version=NULL,attempt_count=0,error=NULL,
                lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=0,updated_at=?
            WHERE embedding_state='COMPLETE' AND COALESCE(embedding_model_version,'')<>?
            """.trimIndent(),
            arrayOf(System.currentTimeMillis(), producerVersion),
        )
    }

    fun recoverCaptionEmbeddingClaims() {
        val now = System.currentTimeMillis()
        writableDatabase.execSQL(
            """
            UPDATE semantic_caption_chunk SET embedding_state='PENDING',lease_owner=NULL,lease_expires_at=NULL,
                error='lease_expired',updated_at=?
            WHERE embedding_state='RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at<=?
            """.trimIndent(),
            arrayOf(now, now),
        )
    }

    fun claimCaptionEmbeddingChunks(owner: String, producerVersion: String, limit: Int): List<SemanticCaptionChunkRecord> =
        writableDatabase.transaction { db ->
            val now = System.currentTimeMillis()
            val ids = db.rawQuery(
                """
                SELECT id FROM semantic_caption_chunk
                WHERE embedding_state IN ('PENDING','FAILED_RETRYABLE') AND next_attempt_at<=?
                  AND attempt_count<? AND chunk_policy_version=?
                ORDER BY CASE WHEN cluster_id IS NOT NULL THEN 0 ELSE 1 END,updated_at,id LIMIT ?
                """.trimIndent(),
                arrayOf(
                    now.toString(),
                    IndexingRetryPolicy.MAX_ITEM_ATTEMPTS.toString(),
                    SemanticCaptionChunker.POLICY_VERSION,
                    limit.coerceIn(1, 64).toString(),
                ),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            if (ids.isEmpty()) return@transaction emptyList()
            val placeholders = ids.joinToString(",") { "?" }
            db.execSQL(
                """
                UPDATE semantic_caption_chunk SET embedding_state='RUNNING',embedding_model_version=?,
                    attempt_count=attempt_count+1,lease_owner=?,lease_expires_at=?,last_progress_at=?,updated_at=?,error=NULL
                WHERE id IN ($placeholders)
                """.trimIndent(),
                arrayOf<Any?>(producerVersion, owner, now + CAPTION_EMBEDDING_LEASE_MS, now, now, *ids.toTypedArray()),
            )
            db.rawQuery(
                "SELECT * FROM semantic_caption_chunk WHERE id IN ($placeholders) ORDER BY updated_at,id",
                ids.toTypedArray(),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(readCaptionChunk(cursor)) } }
        }

    fun completeCaptionEmbedding(chunkId: String, producerVersion: String) {
        writableDatabase.update("semantic_caption_chunk", ContentValues().apply {
            put("embedding_state", CaptionEmbeddingState.COMPLETE.name)
            put("embedding_model_version", producerVersion)
            putNull("error")
            putNull("lease_owner")
            putNull("lease_expires_at")
            put("next_attempt_at", 0L)
            put("last_progress_at", System.currentTimeMillis())
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(chunkId))
    }

    fun failCaptionEmbedding(chunkId: String, error: String, retryable: Boolean) {
        val db = writableDatabase
        val attempt = db.rawQuery("SELECT attempt_count FROM semantic_caption_chunk WHERE id=?", arrayOf(chunkId))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else IndexingRetryPolicy.MAX_ITEM_ATTEMPTS }
        val state = when {
            !retryable -> CaptionEmbeddingState.FAILED_PERMANENT
            attempt >= IndexingRetryPolicy.MAX_ITEM_ATTEMPTS -> CaptionEmbeddingState.FAILED_EXHAUSTED
            else -> CaptionEmbeddingState.FAILED_RETRYABLE
        }
        db.update("semantic_caption_chunk", ContentValues().apply {
            put("embedding_state", state.name)
            put("error", error.take(240))
            putNull("lease_owner")
            putNull("lease_expires_at")
            put(
                "next_attempt_at",
                if (state == CaptionEmbeddingState.FAILED_RETRYABLE) {
                    IndexingRetryPolicy.nextAttemptAt(System.currentTimeMillis(), attempt)
                } else {
                    0L
                },
            )
            put("last_progress_at", System.currentTimeMillis())
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(chunkId))
    }

    fun releaseCaptionEmbeddingClaims(owner: String, reason: String) {
        writableDatabase.update("semantic_caption_chunk", ContentValues().apply {
            put("embedding_state", CaptionEmbeddingState.PENDING.name)
            put("error", reason.take(240))
            putNull("lease_owner")
            putNull("lease_expires_at")
            put("updated_at", System.currentTimeMillis())
        }, "embedding_state='RUNNING' AND lease_owner=?", arrayOf(owner))
    }

    fun hasCaptionEmbeddingWork(producerVersion: String): Boolean =
        readableDatabase.rawQuery(
            """
            SELECT 1 WHERE EXISTS(
                SELECT 1 FROM semantic_caption WHERE COALESCE(chunk_policy_version,'')<>? AND applicability<>'STALE_PERSON_BINDING'
            ) OR EXISTS(
                SELECT 1 FROM semantic_caption_chunk
                WHERE chunk_policy_version=? AND (
                    embedding_state IN ('PENDING','RUNNING','FAILED_RETRYABLE')
                    OR embedding_state='COMPLETE' AND COALESCE(embedding_model_version,'')<>?
                )
            ) LIMIT 1
            """.trimIndent(),
            arrayOf(SemanticCaptionChunker.POLICY_VERSION, SemanticCaptionChunker.POLICY_VERSION, producerVersion),
        ).use(android.database.Cursor::moveToFirst)

    fun currentCaptionEmbeddingChunkIds(producerVersion: String): Set<String> = readableDatabase.rawQuery(
        "SELECT id FROM semantic_caption_chunk WHERE embedding_state='COMPLETE' AND embedding_model_version=?",
        arrayOf(producerVersion),
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun eligibleCaptionChunksForSearch(
        allowedMediaIds: Set<String>,
        requiredPersonClusterIds: Set<String>,
        producerVersion: String,
    ): List<SemanticCaptionChunkRecord> {
        if (allowedMediaIds.isEmpty()) return emptyList()
        val direct = allowedMediaIds.chunked(SQLITE_ID_CHUNK).flatMap { ids ->
            val placeholders = ids.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                """
                SELECT * FROM semantic_caption_chunk
                WHERE media_id IN ($placeholders) AND embedding_state='COMPLETE' AND embedding_model_version=?
                """.trimIndent(),
                arrayOf(*ids.toTypedArray(), producerVersion),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(readCaptionChunk(cursor)) } }
        }
        val contextual = readableDatabase.rawQuery(
            """
            SELECT * FROM semantic_caption_chunk
            WHERE scope IN ('VISUAL_GROUP','EVENT') AND embedding_state='COMPLETE' AND embedding_model_version=?
            """.trimIndent(),
            arrayOf(producerVersion),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(readCaptionChunk(cursor)) } }
            .filter { chunkTargets(it).any { target -> target in allowedMediaIds } }
        return (direct + contextual).asSequence()
            .filter {
                it.applicability != "STALE_PERSON_BINDING" &&
                    (it.clusterId == null || requiredPersonClusterIds.isEmpty() || it.clusterId in requiredPersonClusterIds)
            }
            .distinctBy(SemanticCaptionChunkRecord::id)
            .toList()
    }

    fun resolveCaptionVectorHits(
        hits: List<CaptionVectorHit>,
        allowedMediaIds: Set<String>,
        requiredPersonClusterIds: Set<String>,
    ): List<CaptionSearchHit> {
        if (hits.isEmpty()) return emptyList()
        val ids = hits.map(CaptionVectorHit::chunkId)
        val chunks = ids.chunked(SQLITE_ID_CHUNK).flatMap { chunkIds ->
            val placeholders = chunkIds.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT * FROM semantic_caption_chunk WHERE id IN ($placeholders)",
                chunkIds.toTypedArray(),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(readCaptionChunk(cursor)) } }
        }.associateBy(SemanticCaptionChunkRecord::id)
        val captions = chunks.values.map(SemanticCaptionChunkRecord::captionId).distinct()
            .associateWith(::semanticCaptionById)
        return hits.flatMap { hit ->
            val chunk = chunks[hit.chunkId] ?: return@flatMap emptyList()
            if (chunk.clusterId != null && requiredPersonClusterIds.isNotEmpty() && chunk.clusterId !in requiredPersonClusterIds) {
                return@flatMap emptyList()
            }
            val caption = captions[chunk.captionId] ?: return@flatMap emptyList()
            chunkTargets(chunk).filter { it in allowedMediaIds }.map { mediaId ->
                val direct = chunk.scope == SemanticFactScope.EXACT_DUPLICATE_GROUP || mediaId == chunk.evidenceMediaId
                CaptionSearchHit(
                    mediaId = mediaId,
                    caption = caption,
                    score = hit.score * if (direct) 1.0 else 0.72,
                    directEvidence = direct,
                    chunk = chunk,
                    queryVariant = hit.queryVariant,
                )
            }
        }.sortedByDescending(CaptionSearchHit::score).distinctBy(CaptionSearchHit::mediaId)
    }

    fun personVisualFactsForMedia(mediaId: String): List<PersonVisualFactRecord> = readableDatabase.rawQuery(
        "SELECT f.*,p.label,p.relationship FROM person_attribute_fact f " +
            "LEFT JOIN person_cluster p ON p.id=f.cluster_id WHERE f.media_id=? ORDER BY f.cluster_id,f.relation,f.value",
        arrayOf(mediaId),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(readPersonVisualFact(cursor)) } }

    fun allPersonVisualFacts(): List<PersonVisualFactRecord> = readableDatabase.rawQuery(
        "SELECT f.*,p.label,p.relationship FROM person_attribute_fact f " +
            "LEFT JOIN person_cluster p ON p.id=f.cluster_id ORDER BY f.media_id,f.cluster_id,f.relation,f.value",
        emptyArray(),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(readPersonVisualFact(cursor)) } }

    fun searchSemanticCaptions(
        queries: Collection<String>,
        allowedIds: Set<String>,
        requiredPersonClusterIds: Set<String> = emptySet(),
        limit: Int = 500,
    ): List<CaptionSearchHit> {
        if (allowedIds.isEmpty()) return emptyList()
        val variants = CaptionLexicalQueryBuilder.variants(queries)
        val perVariant = variants.mapNotNull { query ->
            val expression = CaptionLexicalQueryBuilder.ftsExpression(query) ?: return@mapNotNull null
            val matches = runCatching {
                readableDatabase.rawQuery(
                    """
                    SELECT c.*,matchinfo(semantic_caption_chunk_fts,'pcnalx') AS fts_info
                    FROM semantic_caption_chunk_fts
                    JOIN semantic_caption_chunk c ON c.id=semantic_caption_chunk_fts.chunk_id
                    WHERE semantic_caption_chunk_fts MATCH ? AND c.chunk_policy_version=?
                    """.trimIndent(),
                    arrayOf(expression, SemanticCaptionChunker.POLICY_VERSION),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val chunk = readCaptionChunk(cursor)
                            if (
                                chunk.applicability == "STALE_PERSON_BINDING" ||
                                chunk.clusterId != null &&
                                requiredPersonClusterIds.isNotEmpty() &&
                                chunk.clusterId !in requiredPersonClusterIds
                            ) {
                                continue
                            }
                            val caption = semanticCaptionById(chunk.captionId) ?: continue
                            val baseScore = CaptionFtsRanker.bm25(cursor.getBlob(cursor.getColumnIndexOrThrow("fts_info")))
                            chunkTargets(chunk).filter { it in allowedIds }.forEach { mediaId ->
                                val direct = chunk.scope == SemanticFactScope.EXACT_DUPLICATE_GROUP ||
                                    mediaId == chunk.evidenceMediaId
                                add(
                                    CaptionSearchHit(
                                        mediaId,
                                        caption,
                                        baseScore * if (direct) 1.0 else 0.72,
                                        direct,
                                        chunk,
                                        query,
                                    ),
                                )
                            }
                        }
                    }
                }
            }.getOrDefault(emptyList()).sortedByDescending(CaptionSearchHit::score).distinctBy(CaptionSearchHit::mediaId)
            query to matches
        }
        if (perVariant.any { it.second.isNotEmpty() }) {
            val fused = HybridRankFusion.fuse(perVariant.map { RankedChannel(1.0, it.second.map(CaptionSearchHit::mediaId)) })
            val best = perVariant.flatMap { it.second }.groupBy(CaptionSearchHit::mediaId)
                .mapValues { (_, hits) -> hits.maxBy(CaptionSearchHit::score) }
            return fused.mapNotNull { (mediaId, score) -> best[mediaId]?.copy(score = score) }.take(limit)
        }
        return legacyCaptionSearch(variants, allowedIds, limit)
    }

    private fun legacyCaptionSearch(
        variants: List<String>,
        allowedIds: Set<String>,
        limit: Int,
    ): List<CaptionSearchHit> {
        val perVariant = variants.map { query ->
            val terms = Regex("[\\p{L}\\p{M}\\p{N}]+").findAll(query.lowercase(Locale.ROOT))
                .map(MatchResult::value).filter { it.length > 2 && it !in CAPTION_STOP_WORDS }.distinct().take(16).toList()
            val hits = if (terms.isEmpty()) emptyList() else allSemanticCaptions().flatMap { caption ->
                val matched = terms.count { it in caption.text.lowercase(Locale.ROOT) }
                if (matched == 0) return@flatMap emptyList()
                captionTargets(caption).filter { it in allowedIds }.map { mediaId ->
                    val direct = mediaId == caption.evidenceMediaId || caption.scope == SemanticFactScope.EXACT_DUPLICATE_GROUP
                    CaptionSearchHit(
                        mediaId,
                        caption,
                        matched.toDouble() / terms.size * if (direct) 1.0 else 0.72,
                        direct,
                        queryVariant = query,
                    )
                }
            }.sortedByDescending(CaptionSearchHit::score).distinctBy(CaptionSearchHit::mediaId)
            query to hits
        }
        val fused = HybridRankFusion.fuse(perVariant.map { RankedChannel(1.0, it.second.map(CaptionSearchHit::mediaId)) })
        val best = perVariant.flatMap { it.second }.groupBy(CaptionSearchHit::mediaId)
            .mapValues { (_, hits) -> hits.maxBy(CaptionSearchHit::score) }
        return fused.mapNotNull { (id, score) -> best[id]?.copy(score = score) }.take(limit)
    }

    fun semanticCaptionEvidenceCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(DISTINCT evidence_media_id) FROM semantic_caption",
        emptyArray(),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun semanticCaptionEvidenceCount(allowedMediaIds: Set<String>): Int {
        if (allowedMediaIds.isEmpty()) return 0

        return allowedMediaIds.chunked(SQLITE_ID_CHUNK).sumOf { ids ->
            val placeholders = ids.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT COUNT(DISTINCT evidence_media_id) FROM semantic_caption " +
                    "WHERE evidence_media_id IN ($placeholders)",
                ids.toTypedArray(),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        }
    }

    fun hasAuthenticationProtectedOcr(mediaId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM ocr_entity WHERE media_id=? AND entity_type IN ('PASSWORD','EMAIL','PHONE','ORDER_ID') LIMIT 1",
        arrayOf(mediaId),
    ).use(android.database.Cursor::moveToFirst)

    private fun exactDuplicateMediaIds(db: GallerySqlDatabase, mediaId: String): Set<String> = db.rawQuery(
        "SELECT sibling.id FROM media_item source JOIN media_item sibling " +
            "ON sibling.exact_content_digest=source.exact_content_digest " +
            "WHERE source.id=? AND source.exact_content_digest IS NOT NULL",
        arrayOf(mediaId),
    ).use { cursor ->
        buildSet {
            add(mediaId)
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    private fun readSemanticCaption(cursor: android.database.Cursor): SemanticCaptionRecord {
        val id = cursor.text("id")
        return SemanticCaptionRecord(
            id = id,
            scope = SemanticFactScope.valueOf(cursor.text("scope")),
            subjectId = cursor.text("subject_id"),
            text = cursor.text("text"),
            confidence = cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
            evidenceMediaId = cursor.text("evidence_media_id"),
            representativeMediaId = cursor.nullableText("representative_media_id"),
            sourceType = cursor.text("source_type"),
            applicability = cursor.text("applicability"),
            bodyRegionVersion = cursor.text("body_region_version"),
            modelVersion = cursor.text("model_version"),
            promptVersion = cursor.text("prompt_version"),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
            personRefs = captionPersonRefs(id),
        )
    }

    private fun semanticCaptionById(id: String): SemanticCaptionRecord? = readableDatabase.rawQuery(
        "SELECT * FROM semantic_caption WHERE id=? LIMIT 1",
        arrayOf(id),
    ).use { cursor -> if (cursor.moveToFirst()) readSemanticCaption(cursor) else null }

    private fun readCaptionChunk(cursor: android.database.Cursor) = SemanticCaptionChunkRecord(
        id = cursor.text("id"),
        captionId = cursor.text("caption_id"),
        mediaId = cursor.text("media_id"),
        scope = SemanticFactScope.valueOf(cursor.text("scope")),
        scopeId = cursor.text("scope_id"),
        evidenceMediaId = cursor.text("evidence_media_id"),
        clusterId = cursor.nullableText("cluster_id"),
        chunkType = enumOrDefault(cursor.text("chunk_type"), CaptionChunkType.OTHER),
        exactText = cursor.text("exact_text"),
        confidence = cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
        applicability = cursor.text("applicability"),
        captionModelVersion = cursor.text("caption_model_version"),
        captionPromptVersion = cursor.text("caption_prompt_version"),
        chunkPolicyVersion = cursor.text("chunk_policy_version"),
        embeddingModelVersion = cursor.nullableText("embedding_model_version"),
        embeddingState = enumOrDefault(cursor.text("embedding_state"), CaptionEmbeddingState.PENDING),
        attemptCount = cursor.getInt(cursor.getColumnIndexOrThrow("attempt_count")),
        error = cursor.nullableText("error"),
        leaseOwner = cursor.nullableText("lease_owner"),
        leaseExpiresAt = cursor.getColumnIndex("lease_expires_at").let { if (it < 0 || cursor.isNull(it)) null else cursor.getLong(it) },
        nextAttemptAt = cursor.getLong(cursor.getColumnIndexOrThrow("next_attempt_at")),
        lastProgressAt = cursor.getColumnIndex("last_progress_at").let { if (it < 0 || cursor.isNull(it)) null else cursor.getLong(it) },
        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
    )

    private fun readPersonVisualFact(cursor: android.database.Cursor): PersonVisualFactRecord {
        val region = decodeRegion(cursor.text("region"))
        return PersonVisualFactRecord(
            id = cursor.text("id"),
            mediaId = cursor.text("media_id"),
            clusterId = cursor.text("cluster_id"),
            resolvedLabel = cursor.nullableText("label") ?: cursor.nullableText("relationship"),
            personRef = cursor.text("person_ref"),
            relation = enumOrDefault(cursor.text("relation"), PersonVisualRelation.ACTION),
            category = enumOrNull<WornItemCategory>(cursor.text("category")),
            itemType = cursor.nullableText("item_type"),
            value = cursor.text("value"),
            attributes = decodeAttributes(cursor.text("attributes")),
            bodyRegion = enumOrDefault(cursor.text("body_region"), BodyRegion.UNKNOWN),
            confidence = cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
            faceRegion = cursor.nullableText("face_region")?.let(::decodeRegion) ?: region,
            evidenceRegion = region,
            associationStatus = enumOrDefault(cursor.text("association_status"), PersonAssociationStatus.CONFIDENT),
            verdict = enumOrDefault(cursor.text("verdict"), PersonVisualVerdict.VERIFIED_TRUE),
            targetClusterId = cursor.nullableText("target_cluster_id"),
            modelVersion = cursor.text("model_version"),
            promptVersion = cursor.text("prompt_version"),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
        )
    }

    private fun replaceCaptionChunks(
        db: GallerySqlDatabase,
        caption: SemanticCaptionRecord,
        facts: List<SemanticFactRecord>,
        personFacts: List<PersonVisualFactRecord>,
    ) {
        val oldIds = db.rawQuery("SELECT id FROM semantic_caption_chunk WHERE caption_id=?", arrayOf(caption.id))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        oldIds.chunked(SQLITE_ID_CHUNK).forEach { ids ->
            if (ids.isEmpty()) return@forEach
            val placeholders = ids.joinToString(",") { "?" }
            db.delete("semantic_caption_chunk_fts", "chunk_id IN ($placeholders)", ids.toTypedArray())
        }
        db.delete("semantic_caption_chunk", "caption_id=?", arrayOf(caption.id))
        SemanticCaptionChunker.generate(caption, facts, personFacts).forEach { chunk ->
            db.insertWithOnConflict("semantic_caption_chunk", null, ContentValues().apply {
                put("id", chunk.id)
                put("caption_id", chunk.captionId)
                put("media_id", chunk.mediaId)
                put("scope", chunk.scope.name)
                put("scope_id", chunk.scopeId)
                put("evidence_media_id", chunk.evidenceMediaId)
                if (chunk.clusterId == null) putNull("cluster_id") else put("cluster_id", chunk.clusterId)
                put("chunk_type", chunk.chunkType.name)
                put("exact_text", chunk.exactText)
                put("confidence", chunk.confidence)
                put("applicability", chunk.applicability)
                put("caption_model_version", chunk.captionModelVersion)
                put("caption_prompt_version", chunk.captionPromptVersion)
                put("chunk_policy_version", chunk.chunkPolicyVersion)
                putNull("embedding_model_version")
                put("embedding_state", CaptionEmbeddingState.PENDING.name)
                put("attempt_count", 0)
                putNull("error")
                putNull("lease_owner")
                putNull("lease_expires_at")
                put("next_attempt_at", 0L)
                putNull("last_progress_at")
                put("created_at", chunk.createdAt)
                put("updated_at", chunk.updatedAt)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.insertWithOnConflict("semantic_caption_chunk_fts", null, ContentValues().apply {
                put("chunk_id", chunk.id)
                put("exact_text", chunk.exactText)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        db.update("semantic_caption", ContentValues().apply {
            put("chunk_policy_version", SemanticCaptionChunker.POLICY_VERSION)
            put("chunked_at", System.currentTimeMillis())
        }, "id=?", arrayOf(caption.id))
    }

    private fun captionTargets(caption: SemanticCaptionRecord): List<String> = when (caption.scope) {
        SemanticFactScope.MEDIA, SemanticFactScope.QUERY_VERIFICATION -> listOf(caption.evidenceMediaId)
        SemanticFactScope.EXACT_DUPLICATE_GROUP, SemanticFactScope.VISUAL_GROUP -> readableDatabase.rawQuery(
            "SELECT media_id FROM visual_group_member WHERE group_id=?",
            arrayOf(caption.subjectId),
        ).use { cursor -> buildList { add(caption.evidenceMediaId); while (cursor.moveToNext()) add(cursor.getString(0)) } }
        SemanticFactScope.EVENT -> listOf(caption.evidenceMediaId) + eventMembers(caption.subjectId.toLongOrNull() ?: Long.MIN_VALUE)
    }.distinct()

    private fun chunkTargets(chunk: SemanticCaptionChunkRecord): List<String> = when (chunk.scope) {
        SemanticFactScope.MEDIA, SemanticFactScope.QUERY_VERIFICATION -> listOf(chunk.evidenceMediaId)
        SemanticFactScope.EXACT_DUPLICATE_GROUP, SemanticFactScope.VISUAL_GROUP -> readableDatabase.rawQuery(
            "SELECT media_id FROM visual_group_member WHERE group_id=?",
            arrayOf(chunk.scopeId),
        ).use { cursor -> buildList { add(chunk.evidenceMediaId); while (cursor.moveToNext()) add(cursor.getString(0)) } }
        SemanticFactScope.EVENT -> listOf(chunk.evidenceMediaId) + eventMembers(chunk.scopeId.toLongOrNull() ?: Long.MIN_VALUE)
    }.distinct()

    private fun captionPersonRefs(captionId: String): List<SemanticCaptionPersonRefRecord> = readableDatabase.rawQuery(
        "SELECT r.*,p.label,p.relationship FROM semantic_caption_person_ref r " +
            "LEFT JOIN person_cluster p ON p.id=r.cluster_id WHERE r.caption_id=? ORDER BY r.person_ref",
        arrayOf(captionId),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                SemanticCaptionPersonRefRecord(
                    personRef = cursor.text("person_ref"),
                    clusterId = cursor.text("cluster_id"),
                    resolvedLabel = cursor.nullableText("label") ?: cursor.nullableText("relationship"),
                    faceRegion = decodeRegion(cursor.text("face_region")),
                    bodyRegion = cursor.nullableText("body_region")?.let(::decodeRegion),
                    associationStatus = enumOrDefault(cursor.text("association_status"), PersonAssociationStatus.AMBIGUOUS),
                ),
            )
        }
    }

    private fun encodeAttributes(attributes: Map<String, List<String>>): String = JSONObject().apply {
        attributes.forEach { (key, values) -> put(key, JSONArray(values)) }
    }.toString()

    private fun decodeAttributes(encoded: String): Map<String, List<String>> = runCatching {
        val json = JSONObject(encoded)
        json.keys().asSequence().associateWith { key ->
            val values = json.optJSONArray(key) ?: JSONArray()
            List(values.length()) { values.optString(it) }
        }
    }.getOrDefault(emptyMap())

    private fun decodeRegion(encoded: String): List<Float> = JSONArray(encoded).let { array ->
        List(array.length()) { array.getDouble(it).toFloat() }
    }

    private inline fun <reified T : Enum<T>> enumOrNull(raw: String): T? =
        enumValues<T>().firstOrNull { it.name == raw }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String, fallback: T): T =
        enumOrNull<T>(raw) ?: fallback

    companion object {
        private const val CAPTION_EMBEDDING_LEASE_MS = 10 * 60_000L
        private val CAPTION_STOP_WORDS = setOf(
            "show", "photos", "photo", "pictures", "picture", "images", "image", "with", "where",
            "that", "this", "from", "have", "wearing", "please", "some", "में", "वाली", "दिखाओ",
        )
        private const val SQLITE_ID_CHUNK = 800
        const val PEOPLE_CONSENT_VERSION = 1
        private const val MIN_UNREVIEWED_PERSON_MEDIA = 5
        const val PRIMARY_QUERY_SESSION = "primary"
        private const val MAX_RESULT_SETS_PER_SESSION = 20
        private const val MAX_FACES_PER_MEDIA = 64
        private const val MAX_PERSON_ALIASES = 16
        private const val MAX_EVENT_CORRECTION_MEDIA = 100
        private const val MAX_EVENT_LABEL_LENGTH = 120
        private const val TAG_SEPARATOR = "\u001F"
        private val PERSON_ID = Regex("[a-z][a-z0-9_]{0,63}")
    }
}

private object UriSafety {
    fun isMediaContentUri(value: String): Boolean = runCatching {
        val uri = android.net.Uri.parse(value)
        uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY
    }.getOrDefault(false)
}
