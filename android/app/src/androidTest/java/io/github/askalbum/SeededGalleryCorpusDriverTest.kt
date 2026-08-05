package io.github.anup42.askalbum

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Debug-only driver for the run-scoped corpus acceptance tests.
 *
 * The archive is pushed to the app's external-files transfer directory by the host. The app
 * validates and adopts it before writing any MediaStore rows. Cleanup removes only the rows
 * recorded for the same run ID and the two reserved run-scoped directories.
 */
@RunWith(AndroidJUnit4::class)
class SeededGalleryCorpusDriverTest {
    @Test
    fun prepareOrCleanupRunScopedCorpus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.getString("galleryRunId")
        assumeTrue("galleryRunId was not supplied", !runId.isNullOrBlank())
        val safeRunId = requireNotNull(runId)
        when (arguments.getString("galleryDriverAction") ?: ACTION_PREPARE) {
            ACTION_PREPARE -> prepare(context, safeRunId, arguments)
            ACTION_INDEX -> index(context, safeRunId, arguments)
            ACTION_CLEANUP -> cleanup(context, safeRunId)
            ACTION_REPORT -> report(context, safeRunId)
            ACTION_DIAGNOSE -> diagnose(context, safeRunId)
            else -> error("Unsupported galleryDriverAction")
        }
    }

    private fun prepare(context: android.content.Context, runId: String, arguments: Bundle) {
        val archiveName = arguments.getString("gallerySeedArchiveName") ?: "$runId.zip"
        require(archiveName.matches(ARCHIVE_NAME)) { "Invalid gallerySeedArchiveName" }
        val archive = File(requireNotNull(context.getExternalFilesDir("test-seed-transfer")), archiveName)
        require(archive.isFile) { "Seed archive is not present at ${archive.absolutePath}" }
        val digest = sha256(archive)
        val authority = "${context.packageName}.testseed"
        val uri = Uri.parse("content://$authority/seed/$runId")
        val adopted = requireNotNull(context.contentResolver.call(
            uri,
            "adopt_external",
            runId,
            Bundle().apply {
                putString("total_bytes", archive.length().toString())
                putString("sha256", digest)
            },
        ))
        assertEquals("COMPLETE", adopted.getString("state"))

        clearStatus(context, runId, "status.json")
        clearStatus(context, runId, "seed-result.json")
        TestGallerySeederService.start(context, runId, TestGallerySeederService.ACTION_SEED)
        waitForState(context, runId, "status.json")
        waitForState(context, runId, "seed-result.json")

        clearStatus(context, runId, "import-status.json")
        TestGallerySeederService.start(context, runId, TestGallerySeederService.ACTION_IMPORT)
        val imported = waitForState(context, runId, "import-status.json")
        assertEquals("COMPLETE", imported.optString("state"))
        assertTrue(imported.optInt("importedCount", 0) > 0)
    }

    private fun cleanup(context: android.content.Context, runId: String) {
        clearStatus(context, runId, "cleanup-status.json")
        TestGallerySeederService.start(context, runId, TestGallerySeederService.ACTION_CLEANUP)
        waitForState(context, runId, "cleanup-status.json")

        // A seed can fail after publishing MediaStore rows but before writing seed-result.json.
        // The foreground cleanup already recovers rows by the exact reserved paths; there is no
        // imported database scope to remove in that case.
        if (!File(context.filesDir, "test-seed/$runId/seed-result.json").isFile) return

        context.sendBroadcast(
            Intent(TestGallerySeederReceiver.ACTION_REMOVE_IMPORTED)
                .setComponent(ComponentName(context, TestGallerySeederReceiver::class.java))
                .putExtra(TestGallerySeederReceiver.EXTRA_RUN_ID, runId),
        )
        val database = waitForState(context, runId, "db-cleanup-status.json")
        assertEquals(0, database.optInt("remainingCount", -1))
    }

    private fun index(context: android.content.Context, runId: String, arguments: Bundle) {
        val operationId = arguments.getString("galleryOperationId") ?: sha256("index:$runId").take(32)
        require(TestGallerySeederReceiver.OPERATION_ID.matches(operationId)) { "Invalid galleryOperationId" }
        val maxCycles = arguments.getString("galleryMaxCycles")?.toIntOrNull() ?: DEFAULT_INDEX_CYCLES
        require(maxCycles in 1..MAX_INDEX_CYCLES) { "Invalid galleryMaxCycles" }
        clearStatus(context, runId, "foreground-index-status.json")
        TestGallerySeederService.start(
            context,
            runId,
            TestGallerySeederService.ACTION_INDEX,
            operationId,
            maxCycles,
        )
        val indexed = waitForState(context, runId, "foreground-index-status.json")
        assertEquals("COMPLETE", indexed.optString("state"))
        assertEquals(operationId, indexed.optString("operationId"))
    }

    private fun report(context: android.content.Context, runId: String) {
        clearStatus(context, runId, "index-coverage-status.json")
        context.sendBroadcast(
            Intent(TestGallerySeederReceiver.ACTION_REPORT_INDEX)
                .setComponent(ComponentName(context, TestGallerySeederReceiver::class.java))
                .putExtra(TestGallerySeederReceiver.EXTRA_RUN_ID, runId),
        )
        waitForState(context, runId, "index-coverage-status.json")
    }

    private fun diagnose(context: android.content.Context, runId: String) {
        val application = context.applicationContext as AskAlbumApplication
        val seed = JSONObject(File(context.filesDir, "test-seed/$runId/seed-result.json").readText())
        val seeded = seed.getJSONArray("createdUris").let { values ->
            (0 until values.length()).mapTo(mutableSetOf()) { values.getString(it) }
        }
        val rows = application.repository.allItems()
            .filter { it.contentUri in seeded }
            .flatMap { item ->
                application.repository.stageRecords(item.id)
                    .filter { it.status == StageStatus.PENDING || it.status == StageStatus.RUNNING }
                    .map { stage ->
                        JSONObject()
                            .put("mediaId", item.id)
                            .put("filename", item.filename)
                            .put("indexState", item.indexState.name)
                            .put("stage", stage.stage.name)
                            .put("status", stage.status.name)
                            .put("attemptCount", stage.attemptCount)
                            .put("updatedAt", stage.updatedAt)
                            .put("error", stage.error ?: JSONObject.NULL)
                    }
            }
        File(context.filesDir, "test-seed/$runId/index-diagnostic.json").apply {
            parentFile?.mkdirs()
            writeText(JSONObject().put("state", "COMPLETE").put("runId", runId).put("rows", JSONArray(rows)).toString())
        }
    }

    private fun waitForState(context: android.content.Context, runId: String, filename: String): JSONObject {
        val file = File(context.filesDir, "test-seed/$runId/$filename")
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var last: JSONObject? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            if (file.isFile) {
                val current = runCatching { JSONObject(file.readText()) }.getOrNull()
                if (current != null) {
                    last = current
                    when (current.optString("state")) {
                        "COMPLETE", "RECOVERED" -> return current
                        "FAILED" -> error("$filename failed: ${current.optString("error")}")
                    }
                }
            }
            SystemClock.sleep(250)
        }
        error("Timed out waiting for $filename; last=$last")
    }

    private fun clearStatus(context: android.content.Context, runId: String, filename: String) {
        File(context.filesDir, "test-seed/$runId/$filename").delete()
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

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val ACTION_PREPARE = "prepare"
        const val ACTION_INDEX = "index"
        const val ACTION_CLEANUP = "cleanup"
        const val ACTION_REPORT = "report"
        const val ACTION_DIAGNOSE = "diagnose"
        const val TIMEOUT_MS = 30 * 60_000L
        const val DEFAULT_INDEX_CYCLES = 5_000
        const val MAX_INDEX_CYCLES = 5_000
        val ARCHIVE_NAME = Regex("[A-Za-z0-9._-]{1,180}")
    }
}
