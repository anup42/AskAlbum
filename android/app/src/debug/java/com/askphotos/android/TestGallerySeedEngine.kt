package com.askphotos.android

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** Durable, debug-only MediaStore seeder. All shared-storage writes are scoped to one validated run ID. */
internal class TestGallerySeedEngine(
    context: Context,
    private val onProgress: (created: Int, total: Int) -> Unit = { _, _ -> },
) {
    private val applicationContext = context.applicationContext
    private val resolver = applicationContext.contentResolver

    suspend fun seed(runIdValue: String): JSONObject {
        val runId = requireRunId(runIdValue)
        completedResult(runId)?.let { return it }
        clearCompletedCleanupMarker(runId)
        writeStatus(runId, running(runId, "EXTRACTING", 0, null))
        val staging = prepareImmutableStaging(runId)
        val mediaRoot = child(staging, "media")
        val items = JSONObject(child(staging, "gallery-manifest.json").readText()).getJSONArray("items")
        require(items.length() in 1 until MAX_ARCHIVE_ENTRIES) { "Invalid seed item count" }
        val expectedNames = HashSet<String>(items.length())
        repeat(items.length()) { index ->
            val filename = safeFilename(items.getJSONObject(index).getString("filename"))
            require(expectedNames.add(filename)) { "Duplicate staged filename: $filename" }
        }

        val rows = ownedRows(runId).groupByTo(mutableMapOf()) { rowKey(it.relativePath, it.name) }
        var reused = 0
        for (index in 0 until items.length()) {
            coroutineContext.ensureActive()
            val item = items.getJSONObject(index)
            val filename = safeFilename(item.getString("filename"))
            val source = child(mediaRoot, filename)
            require(source.isFile) { "Missing staged media: $filename" }
            val mime = mimeType(filename)
            val relativePath = relativePath(runId, mime)
            val key = rowKey(relativePath, filename)
            val candidates = rows[key].orEmpty()
            val valid = candidates.singleOrNull()?.takeIf { it.pending == 0 && it.size == source.length() }
            val uri = if (valid != null) {
                reused += 1
                valid.uri
            } else {
                candidates.forEach { resolver.delete(it.uri, null, null) }
                insertItem(source, filename, mime, relativePath, item)
            }
            rows[key] = mutableListOf(MediaRow(uri, filename, source.length(), 0, relativePath))
            val completed = index + 1
            if (completed % CHECKPOINT_INTERVAL == 0 || completed == items.length()) {
                writeCheckpoint(runId, completed, items.length(), reused, filename)
                writeStatus(runId, running(runId, "SEEDING", completed, items.length()).put("reused", reused))
                onProgress(completed, items.length())
            }
        }

        val finalRows = ownedRows(runId)
        require(finalRows.size == items.length()) { "Expected ${items.length()} app-owned rows, found ${finalRows.size}" }
        val finalByKey = finalRows.groupBy { rowKey(it.relativePath, it.name) }
        val orderedUris = ArrayList<String>(items.length())
        repeat(items.length()) { index ->
            val item = items.getJSONObject(index)
            val filename = safeFilename(item.getString("filename"))
            val mime = mimeType(filename)
            val matches = finalByKey[rowKey(relativePath(runId, mime), filename)].orEmpty()
            require(matches.size == 1 && matches.single().pending == 0) { "Seed row is missing or duplicated: $filename" }
            orderedUris += matches.single().uri.toString()
        }
        val paths = listOf(PICTURE_ROOT.format(runId), DOCUMENT_ROOT.format(runId))
            .filter { path -> finalRows.any { it.relativePath == path } }
        val result = JSONObject()
            .put("state", "COMPLETE")
            .put("runId", runId)
            .put("relativePaths", JSONArray(paths))
            .put("createdUris", JSONArray(orderedUris))
            .put("createdCount", orderedUris.size)
            .put("reusedCount", reused)
            .put("recovered", reused > 0)
            .put("stagingRemoved", false)
        writeStatus(runId, result, "seed-result.json")
        writeStatus(runId, result)
        require(deleteExactTree(stagingRoot(runId))) { "Could not remove app-private seed staging" }
        require(deleteExactTree(inputRoot(runId))) { "Could not remove app-private seed input" }
        child(runRoot(runId), "checkpoint.json").delete()
        result.put("stagingRemoved", !stagingRoot(runId).exists() && !inputRoot(runId).exists())
        writeStatus(runId, result, "seed-result.json")
        writeStatus(runId, result)
        return result
    }

    fun writeFailure(runIdValue: String, error: Throwable) {
        val runId = requireRunId(runIdValue)
        writeStatus(
            runId,
            JSONObject().put("state", "FAILED").put("runId", runId)
                .put("resumable", true).put("error", error.message ?: error.javaClass.simpleName),
        )
    }

    private fun completedResult(runId: String): JSONObject? {
        val resultFile = child(runRoot(runId), "seed-result.json")
        val result = resultFile.takeIf(File::isFile)?.let { runCatching { JSONObject(it.readText()) }.getOrNull() } ?: return null
        if (result.optString("state") != "COMPLETE" || result.optString("runId") != runId) return null
        val cleanupFile = child(runRoot(runId), "cleanup-status.json")
        val cleanedAfterResult = cleanupFile.isFile && cleanupFile.lastModified() >= resultFile.lastModified() &&
            runCatching { JSONObject(cleanupFile.readText()).optString("state") == "COMPLETE" }.getOrDefault(false)
        if (cleanedAfterResult) return null
        val count = ownedRows(runId).size
        if (count != result.optInt("createdCount", -1)) return null
        deleteExactTree(stagingRoot(runId))
        deleteExactTree(inputRoot(runId))
        result.put("stagingRemoved", !stagingRoot(runId).exists() && !inputRoot(runId).exists())
        writeStatus(runId, result, "seed-result.json")
        writeStatus(runId, result)
        return result
    }

    private fun clearCompletedCleanupMarker(runId: String) {
        listOf("cleanup-status.json", "cleanup-result.json").forEach { name ->
            val file = child(runRoot(runId), name)
            require(!file.exists() || file.delete()) { "Could not clear stale $name" }
        }
    }

    private fun prepareImmutableStaging(runId: String): File {
        val input = child(inputRoot(runId), "gallery.zip")
        require(input.isFile && input.length() in 1..MAX_ARCHIVE_BYTES) { "Missing or oversized seed archive" }
        val transfer = JSONObject(child(inputRoot(runId), "transfer.json").readText())
        require(transfer.optString("state") == "COMPLETE") { "Seed archive transfer is incomplete" }
        val fingerprint = "${input.length()}:${transfer.getString("sha256")}" 
        val staging = stagingRoot(runId)
        val marker = child(staging, GENERATION_FILE)
        val reusable = marker.isFile && child(staging, "gallery-manifest.json").isFile &&
            runCatching { JSONObject(marker.readText()).optString("fingerprint") == fingerprint }.getOrDefault(false)
        if (reusable) return staging

        val building = child(runRoot(runId), "staging.building")
        deleteExactTree(building)
        require(building.mkdirs()) { "Could not create immutable staging generation" }
        var entries = 0
        var extractedBytes = 0L
        try {
            ZipInputStream(input.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries += 1
                    require(entries <= MAX_ARCHIVE_ENTRIES) { "Seed archive has too many entries" }
                    val target = child(building, entry.name)
                    if (entry.isDirectory) {
                        require(target.exists() || target.mkdirs())
                    } else {
                        require(entry.name == "gallery-manifest.json" || entry.name.startsWith("media/")) { "Unexpected seed archive entry" }
                        target.parentFile?.let { require(it.exists() || it.mkdirs()) }
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
            require(child(building, "gallery-manifest.json").isFile) { "Seed archive has no gallery manifest" }
            writeJsonAtomic(
                child(building, GENERATION_FILE),
                JSONObject().put("fingerprint", fingerprint).put("entries", entries).put("extractedBytes", extractedBytes),
            )
            deleteExactTree(staging)
            moveAtomically(building, staging)
            return staging
        } catch (error: Throwable) {
            deleteExactTree(building)
            throw error
        }
    }

    private fun insertItem(
        source: File,
        filename: String,
        mime: String,
        relativePath: String,
        item: JSONObject,
    ): Uri {
        val collection = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val capturedAt = item.optString("captured_at").takeIf(String::isNotBlank)
            ?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            capturedAt?.let { put(MediaStore.Images.ImageColumns.DATE_TAKEN, it) }
        }
        val uri = requireNotNull(resolver.insert(collection, values)) { "MediaStore insert failed for $filename" }
        try {
            resolver.openOutputStream(uri, "w")!!.use { output -> source.inputStream().buffered().use { it.copyTo(output) } }
            val changed = resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                capturedAt?.let { put(MediaStore.Images.ImageColumns.DATE_TAKEN, it) }
            }, null, null)
            require(changed == 1) { "Could not publish $filename" }
            return uri
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun ownedRows(runId: String): List<MediaRow> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return listOf(PICTURE_ROOT.format(runId), DOCUMENT_ROOT.format(runId)).flatMap { relativePath ->
            resolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.IS_PENDING,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                arrayOf(relativePath, applicationContext.packageName),
                null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            MediaRow(
                                ContentUris.withAppendedId(collection, cursor.getLong(0)),
                                cursor.getString(1),
                                cursor.getLong(2),
                                cursor.getInt(3),
                                cursor.getString(4),
                            ),
                        )
                    }
                }
            }.orEmpty()
        }
    }

    private fun writeCheckpoint(runId: String, completed: Int, total: Int, reused: Int, filename: String) {
        writeJsonAtomic(
            child(runRoot(runId), "checkpoint.json"),
            JSONObject().put("state", "CHECKPOINTED").put("runId", runId)
                .put("completed", completed).put("total", total).put("reused", reused).put("lastFilename", filename),
        )
    }

    private fun running(runId: String, phase: String, created: Int, total: Int?): JSONObject =
        JSONObject().put("state", "RUNNING").put("runId", runId).put("phase", phase).put("created", created)
            .also { if (total != null) it.put("total", total) }

    private fun writeStatus(runId: String, value: JSONObject, name: String = "status.json") {
        writeJsonAtomic(child(runRoot(runId), name), value)
    }

    private fun writeJsonAtomic(target: File, value: JSONObject) {
        target.parentFile?.let { require(it.exists() || it.mkdirs()) }
        val temporary = File(target.parentFile, "${target.name}.writing")
        temporary.writeText(value.toString())
        moveAtomically(temporary, target)
    }

    private fun moveAtomically(source: File, target: File) {
        runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun runRoot(runId: String): File = child(File(applicationContext.filesDir, "test-seed"), requireRunId(runId))
    private fun stagingRoot(runId: String): File = child(runRoot(runId), "staging")
    private fun inputRoot(runId: String): File = child(File(applicationContext.filesDir, "test-seed-input"), requireRunId(runId))

    private fun child(parent: File, name: String): File {
        val file = File(parent, name).canonicalFile
        require(file.toPath().startsWith(parent.canonicalFile.toPath())) { "Path escaped test seed root" }
        return file
    }

    private fun deleteExactTree(target: File): Boolean = !target.exists() || target.deleteRecursively()
    private fun requireRunId(value: String): String = value.also { require(TestGallerySeederReceiver.RUN_ID.matches(it)) }
    private fun safeFilename(value: String): String = value.also {
        require(it.matches(Regex("[A-Za-z0-9._-]{1,180}"))) { "Unsafe staged filename" }
    }

    private fun mimeType(filename: String): String = when (filename.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "pdf" -> "application/pdf"
        "mp4" -> "video/mp4"
        else -> error("Unsupported test media type")
    }

    private fun relativePath(runId: String, mime: String): String =
        if (mime == "application/pdf") DOCUMENT_ROOT.format(runId) else PICTURE_ROOT.format(runId)

    private fun rowKey(relativePath: String, name: String): String = "$relativePath\u0000$name"

    private data class MediaRow(
        val uri: Uri,
        val name: String,
        val size: Long,
        val pending: Int,
        val relativePath: String,
    )

    private companion object {
        const val CHECKPOINT_INTERVAL = 25
        const val GENERATION_FILE = "seed-generation.json"
        const val MAX_ARCHIVE_ENTRIES = 20_001
        const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
        const val MAX_EXTRACTED_BYTES = 1024L * 1024 * 1024
        const val PICTURE_ROOT = "Pictures/AgenticGalleryTest/%s/"
        const val DOCUMENT_ROOT = "Documents/AgenticGalleryTest/%s/"
    }
}
