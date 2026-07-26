package com.samsung.agenticgallery

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.MediaStore
import org.json.JSONArray
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

    fun pendingItems(limit: Int): List<GalleryItem> = queryItems(
        "index_state IN ('PENDING','FAILED_RETRYABLE') AND source_kind != 'DEMO_ASSET'",
        null,
        "modified_at DESC",
        limit.toString(),
    )

    fun pendingItemsForIds(mediaIds: Set<String>, limit: Int): List<GalleryItem> = queryScoped(mediaIds, limit) { ids, remaining ->
        queryItems(
            "id IN (${ids.joinToString(",") { "?" }}) AND index_state IN ('PENDING','FAILED_RETRYABLE') AND source_kind != 'DEMO_ASSET'",
            ids.toTypedArray(),
            "modified_at DESC",
            remaining.toString(),
        )
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

    fun embeddingPendingItems(producerVersion: String, limit: Int): List<GalleryItem> = readableDatabase.rawQuery(
        """SELECT m.* FROM media_item m
            JOIN media_index_stage s ON s.media_id=m.id AND s.stage='EMBEDDING'
            WHERE m.access_state='ACCESSIBLE' AND (s.status!='COMPLETE' OR s.producer_version!=?)
            ORDER BY COALESCE(m.captured_at,0) DESC, m.id LIMIT ?""".trimIndent(),
        arrayOf(producerVersion, limit.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursorItem(cursor)) } }

    fun embeddingPendingItemsForIds(producerVersion: String, mediaIds: Set<String>, limit: Int): List<GalleryItem> =
        queryScoped(mediaIds, limit) { ids, remaining ->
            readableDatabase.rawQuery(
                """SELECT m.* FROM media_item m
                    JOIN media_index_stage s ON s.media_id=m.id AND s.stage='EMBEDDING'
                    WHERE m.access_state='ACCESSIBLE' AND m.id IN (${ids.joinToString(",") { "?" }})
                    AND (s.status!='COMPLETE' OR s.producer_version!=?)
                    ORDER BY COALESCE(m.captured_at,0) DESC, m.id LIMIT ?""".trimIndent(),
                (ids + producerVersion + remaining.toString()).toTypedArray(),
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

    fun markEmbedding(id: String, producerVersion: String) =
        updateStage(writableDatabase, id, IndexStage.EMBEDDING, StageStatus.RUNNING, producerVersion, incrementAttempt = true)

    fun completeEmbedding(id: String, producerVersion: String) =
        updateStage(writableDatabase, id, IndexStage.EMBEDDING, StageStatus.COMPLETE, producerVersion)

    fun failEmbedding(id: String, producerVersion: String, message: String, permanent: Boolean) = updateStage(
        writableDatabase,
        id,
        IndexStage.EMBEDDING,
        if (permanent) StageStatus.FAILED_PERMANENT else StageStatus.FAILED_RETRYABLE,
        producerVersion,
        error = message,
    )

    fun markIndexing(id: String) {
        val db = writableDatabase
        db.update("media_item", ContentValues().apply {
            put("index_state", IndexState.INDEXING.name)
            putNull("index_error")
        }, "id=?", arrayOf(id))
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
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun failIndex(id: String, message: String, permanent: Boolean) {
        val db = writableDatabase
        db.update("media_item", ContentValues().apply {
            put("index_state", if (permanent) IndexState.FAILED_PERMANENT.name else IndexState.FAILED_RETRYABLE.name)
            put("index_error", message.take(300))
        }, "id=?", arrayOf(id))
        val status = if (permanent) StageStatus.FAILED_PERMANENT else StageStatus.FAILED_RETRYABLE
        listOf(IndexStage.THUMBNAIL, IndexStage.VIDEO_KEYFRAMES, IndexStage.OCR, IndexStage.ENRICHMENT).forEach {
            updateStage(db, id, it, status, "mlkit-mobile-v1", error = message)
        }
    }

    fun recoverInterruptedJobs() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE media_item SET index_state='PENDING' WHERE index_state='INDEXING'")
            db.execSQL("UPDATE media_index_stage SET status='PENDING', updated_at=${System.currentTimeMillis()}, error='process_interrupted' WHERE status='RUNNING'")
            db.execSQL(
                """
                UPDATE semantic_enrichment_job
                SET status='PENDING',
                    attempt_count=CASE WHEN attempt_count > 0 THEN attempt_count - 1 ELSE 0 END,
                    updated_at=${System.currentTimeMillis()},
                    error='process_interrupted'
                WHERE status='RUNNING'
                """.trimIndent(),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
    ): PeopleIndexStatus {
        require(PERSON_ID.matches(id)) { "Invalid local person ID" }
        val safeLabel = label.trim().also { require(it.isNotBlank() && it.length <= 80) { "Invalid person label" } }
        val safeRelationship = relationship?.trim()?.takeIf(String::isNotBlank)?.also {
            require(it.length <= 80) { "Relationship is too long" }
        }
        val safeAliases = aliases.asSequence().map(String::trim).filter(String::isNotBlank).distinct().take(MAX_PERSON_ALIASES + 1).toList()
        require(safeAliases.size <= MAX_PERSON_ALIASES && safeAliases.all { it.length <= 80 }) { "Invalid person aliases" }
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

    fun clusterIdForFace(faceId: String): String? = readableDatabase.rawQuery(
        "SELECT cluster_id FROM face_instance WHERE id=?",
        arrayOf(faceId),
    ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null }

    fun faceClusterReferences(faceIds: List<String>): Map<String, FaceClusterReference> =
        faceIds.distinct().chunked(SQLITE_ID_CHUNK).flatMap { ids ->
            val placeholders = ids.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT f.id,c.id,c.reviewed,c.hidden FROM face_instance f " +
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
            "SELECT c.id, c.label, c.relationship, c.aliases, COUNT(f.id) AS face_count, MAX(f.media_id) AS sample_media_id " +
                ", c.reviewed, c.hidden, c.representative_face_id " +
                "FROM person_cluster c LEFT JOIN face_instance f ON c.id = f.cluster_id " +
                (if (includeHidden) "" else "WHERE c.hidden = 0 ") +
                "GROUP BY c.id, c.label, c.relationship, c.aliases, c.reviewed, c.hidden, c.representative_face_id " +
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
                            sampleMediaId = if (cursor.isNull(5)) null else cursor.getString(5),
                            reviewed = cursor.getInt(6) != 0,
                            hidden = cursor.getInt(7) != 0,
                            representativeFaceId = if (cursor.isNull(8)) null else cursor.getString(8),
                        ),
                    )
                }
            }
        }
        val facesByCluster = personFacesForClusters(
            summaries.mapTo(linkedSetOf(), PersonClusterReviewItem::id),
            limitPerCluster = 4,
        )
        return summaries.map { summary ->
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
                    mediaIds.chunked(SQLITE_ID_CHUNK).forEach { mediaChunk ->
                        val mediaPlaceholders = mediaChunk.joinToString(",") { "?" }
                        db.delete(
                            "person_attribute_fact",
                            "cluster_id=? AND media_id IN ($mediaPlaceholders)",
                            (listOf(clusterId) + mediaChunk).toTypedArray(),
                        )
                    }
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
            db.delete("person_attribute_fact", "media_id=? AND cluster_id=?", arrayOf(source.second, source.first))
            db.setTransactionSuccessful()
            source.first
        } finally {
            db.endTransaction()
        }
    }

    fun removePersonLabel(clusterId: String): PeopleIndexStatus {
        require(PERSON_ID.matches(clusterId)) { "Invalid local person ID" }
        writableDatabase.update("person_cluster", ContentValues().apply {
            putNull("label")
            putNull("relationship")
            put("aliases", "[]")
            put("reviewed", 0)
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(clusterId))
        return peopleIndexStatus()
    }

    fun setPersonClusterHidden(clusterId: String, hidden: Boolean): PeopleIndexStatus {
        require(PERSON_ID.matches(clusterId)) { "Invalid local person ID" }
        writableDatabase.update("person_cluster", ContentValues().apply {
            put("hidden", if (hidden) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(clusterId))
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
            )
            fun identity(clusterId: String): ClusterIdentity = db.rawQuery(
                "SELECT label,relationship,aliases,representative_face_id FROM person_cluster WHERE id=?",
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
                )
            }
            val targetIdentity = identity(targetClusterId)
            val sourceIdentity = identity(sourceClusterId)
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
                put("updated_at", now)
            }, "id=?", arrayOf(targetClusterId))
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

    fun resolveReviewedPersonIds(query: String): Set<String> {
        val normalizedQuery = query.lowercase(Locale.ROOT)
        return readableDatabase.rawQuery(
            "SELECT id,label,relationship,aliases FROM person_cluster WHERE reviewed=1 AND hidden=0",
            null,
        ).use { cursor ->
            buildSet {
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
                    if (terms.any { identityTermMatches(normalizedQuery, it) }) add(id)
                }
            }
        }
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
        val id = "$mediaId:$clusterId:${predicate.lowercase(Locale.ROOT).hashCode().toUInt()}"
        writableDatabase.insertWithOnConflict("person_attribute_fact", null, ContentValues().apply {
            put("id", id)
            put("media_id", mediaId)
            put("cluster_id", clusterId)
            put("predicate", predicate.take(240))
            put("value", value.take(240))
            put("confidence", confidence.coerceIn(0f, 1f))
            put("region", JSONArray(region).toString())
            put("model_version", modelVersion.take(160))
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
        val items = allItems()
        return IndexSummary(
            discovered = items.size,
            metadataReady = items.size,
            semanticFactsReady = readableDatabase.rawQuery(
                "SELECT COUNT(DISTINCT evidence_media_id) FROM semantic_fact",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 },
            ocrReady = items.count { it.source == MediaSource.DEMO_ASSET || it.indexState == IndexState.READY },
            visualLabelsReady = items.count { it.tags.isNotEmpty() },
            videoKeyframesReady = readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM video_keyframe", null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 },
            facesScanned = readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM media_index_stage s JOIN media_item m ON m.id=s.media_id " +
                    "WHERE s.stage='FACES' AND s.status='COMPLETE' AND m.media_kind='IMAGE' " +
                    "AND m.access_state='ACCESSIBLE' AND m.index_state='READY'",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 },
            faceEligible = readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM media_item WHERE media_kind='IMAGE' AND access_state='ACCESSIBLE' AND index_state='READY'",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 },
            pending = items.count { it.indexState == IndexState.PENDING || it.indexState == IndexState.INDEXING },
            events = events().size,
            failed = items.count { it.indexState == IndexState.FAILED_PERMANENT || it.indexState == IndexState.FAILED_RETRYABLE },
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

    fun replaceSemanticEnrichmentPlan(plan: SemanticEnrichmentPlan) {
        writableDatabase.transaction { db ->
            db.delete(
                "semantic_enrichment_job",
                "status IN (?,?,?,?)",
                arrayOf(
                    SemanticEnrichmentStatus.PENDING.name,
                    SemanticEnrichmentStatus.FAILED.name,
                    SemanticEnrichmentStatus.AUTH_REQUIRED.name,
                    SemanticEnrichmentStatus.RUNNING.name,
                ),
            )
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

    fun claimSemanticEnrichmentJob(userRequestedOnly: Boolean = false): SemanticEnrichmentJobRecord? {
        var selected: SemanticEnrichmentJobRecord? = null
        writableDatabase.transaction { db ->
            val where = if (userRequestedOnly) " AND user_requested=1" else ""
            selected = db.rawQuery(
                "SELECT * FROM semantic_enrichment_job WHERE status=?$where ORDER BY user_requested DESC,updated_at LIMIT 1",
                arrayOf(SemanticEnrichmentStatus.PENDING.name),
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

    fun semanticMemoryProgress(): SemanticMemoryProgress {
        val db = readableDatabase
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
            latestError = latestError,
        )
    }

    fun completeSemanticEnrichment(job: SemanticEnrichmentJobRecord, facts: List<SemanticFactRecord>) {
        writableDatabase.transaction { db ->
            val now = System.currentTimeMillis()
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
                        put("scope", if (subjectId == fact.evidenceMediaId) fact.scope.name else SemanticFactScope.MEDIA.name)
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
            db.update("semantic_enrichment_job", ContentValues().apply {
                put("status", SemanticEnrichmentStatus.COMPLETE.name)
                put("model_version", facts.firstOrNull()?.modelVersion)
                putNull("error")
                put("updated_at", now)
            }, "id=?", arrayOf(job.id))
            updateStage(
                db,
                job.representativeMediaId,
                IndexStage.ENRICHMENT,
                StageStatus.COMPLETE,
                facts.firstOrNull()?.modelVersion ?: "adaptive-no-facts-v1",
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

    fun hasAuthenticationProtectedOcr(mediaId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM ocr_entity WHERE media_id=? AND entity_type IN ('PASSWORD','EMAIL','PHONE','ORDER_ID') LIMIT 1",
        arrayOf(mediaId),
    ).use(android.database.Cursor::moveToFirst)

    private fun exactDuplicateMediaIds(db: GallerySqlDatabase, mediaId: String): Set<String> = db.rawQuery(
        "SELECT sibling.media_id FROM visual_group_member source JOIN visual_group g ON g.id=source.group_id JOIN visual_group_member sibling ON sibling.group_id=g.id WHERE source.media_id=? AND g.kind='EXACT_DUPLICATE'",
        arrayOf(mediaId),
    ).use { cursor ->
        buildSet {
            add(mediaId)
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    companion object {
        private const val SQLITE_ID_CHUNK = 800
        const val PEOPLE_CONSENT_VERSION = 1
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
