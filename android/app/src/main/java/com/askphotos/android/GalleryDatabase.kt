package com.askphotos.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.MediaStore
import org.json.JSONArray
import java.util.Calendar

class GalleryDatabase(private val context: Context) {
    private val room = GalleryRoomDatabase.open(context)
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
                val galleryItem = GalleryItem(
                    id = item.getString("id"),
                    filename = item.getString("filename"),
                    title = item.getString("title"),
                    creator = item.optString("creator").takeIf { it.isNotBlank() && it != "null" },
                    location = item.optString("location_name", "Unknown location"),
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
                    existing.modifiedAt == imported.modifiedAt && existing.sizeBytes == imported.sizeBytes
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
                    latitude = null,
                    longitude = null,
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
            updateStage(db, id, IndexStage.FACES, StageStatus.SKIPPED, "disabled-until-opt-in")
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
        listOf(IndexStage.THUMBNAIL, IndexStage.OCR, IndexStage.ENRICHMENT).forEach {
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
            val allowedRoot = java.io.File(context.filesDir, "previews").canonicalFile
            if (preview.toPath().startsWith(allowedRoot.toPath()) && preview.exists() && preview.delete()) previewFilesDeleted++
        }
        rebuildEvents()
        return MediaRemovalResult(requested.size, matched, deleted, tombstones, previewFilesDeleted)
    }

    fun tombstoneCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM media_tombstone", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
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
        val grouped = ready.groupBy { dayStart(it.capturedAt!!) }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("event_media", null, null)
            db.delete("gallery_event", null, null)
            grouped.toSortedMap().forEach { (day, members) ->
                val title = members.flatMap { it.tags }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    ?.replaceFirstChar(Char::uppercase) ?: "Gallery day"
                val eventId = db.insertOrThrow("gallery_event", null, ContentValues().apply {
                    put("day_start", day)
                    put("title", title)
                    put("member_count", members.size)
                })
                members.forEach { member ->
                    db.insert("event_media", null, ContentValues().apply {
                        put("event_id", eventId)
                        put("media_id", member.id)
                    })
                }
            }
            ready.forEach { updateStage(db, it.id, IndexStage.EVENTS, StageStatus.COMPLETE, "day-event-v1") }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun events(): List<EventRecord> = readableDatabase.query("gallery_event", null, null, null, null, null, "day_start DESC").use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                EventRecord(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    dayStart = cursor.getLong(cursor.getColumnIndexOrThrow("day_start")),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    memberCount = cursor.getInt(cursor.getColumnIndexOrThrow("member_count")),
                ),
            )
        }
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
            facesScanned = items.count { it.source == MediaSource.DEMO_ASSET || it.indexState == IndexState.READY },
            pending = items.count { it.indexState == IndexState.PENDING || it.indexState == IndexState.INDEXING },
            events = events().size,
            failed = items.count { it.indexState == IndexState.FAILED_PERMANENT || it.indexState == IndexState.FAILED_RETRYABLE },
            storageBytes = databaseBytes(),
        )
    }

    fun recordQuery(outcome: SearchOutcome) {
        writableDatabase.insert("query_turn", null, ContentValues().apply {
            put("query", outcome.plan.originalQuery)
            put("plan_summary", "${outcome.plan.intent}:${outcome.plan.terms.joinToString(",")}")
            put("result_count", outcome.hits.size)
            put("elapsed_ms", outcome.elapsedMs)
            put("created_at", System.currentTimeMillis())
        })
    }

    fun databaseBytes(): Long = context.getDatabasePath(GalleryRoomDatabase.NAME).length()

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

    private fun mediaKind(mimeType: String) = when {
        mimeType == "application/pdf" -> MediaKind.PDF
        mimeType.startsWith("video/") -> MediaKind.VIDEO
        else -> MediaKind.IMAGE
    }

    private fun initializeStages(db: GallerySqlDatabase, item: GalleryItem, replace: Boolean) {
        val complete = item.source == MediaSource.DEMO_ASSET || item.indexState == IndexState.READY
        val values = mapOf(
            IndexStage.DISCOVERY to StageStatus.COMPLETE,
            IndexStage.METADATA to StageStatus.COMPLETE,
            IndexStage.THUMBNAIL to if (complete) StageStatus.COMPLETE else StageStatus.PENDING,
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

    private fun dayStart(timestamp: Long): Long = Calendar.getInstance().run {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    private companion object {
        const val TAG_SEPARATOR = "\u001F"
    }
}

private object UriSafety {
    fun isMediaContentUri(value: String): Boolean = runCatching {
        val uri = android.net.Uri.parse(value)
        uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY
    }.getOrDefault(false)
}
