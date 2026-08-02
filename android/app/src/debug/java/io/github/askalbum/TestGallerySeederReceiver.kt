package io.github.anup42.askalbum

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

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
                    ACTION_SEED -> TestGallerySeederService.start(context, runId)
                    ACTION_CLEANUP -> TestGallerySeederService.start(context, runId, TestGallerySeederService.ACTION_CLEANUP)
                    ACTION_IMPORT -> TestGallerySeederService.start(context, runId, TestGallerySeederService.ACTION_IMPORT)
                    ACTION_REMOVE_IMPORTED -> removeImported(context, runId)
                    ACTION_PREPARE_INTERRUPTION -> prepareIndexInterruption(context, runId)
                    ACTION_VERIFY_RECOVERY -> verifyIndexRecovery(context, runId)
                    ACTION_REPORT_INDEX -> reportIndexCoverage(context, runId)
                    ACTION_RESUME_INDEX -> resumeIndexing(context, runId, intent.getStringExtra(EXTRA_OPERATION_ID))
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

    internal fun cleanup(context: Context, runId: String, operationId: String? = null) {
        operationId?.let { require(OPERATION_ID.matches(it)) { "Invalid cleanup operation ID" } }
        writeStatus(
            context,
            runId,
            "cleanup-status.json",
            JSONObject().put("state", "RUNNING").put("runId", runId).also {
                operationId?.let { value -> it.put("operationId", value) }
            },
        )
        val resultFile = child(runRoot(context, runId), "seed-result.json")
        val seed = resultFile.takeIf(File::isFile)?.let { JSONObject(it.readText()) }
        seed?.let { require(it.getString("runId") == runId) }
        val uris = seed?.getJSONArray("createdUris") ?: JSONArray()
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
            .put("proof", "exact reserved run-scoped AskAlbumTest paths")
        writeStatus(context, runId, "orphan-recovery.json", recoveryRecord)
        var recoveredDeleted = 0
        recovered.forEach { uri -> recoveredDeleted += context.contentResolver.delete(uri, null, null) }
        val relativePaths = seed?.getJSONArray("relativePaths") ?: JSONArray(TestGalleryRunScope.relativePaths(runId))
        var remaining = 0
        for (index in 0 until relativePaths.length()) {
            val relativePath = relativePaths.getString(index)
            require(relativePath == "Pictures/AskAlbumTest/$runId/" || relativePath == "Documents/AskAlbumTest/$runId/")
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
        operationId?.let { cleanup.put("operationId", it) }
        writeStatus(context, runId, "cleanup-result.json", cleanup)
        writeStatus(context, runId, "cleanup-status.json", cleanup)
    }

    private fun ownedRunUris(context: Context, runId: String): List<Uri> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val paths = TestGalleryRunScope.relativePaths(runId)
        return paths.flatMap { relativePath ->
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(relativePath),
                null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0)))
                }
            }.orEmpty()
        }
    }

    internal fun importSeeded(context: Context, runId: String, operationId: String? = null) {
        operationId?.let { require(OPERATION_ID.matches(it)) { "Invalid import operation ID" } }
        val uris = seededUris(context, runId)
        writeStatus(context, runId, "import-status.json", JSONObject().put("state", "RUNNING").put("runId", runId).also {
            operationId?.let { value -> it.put("operationId", value) }
        })
        val repository = (context.applicationContext as AskAlbumApplication).repository
        val changed = repository.importUris(uris, MediaSource.MEDIA_STORE)
        val expected = uris.map(Uri::toString).toSet()
        val imported = repository.allItems().count { it.contentUri in expected }
        require(imported == expected.size) { "Imported $imported of ${expected.size} seeded items" }
        writeStatus(
            context,
            runId,
            "import-status.json",
            JSONObject().put("state", "COMPLETE").put("runId", runId)
                .put("requestedCount", expected.size).put("changedCount", changed).put("importedCount", imported).also {
                    operationId?.let { value -> it.put("operationId", value) }
                },
        )
    }

    private fun reportIndexCoverage(context: Context, runId: String) {
        val uris = seededUris(context, runId)
        val expectedUris = uris.map(Uri::toString).toSet()
        val application = context.applicationContext as AskAlbumApplication
        val repository = application.repository
        val coverage = repository.indexCoverageForContentUris(expectedUris)
        val scopedIds = repository.allItems().filter { it.contentUri in expectedUris }.mapTo(mutableSetOf()) { it.id }
        val vectorIds = runBlocking { application.services.semanticVectorStore.indexedIds() }
        val admission = BackgroundWorkAdmissionPolicy(context).evaluate()
        val stages = JSONObject()
        coverage.stageStatuses.forEach { (stage, counts) ->
            stages.put(stage.name, JSONObject().also { value -> counts.forEach { (status, count) -> value.put(status.name, count) } })
        }
        val states = JSONObject().also { value ->
            coverage.indexStates.forEach { (state, count) -> value.put(state.name, count) }
        }
        writeStatus(
            context,
            runId,
            "index-coverage-status.json",
            JSONObject().put("state", "COMPLETE").put("runId", runId)
                .put("expectedCount", expectedUris.size).put("mediaCount", coverage.mediaCount)
                .put("uniqueMediaIds", scopedIds.size).put("vectorCount", vectorIds.count(scopedIds::contains))
                .put("vectorProducer", application.services.semanticVectorStore.producerVersion())
                .put("thermalAllowed", admission.allowed).put("thermalStatus", admission.thermalStatus)
                .put("thermalReason", admission.reason).put("indexStates", states).put("stages", stages),
        )
    }

    internal fun resumeIndexing(context: Context, runId: String, operationId: String? = null) {
        operationId?.let { require(OPERATION_ID.matches(it)) { "Invalid resume operation ID" } }
        val application = context.applicationContext as AskAlbumApplication
        application.repository.recoverInterruptedJobs()
        IndexScheduler.restart(context)
        if (application.services.semanticVectorStore.producerVersion() != null) {
            EmbeddingIndexScheduler.restart(context)
        }
        val admission = BackgroundWorkAdmissionPolicy(context).evaluate()
        writeStatus(
            context,
            runId,
            "index-resume-status.json",
            JSONObject().put("state", "COMPLETE").put("runId", runId)
                .put("thermalAllowed", admission.allowed).put("thermalStatus", admission.thermalStatus)
                .put("thermalReason", admission.reason).also {
                    operationId?.let { value -> it.put("operationId", value) }
                },
        )
    }

    private fun removeImported(context: Context, runId: String) {
        val uris = seededUris(context, runId)
        writeStatus(context, runId, "db-cleanup-status.json", JSONObject().put("state", "RUNNING"))
        val repository = (context.applicationContext as AskAlbumApplication).repository
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

    internal fun seededUris(context: Context, runId: String): List<Uri> {
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
        val repository = (context.applicationContext as AskAlbumApplication).repository
        IndexScheduler.cancelAndWait(context)
        val before = repository.indexCoverageForContentUris(expected)
        require(before.mediaCount == expected.size) {
            "Expected ${expected.size} indexed rows before interruption, found ${before.mediaCount}"
        }
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
                .put("uniqueRows", imported.size)
                .put("runningStages", runningStages),
        )
    }

    private fun verifyIndexRecovery(context: Context, runId: String) {
        val uris = seededUris(context, runId)
        val expected = uris.map(Uri::toString).toSet()
        val repository = (context.applicationContext as AskAlbumApplication).repository
        repository.recoverInterruptedJobs()
        val coverage = repository.indexCoverageForContentUris(expected)
        require(coverage.mediaCount == expected.size) { "Recovery changed row count: ${coverage.mediaCount}" }
        val stageRows = coverage.stageStatuses.values.sumOf { it.values.sum() }
        require(stageRows == expected.size * IndexStage.entries.size) {
            "Expected ${expected.size * IndexStage.entries.size} stages, found $stageRows"
        }
        val runningStages = coverage.stageStatuses.values.sumOf { it[StageStatus.RUNNING] ?: 0 }
        val indexingRows = coverage.indexStates[IndexState.INDEXING] ?: 0
        require(runningStages == 0) { "Recovery left $runningStages RUNNING stages" }
        require(indexingRows == 0) { "Recovery left $indexingRows INDEXING media rows" }
        writeStatus(
            context,
            runId,
            "recovery-verify-status.json",
            JSONObject().put("state", "COMPLETE").put("runId", runId)
                .put("uniqueRows", coverage.mediaCount).put("stageRows", stageRows)
                .put("runningStages", 0).put("indexingRows", 0),
        )
    }

    private fun statusName(action: String?): String = when (action) {
        ACTION_IMPORT -> "import-status.json"
        ACTION_REMOVE_IMPORTED -> "db-cleanup-status.json"
        ACTION_PREPARE_INTERRUPTION -> "recovery-prepare-status.json"
        ACTION_VERIFY_RECOVERY -> "recovery-verify-status.json"
        ACTION_REPORT_INDEX -> "index-coverage-status.json"
        ACTION_RESUME_INDEX -> "index-resume-status.json"
        ACTION_CLEANUP -> "cleanup-status.json"
        else -> "status.json"
    }

    internal fun writeStatus(context: Context, runId: String, name: String, value: JSONObject) {
        child(runRoot(context, runId).apply { mkdirs() }, name).writeText(value.toString())
    }

    private fun runRoot(context: Context, runId: String): File = child(File(context.filesDir, "test-seed"), requireRunId(runId))

    private fun child(parent: File, name: String): File {
        val file = File(parent, name).canonicalFile
        require(file.toPath().startsWith(parent.canonicalFile.toPath())) { "Path escaped test staging root" }
        return file
    }

    private fun requireRunId(value: String?): String = requireNotNull(value).also {
        require(RUN_ID.matches(it)) { "Invalid gallery run ID" }
    }

    companion object {
        const val ACTION_SEED = "io.github.anup42.askalbum.test.SEED_GALLERY"
        const val ACTION_CLEANUP = "io.github.anup42.askalbum.test.CLEANUP_GALLERY"
        const val ACTION_IMPORT = "io.github.anup42.askalbum.test.IMPORT_SEEDED"
        const val ACTION_REMOVE_IMPORTED = "io.github.anup42.askalbum.test.REMOVE_IMPORTED"
        const val ACTION_PREPARE_INTERRUPTION = "io.github.anup42.askalbum.test.PREPARE_INDEX_INTERRUPTION"
        const val ACTION_VERIFY_RECOVERY = "io.github.anup42.askalbum.test.VERIFY_INDEX_RECOVERY"
        const val ACTION_REPORT_INDEX = "io.github.anup42.askalbum.test.REPORT_INDEX_COVERAGE"
        const val ACTION_RESUME_INDEX = "io.github.anup42.askalbum.test.RESUME_INDEXING"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_OPERATION_ID = "operation_id"
        val RUN_ID = Regex("[A-Za-z0-9_-]{6,64}")
        val OPERATION_ID = Regex("[a-f0-9]{32}")
    }
}
