package com.askphotos.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.MediaStore
import org.json.JSONArray
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
        db.beginTransaction()
        try {
            allItems(db).forEach { item -> initializeStages(db, item, replace = false) }
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

    fun embeddingPendingItems(producerVersion: String, limit: Int): List<GalleryItem> = readableDatabase.rawQuery(
        """SELECT m.* FROM media_item m
            JOIN media_index_stage s ON s.media_id=m.id AND s.stage='EMBEDDING'
            WHERE m.access_state='ACCESSIBLE' AND (s.status!='COMPLETE' OR s.producer_version!=?)
            ORDER BY COALESCE(m.captured_at,0) DESC, m.id LIMIT ?""".trimIndent(),
        arrayOf(producerVersion, limit.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursorItem(cursor)) } }

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
                if (ocrAttempted) "mlkit-text-latin-v2+document-facts-v2" else "ocr-likelihood-gate-v1",
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
            val allowedRoots = listOf("previews", "video-keyframes").map { java.io.File(context.filesDir, it).canonicalFile }
            if (allowedRoots.any { preview.toPath().startsWith(it.toPath()) } && preview.exists() && preview.delete()) previewFilesDeleted++
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
            reviewedClusterCount = count("SELECT COUNT(*) FROM person_cluster WHERE reviewed=1"),
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
                    "WHERE stage='FACES' AND media_id IN (SELECT id FROM media_item WHERE source_kind!='DEMO_ASSET' AND media_kind='IMAGE' AND access_state='ACCESSIBLE' AND index_state='READY')",
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
                put("label", safeLabel)
                if (safeRelationship == null) putNull("relationship") else put("relationship", safeRelationship)
                put("aliases", JSONArray(safeAliases).toString())
                put("reviewed", 1)
                put("created_at", now)
                put("updated_at", now)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return peopleIndexStatus()
    }

    fun facePendingItems(limit: Int): List<GalleryItem> {
        if (!peopleIndexStatus().enabled) return emptyList()
        return queryItems(
            "source_kind!='DEMO_ASSET' AND media_kind='IMAGE' AND access_state='ACCESSIBLE' AND index_state='READY' AND id IN " +
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
            semanticFactsReady = items.count { it.tags.isNotEmpty() },
            ocrReady = items.count { it.source == MediaSource.DEMO_ASSET || it.indexState == IndexState.READY },
            visualLabelsReady = items.count { it.tags.isNotEmpty() },
            videoKeyframesReady = readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM video_keyframe", null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 },
            facesScanned = readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM media_index_stage WHERE stage='FACES' AND status='COMPLETE'",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 },
            pending = items.count { it.indexState == IndexState.PENDING || it.indexState == IndexState.INDEXING },
            events = events().size,
            failed = items.count { it.indexState == IndexState.FAILED_PERMANENT || it.indexState == IndexState.FAILED_RETRYABLE },
            storageBytes = databaseBytes(),
        )
    }

    fun recordQuery(outcome: SearchOutcome, sessionId: String? = null) {
        writableDatabase.insert("query_turn", null, ContentValues().apply {
            put("query", outcome.plan.originalQuery)
            put("plan_summary", "${outcome.plan.intent}:${outcome.plan.terms.joinToString(",")}")
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

    companion object {
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
