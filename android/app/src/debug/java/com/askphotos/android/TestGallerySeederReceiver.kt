package com.askphotos.android

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/** Debug-only MediaStore bridge used by the safe connected-device harness. */
class TestGallerySeederReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        check(BuildConfig.DEBUG)
        val pending = goAsync()
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val runId = requireRunId(intent.getStringExtra(EXTRA_RUN_ID))
                when (intent.action) {
                    ACTION_SEED -> seed(context, runId)
                    ACTION_CLEANUP -> cleanup(context, runId)
                    ACTION_IMPORT -> importSeeded(context, runId)
                    ACTION_REMOVE_IMPORTED -> removeImported(context, runId)
                    ACTION_PREPARE_INTERRUPTION -> prepareIndexInterruption(context, runId)
                    ACTION_VERIFY_RECOVERY -> verifyIndexRecovery(context, runId)
                    else -> error("Unsupported test action")
                }
            } catch (error: Throwable) {
                intent.getStringExtra(EXTRA_RUN_ID)?.takeIf(RUN_ID::matches)?.let { runId ->
                    val failure = JSONObject().put("state", "FAILED").put("error", error.message ?: error.javaClass.simpleName)
                    writeStatus(context, runId, statusName(intent.action), failure)
                }
            } finally {
                pending.finish()
                executor.shutdown()
            }
        }
    }

    private fun seed(context: Context, runId: String) {
        val previousResultFile = child(runRoot(context, runId), "seed-result.json")
        val previousResult = previousResultFile
            .takeIf(File::isFile)
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        val cleanupStatusFile = child(runRoot(context, runId), "cleanup-status.json")
        val cleanupComplete = cleanupStatusFile
            .takeIf(File::isFile)
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            ?.optString("state") == "COMPLETE"
        val activeCompletedSeed = previousResult?.optString("state") == "COMPLETE" &&
            previousResult.optString("runId") == runId &&
            (!cleanupComplete || previousResultFile.lastModified() > cleanupStatusFile.lastModified())
        if (activeCompletedSeed) {
            if (cleanupComplete) clearCompletedCleanupMarker(context, runId)
            writeStatus(context, runId, "status.json", previousResult)
            return
        }
        if (cleanupComplete) clearCompletedCleanupMarker(context, runId)
        val stagingRoot = extractStaging(context, runId)
        val manifestFile = child(stagingRoot, "gallery-manifest.json")
        val mediaRoot = child(stagingRoot, "media")
        val manifest = JSONObject(manifestFile.readText())
        val created = mutableListOf<Uri>()
        val relativePaths = linkedSetOf<String>()
        writeStatus(context, runId, "status.json", JSONObject().put("state", "RUNNING").put("created", 0))
        try {
            val items = manifest.getJSONArray("items")
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val filename = safeFilename(item.getString("filename"))
                val source = child(mediaRoot, filename)
                require(source.isFile) { "Missing staged media: $filename" }
                val mime = mimeType(filename)
                val collection = when {
                    mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val relativePath = if (mime == "application/pdf") {
                    "Documents/AgenticGalleryTest/$runId/"
                } else {
                    "Pictures/AgenticGalleryTest/$runId/"
                }
                relativePaths += relativePath
                val capturedAt = item.optString("captured_at").takeIf { it.isNotBlank() }
                    ?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    capturedAt?.let { put(MediaStore.Images.ImageColumns.DATE_TAKEN, it) }
                }
                val uri = requireNotNull(context.contentResolver.insert(collection, values)) { "MediaStore insert failed for $filename" }
                created += uri
                context.contentResolver.openOutputStream(uri, "w")!!.use { output -> source.inputStream().use { it.copyTo(output) } }
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    // MediaProvider may replace DATE_TAKEN while it scans newly written bytes. Apply the
                    // fixture timestamp again when publishing so the acceptance corpus remains deterministic.
                    capturedAt?.let { put(MediaStore.Images.ImageColumns.DATE_TAKEN, it) }
                }, null, null)
                writeStatus(context, runId, "status.json", JSONObject().put("state", "RUNNING").put("created", created.size).put("total", items.length()))
            }
            val result = JSONObject()
                .put("state", "COMPLETE")
                .put("runId", runId)
                .put("relativePaths", JSONArray(relativePaths.toList()))
                .put("createdUris", JSONArray(created.map(Uri::toString)))
                .put("createdCount", created.size)
            require(stagingRoot.deleteRecursively()) { "Could not remove app-private seed staging" }
            require(inputRoot(context, runId).deleteRecursively()) { "Could not remove app-private seed input" }
            result.put("stagingRemoved", !stagingRoot.exists() && !inputRoot(context, runId).exists())
            writeStatus(context, runId, "seed-result.json", result)
            writeStatus(context, runId, "status.json", result)
        } catch (error: Throwable) {
            created.asReversed().forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
            runCatching { stagingRoot.deleteRecursively() }
            runCatching { inputRoot(context, runId).deleteRecursively() }
            throw error
        }
    }

    private fun clearCompletedCleanupMarker(context: Context, runId: String) {
        listOf("cleanup-status.json", "cleanup-result.json").forEach { name ->
            val file = child(runRoot(context, runId), name)
            require(!file.exists() || file.delete()) { "Could not clear stale $name" }
        }
    }

    private fun cleanup(context: Context, runId: String) {
        val resultFile = child(runRoot(context, runId), "seed-result.json")
        require(resultFile.isFile) { "No seed result exists for run $runId" }
        val seed = JSONObject(resultFile.readText())
        require(seed.getString("runId") == runId)
        val uris = seed.getJSONArray("createdUris")
        var deleted = 0
        for (index in 0 until uris.length()) {
            val uri = Uri.parse(uris.getString(index))
            require(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) { "Refusing non-MediaStore URI" }
            deleted += context.contentResolver.delete(uri, null, null)
        }
        val recovered = ownedRunUris(context, runId)
        val recoveryRecord = JSONObject()
            .put("state", "RECOVERED")
            .put("runId", runId)
            .put("createdUris", JSONArray(recovered.map(Uri::toString)))
            .put("createdCount", recovered.size)
            .put("proof", "exact run-scoped path and owner_package_name=${context.packageName}")
        writeStatus(context, runId, "orphan-recovery.json", recoveryRecord)
        var recoveredDeleted = 0
        recovered.forEach { uri -> recoveredDeleted += context.contentResolver.delete(uri, null, null) }
        val relativePaths = seed.getJSONArray("relativePaths")
        var remaining = 0
        for (index in 0 until relativePaths.length()) {
            val relativePath = relativePaths.getString(index)
            require(relativePath == "Pictures/AgenticGalleryTest/$runId/" || relativePath == "Documents/AgenticGalleryTest/$runId/")
            remaining += context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(relativePath),
                null,
            )?.use { it.count } ?: 0
        }
        val cleanup = JSONObject().put("state", "COMPLETE").put("runId", runId)
            .put("relativePaths", relativePaths).put("requestedCount", uris.length())
            .put("deletedCount", deleted).put("recoveredOrphanCount", recovered.size)
            .put("recoveredOrphanDeletedCount", recoveredDeleted).put("remainingCount", remaining)
        writeStatus(context, runId, "cleanup-result.json", cleanup)
        writeStatus(context, runId, "cleanup-status.json", cleanup)
    }

    private fun ownedRunUris(context: Context, runId: String): List<Uri> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val paths = listOf(
            "Pictures/AgenticGalleryTest/$runId/",
            "Documents/AgenticGalleryTest/$runId/",
        )
        return paths.flatMap { relativePath ->
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                arrayOf(relativePath, context.packageName),
                null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0)))
                }
            }.orEmpty()
        }
    }

    private fun importSeeded(context: Context, runId: String) {
        val uris = seededUris(context, runId)
        writeStatus(context, runId, "import-status.json", JSONObject().put("state", "RUNNING"))
        val repository = (context.applicationContext as AskPhotosApplication).repository
        val changed = repository.importUris(uris, MediaSource.MEDIA_STORE)
        val expected = uris.map(Uri::toString).toSet()
        val imported = repository.allItems().count { it.contentUri in expected }
        require(imported == expected.size) { "Imported $imported of ${expected.size} seeded items" }
        writeStatus(
            context,
            runId,
            "import-status.json",
            JSONObject().put("state", "COMPLETE").put("runId", runId)
                .put("requestedCount", expected.size).put("changedCount", changed).put("importedCount", imported),
        )
    }

    private fun removeImported(context: Context, runId: String) {
        val uris = seededUris(context, runId)
        writeStatus(context, runId, "db-cleanup-status.json", JSONObject().put("state", "RUNNING"))
        val repository = (context.applicationContext as AskPhotosApplication).repository
        val result = repository.removeImportedUris(uris, "test_cleanup:$runId")
        val expected = uris.map(Uri::toString).toSet()
        val remaining = repository.allItems().count { it.contentUri in expected }
        require(remaining == 0) { "$remaining seeded database rows remain" }
        writeStatus(
            context,
            runId,
            "db-cleanup-status.json",
            JSONObject().put("state", "COMPLETE").put("runId", runId)
                .put("requestedCount", result.requestedUris).put("matchedCount", result.matchedItems)
                .put("deletedCount", result.deletedItems).put("tombstonesWritten", result.tombstonesWritten)
                .put("previewFilesDeleted", result.previewFilesDeleted).put("remainingCount", remaining),
        )
    }

    private fun seededUris(context: Context, runId: String): List<Uri> {
        val resultFile = child(runRoot(context, runId), "seed-result.json")
        require(resultFile.isFile) { "No seed result exists for run $runId" }
        val seed = JSONObject(resultFile.readText())
        require(seed.getString("runId") == runId)
        val values = seed.getJSONArray("createdUris")
        return List(values.length()) { index ->
            Uri.parse(values.getString(index)).also { uri ->
                require(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) { "Refusing non-MediaStore URI" }
            }
        }
    }

    private fun prepareIndexInterruption(context: Context, runId: String) {
        val uris = seededUris(context, runId)
        val expected = uris.map(Uri::toString).toSet()
        val repository = (context.applicationContext as AskPhotosApplication).repository
        IndexScheduler.cancelAndWait(context)
        val idempotentChanged = repository.importUris(uris, MediaSource.MEDIA_STORE)
        require(idempotentChanged == 0) { "Repeat import changed $idempotentChanged rows" }
        IndexScheduler.cancelAndWait(context)
        val imported = repository.allItems().filter { it.contentUri in expected }
        require(imported.size == expected.size) { "Expected ${expected.size} unique rows, found ${imported.size}" }
        val interrupted = imported.first()
        repository.markIndexing(interrupted.id)
        val runningStages = repository.stageRecords(interrupted.id).count { it.status == StageStatus.RUNNING }
        require(runningStages > 0) { "No running stage was persisted" }
        writeStatus(
            context,
            runId,
            "recovery-prepare-status.json",
            JSONObject().put("state", "COMPLETE").put("runId", runId).put("mediaId", interrupted.id)
                .put("uniqueRows", imported.size).put("idempotentChangedCount", idempotentChanged)
                .put("runningStages", runningStages),
        )
    }

    private fun verifyIndexRecovery(context: Context, runId: String) {
        val uris = seededUris(context, runId)
        val expected = uris.map(Uri::toString).toSet()
        val repository = (context.applicationContext as AskPhotosApplication).repository
        repository.recoverInterruptedJobs()
        val imported = repository.allItems().filter { it.contentUri in expected }
        require(imported.size == expected.size) { "Recovery changed row count: ${imported.size}" }
        require(imported.map { it.id }.toSet().size == expected.size) { "Recovery produced duplicate stable IDs" }
        val stages = imported.flatMap { repository.stageRecords(it.id) }
        require(stages.size == expected.size * IndexStage.entries.size) { "Expected ${expected.size * IndexStage.entries.size} stages, found ${stages.size}" }
        require(stages.none { it.status == StageStatus.RUNNING }) { "Recovery left a RUNNING stage" }
        require(imported.none { it.indexState == IndexState.INDEXING }) { "Recovery left an INDEXING media row" }
        writeStatus(
            context,
            runId,
            "recovery-verify-status.json",
            JSONObject().put("state", "COMPLETE").put("runId", runId)
                .put("uniqueRows", imported.size).put("stageRows", stages.size)
                .put("runningStages", 0).put("indexingRows", 0),
        )
    }

    private fun statusName(action: String?): String = when (action) {
        ACTION_IMPORT -> "import-status.json"
        ACTION_REMOVE_IMPORTED -> "db-cleanup-status.json"
        ACTION_PREPARE_INTERRUPTION -> "recovery-prepare-status.json"
        ACTION_VERIFY_RECOVERY -> "recovery-verify-status.json"
        ACTION_CLEANUP -> "cleanup-status.json"
        else -> "status.json"
    }

    private fun writeStatus(context: Context, runId: String, name: String, value: JSONObject) {
        child(runRoot(context, runId).apply { mkdirs() }, name).writeText(value.toString())
    }

    private fun runRoot(context: Context, runId: String): File = child(File(context.filesDir, "test-seed"), requireRunId(runId))

    private fun stagingRoot(context: Context, runId: String): File = child(runRoot(context, runId), "staging")

    private fun inputRoot(context: Context, runId: String): File =
        child(File(context.filesDir, "test-seed-input"), requireRunId(runId))

    private fun extractStaging(context: Context, runId: String): File {
        val input = child(inputRoot(context, runId), "gallery.zip")
        require(input.isFile && input.length() in 1..MAX_ARCHIVE_BYTES) { "Missing or oversized seed archive" }
        val staging = stagingRoot(context, runId)
        runCatching { staging.deleteRecursively() }
        staging.mkdirs()
        var entries = 0
        var extractedBytes = 0L
        try {
            ZipInputStream(input.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries += 1
                    require(entries <= MAX_ARCHIVE_ENTRIES) { "Seed archive has too many entries" }
                    val target = child(staging, entry.name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        require(entry.name == "gallery-manifest.json" || entry.name.startsWith("media/")) { "Unexpected seed archive entry" }
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                extractedBytes += count
                                require(extractedBytes <= MAX_EXTRACTED_BYTES) { "Seed archive expands beyond limit" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(child(staging, "gallery-manifest.json").isFile) { "Seed archive has no gallery manifest" }
            return staging
        } catch (error: Throwable) {
            runCatching { staging.deleteRecursively() }
            runCatching { inputRoot(context, runId).deleteRecursively() }
            throw error
        }
    }

    private fun child(parent: File, name: String): File {
        val file = File(parent, name).canonicalFile
        require(file.toPath().startsWith(parent.canonicalFile.toPath())) { "Path escaped test staging root" }
        return file
    }

    private fun safeFilename(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9._-]{1,180}"))) { "Unsafe staged filename" }
        return value
    }

    private fun mimeType(filename: String): String = when (filename.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "pdf" -> "application/pdf"
        "mp4" -> "video/mp4"
        else -> error("Unsupported test media type")
    }

    private fun requireRunId(value: String?): String = requireNotNull(value).also {
        require(RUN_ID.matches(it)) { "Invalid gallery run ID" }
    }

    companion object {
        const val ACTION_SEED = "com.askphotos.android.test.SEED_GALLERY"
        const val ACTION_CLEANUP = "com.askphotos.android.test.CLEANUP_GALLERY"
        const val ACTION_IMPORT = "com.askphotos.android.test.IMPORT_SEEDED"
        const val ACTION_REMOVE_IMPORTED = "com.askphotos.android.test.REMOVE_IMPORTED"
        const val ACTION_PREPARE_INTERRUPTION = "com.askphotos.android.test.PREPARE_INDEX_INTERRUPTION"
        const val ACTION_VERIFY_RECOVERY = "com.askphotos.android.test.VERIFY_INDEX_RECOVERY"
        const val EXTRA_RUN_ID = "run_id"
        val RUN_ID = Regex("[A-Za-z0-9_-]{6,64}")
        const val MAX_ARCHIVE_ENTRIES = 256
        const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
        const val MAX_EXTRACTED_BYTES = 1024L * 1024 * 1024
    }
}
