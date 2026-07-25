package com.samsung.agenticgallery

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/** Debug-only, write-only bridge into one validated app-private seed archive. */
class TestSeedContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = BuildConfig.DEBUG

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        throw FileNotFoundException("Binary stdin transport is disabled; use validated provider calls")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val runId = runIdFrom(uri)
        val root = inputRoot(runId)
        val external = externalArchive(runId)
        val existed = root.exists() || external.exists()
        require(!root.exists() || root.deleteRecursively()) { "Could not delete test seed input" }
        require(!external.exists() || external.delete()) { "Could not delete external test seed input" }
        return if (existed) 1 else 0
    }

    override fun getType(uri: Uri): String = "application/zip"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = when (method) {
        "init" -> initializeTransfer(requireRunId(arg), requireNotNull(extras))
        "write_chunk" -> writeChunk(requireRunId(arg), requireNotNull(extras))
        "prepare_external" -> prepareExternal(requireRunId(arg))
        "adopt_external" -> adoptExternal(requireRunId(arg), requireNotNull(extras))
        "finalize" -> finalizeTransfer(requireRunId(arg))
        "abort" -> abort(requireRunId(arg))
        else -> error("Unsupported test seed provider method")
    }

    private fun prepareExternal(runId: String): Bundle {
        val archive = externalArchive(runId)
        archive.parentFile?.mkdirs()
        require(!archive.exists() || archive.delete()) { "Could not replace external test seed input" }
        return Bundle().apply {
            putString("state", "READY")
            putString("path", archive.absolutePath)
        }
    }

    private fun adoptExternal(runId: String, extras: Bundle): Bundle {
        val totalBytes = requireNotNull(extras.getString("total_bytes")).toLong()
        val expectedSha256 = requireNotNull(extras.getString("sha256")).lowercase()
        require(totalBytes in 1..MAX_TRANSFER_BYTES)
        require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
        val external = externalArchive(runId)
        require(external.isFile && external.length() == totalBytes) {
            "External archive has ${if (external.isFile) external.length() else -1} bytes; expected $totalBytes"
        }
        require(sha256(external) == expectedSha256) { "External archive SHA-256 mismatch" }
        val root = inputRoot(runId)
        if (root.exists()) require(root.deleteRecursively())
        require(root.mkdirs())
        val adopting = File(root, "gallery.adopting")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        external.inputStream().buffered().use { input ->
            adopting.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    copied += count
                }
            }
        }
        val copiedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        require(copied == totalBytes && copiedSha256 == expectedSha256) { "Private archive copy did not verify" }
        val archive = File(root, "gallery.zip")
        require(adopting.renameTo(archive)) { "Could not adopt external seed archive" }
        require(external.delete()) { "Could not delete adopted external seed archive" }
        File(root, "transfer.json").writeText(
            JSONObject().put("state", "COMPLETE").put("transport", "external_file")
                .put("totalBytes", totalBytes).put("sha256", copiedSha256).toString(),
        )
        return completedBundle(totalBytes, copiedSha256)
    }

    private fun abort(runId: String): Bundle = Bundle().apply {
        val root = inputRoot(runId)
        val external = externalArchive(runId)
        putBoolean("deleted", (!root.exists() || root.deleteRecursively()) && (!external.exists() || external.delete()))
        putString("state", "ABORTED")
    }

    private fun initializeTransfer(runId: String, extras: Bundle): Bundle {
        val totalBytes = requireNotNull(extras.getString("total_bytes")).toLong()
        val chunkSize = requireNotNull(extras.getString("chunk_size")).toInt()
        val chunkCount = requireNotNull(extras.getString("chunk_count")).toInt()
        val sha256 = requireNotNull(extras.getString("sha256")).lowercase()
        require(totalBytes in 1..MAX_TRANSFER_BYTES)
        require(chunkSize in 1..MAX_CHUNK_BYTES)
        require(chunkCount == ((totalBytes + chunkSize - 1) / chunkSize).toInt())
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        val root = inputRoot(runId)
        val metadataFile = File(root, "transfer.json")
        val existing = metadataFile.takeIf(File::isFile)?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        val matches = existing != null &&
            existing.optLong("totalBytes") == totalBytes &&
            existing.optInt("chunkSize") == chunkSize &&
            existing.optInt("chunkCount") == chunkCount &&
            existing.optString("sha256") == sha256
        if (!matches) {
            if (root.exists()) require(root.deleteRecursively())
            File(root, "chunks").mkdirs()
            metadataFile.writeText(
                JSONObject()
                    .put("state", "READY")
                    .put("totalBytes", totalBytes)
                    .put("chunkSize", chunkSize)
                    .put("chunkCount", chunkCount)
                    .put("sha256", sha256)
                    .toString(),
            )
        }
        val metadata = JSONObject(metadataFile.readText())
        val complete = metadata.optString("state") == "COMPLETE" && verifiedArchive(root, totalBytes, sha256)
        val bitmap = if (complete) {
            "1".repeat(chunkCount)
        } else {
            buildString(chunkCount) {
                repeat(chunkCount) { index ->
                    val expectedLength = expectedChunkLength(totalBytes, chunkSize, chunkCount, index)
                    val part = File(root, "chunks/${"%05d".format(index)}.part")
                    append(if (part.isFile && part.length() == expectedLength) '1' else '0')
                }
            }
        }
        return Bundle().apply {
            putString("state", if (complete) "COMPLETE" else "READY")
            putString("present_bitmap", bitmap)
        }
    }

    private fun writeChunk(runId: String, extras: Bundle): Bundle {
        val root = inputRoot(runId)
        val metadata = JSONObject(File(root, "transfer.json").readText())
        require(metadata.optString("state") == "READY") { "Transfer is not writable" }
        val totalBytes = metadata.getLong("totalBytes")
        val chunkSize = metadata.getInt("chunkSize")
        val chunkCount = metadata.getInt("chunkCount")
        val index = requireNotNull(extras.getString("index")).toInt()
        require(index in 0 until chunkCount) { "Chunk index out of range" }
        val expectedLength = expectedChunkLength(totalBytes, chunkSize, chunkCount, index)
        require(requireNotNull(extras.getString("expected_length")).toLong() == expectedLength)
        val expectedSha256 = requireNotNull(extras.getString("sha256")).lowercase()
        require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
        val encoded = requireNotNull(extras.getString("data"))
        val decoded = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)
        require(decoded.size.toLong() == expectedLength) { "Decoded chunk length mismatch" }
        val actualSha256 = MessageDigest.getInstance("SHA-256").digest(decoded)
            .joinToString("") { "%02x".format(it) }
        require(actualSha256 == expectedSha256) { "Decoded chunk SHA-256 mismatch" }
        val chunks = File(root, "chunks").apply { mkdirs() }
        val part = File(chunks, "%05d.part".format(index))
        val staging = File(chunks, "%05d.importing".format(index))
        staging.writeBytes(decoded)
        require(staging.length() == expectedLength)
        if (part.exists()) require(part.delete())
        require(staging.renameTo(part)) { "Could not finalize chunk $index" }
        return Bundle().apply {
            putString("state", "WRITTEN")
            putInt("index", index)
            putLong("size", expectedLength)
            putString("sha256", actualSha256)
        }
    }

    private fun finalizeTransfer(runId: String): Bundle {
        val root = inputRoot(runId)
        val metadata = JSONObject(File(root, "transfer.json").readText())
        val totalBytes = metadata.getLong("totalBytes")
        val expectedSha256 = metadata.getString("sha256")
        if (metadata.optString("state") == "COMPLETE") {
            require(verifiedArchive(root, totalBytes, expectedSha256)) { "Completed seed archive no longer verifies" }
            return completedBundle(totalBytes, expectedSha256)
        }
        val chunkSize = metadata.getInt("chunkSize")
        val chunkCount = metadata.getInt("chunkCount")
        val digest = MessageDigest.getInstance("SHA-256")
        val assembling = File(root, "gallery.assembling")
        var written = 0L
        assembling.outputStream().buffered().use { output ->
            repeat(chunkCount) { index ->
                val part = File(root, "chunks/${"%05d".format(index)}.part")
                val expectedLength = expectedChunkLength(totalBytes, chunkSize, chunkCount, index)
                require(part.isFile && part.length() == expectedLength) {
                    "Chunk $index length ${if (part.isFile) part.length() else -1}; expected $expectedLength"
                }
                part.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        written += count
                    }
                }
            }
        }
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        require(written == totalBytes) { "Transferred $written bytes; expected $totalBytes" }
        require(actualSha256 == expectedSha256) { "Transfer SHA-256 mismatch" }
        val archive = File(root, "gallery.zip")
        require(assembling.renameTo(archive)) { "Could not finalize seed archive" }
        require(File(root, "chunks").deleteRecursively())
        metadata.put("state", "COMPLETE")
        File(root, "transfer.json").writeText(metadata.toString())
        return completedBundle(written, actualSha256)
    }

    private fun expectedChunkLength(totalBytes: Long, chunkSize: Int, chunkCount: Int, index: Int): Long =
        if (index == chunkCount - 1) totalBytes - chunkSize.toLong() * index else chunkSize.toLong()

    private fun verifiedArchive(root: File, expectedSize: Long, expectedSha256: String): Boolean {
        val archive = File(root, "gallery.zip")
        if (!archive.isFile || archive.length() != expectedSize) return false
        return sha256(archive) == expectedSha256
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun completedBundle(size: Long, sha256: String): Bundle = Bundle().apply {
        putString("state", "COMPLETE")
        putLong("size", size)
        putString("sha256", sha256)
    }

    private fun runIdFrom(uri: Uri): String {
        require(uri.pathSegments.size >= 2)
        return requireRunId(uri.pathSegments[1])
    }

    private fun requireRunId(value: String?): String = requireNotNull(value).also {
        require(TestGallerySeederReceiver.RUN_ID.matches(it)) { "Invalid gallery run ID" }
    }

    private fun inputRoot(runId: String): File {
        val base = File(requireNotNull(context).filesDir, "test-seed-input").canonicalFile
        val root = File(base, runId).canonicalFile
        require(root.toPath().startsWith(base.toPath())) { "Seed input escaped root" }
        return root
    }

    private fun externalArchive(runId: String): File {
        val base = requireNotNull(requireNotNull(context).getExternalFilesDir("test-seed-transfer")).canonicalFile
        val archive = File(base, "$runId.zip").canonicalFile
        require(archive.toPath().startsWith(base.toPath())) { "External seed input escaped root" }
        return archive
    }

    private companion object {
        const val MAX_CHUNK_BYTES = 64 * 1024
        const val MAX_TRANSFER_BYTES = 512L * 1024 * 1024
    }
}
