package io.github.anup42.askalbum

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Debug-only foreground owner for long, resumable connected-test gallery seeding. */
class TestGallerySeederService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var seedJob: Job? = null
    private var activeRunId: String? = null
    private var activeAction: String? = null

    override fun onCreate() {
        super.onCreate()
        check(BuildConfig.DEBUG)
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Test gallery seeding", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress for app-owned connected-device test media"
                    setShowBadge(false)
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runId = intent?.getStringExtra(EXTRA_RUN_ID)
        val action = intent?.action
        val operationId = intent?.getStringExtra(EXTRA_OPERATION_ID)
        val maxCycles = intent?.getIntExtra(EXTRA_MAX_CYCLES, DEFAULT_INDEX_CYCLES) ?: DEFAULT_INDEX_CYCLES
        val invalidOperation = operationId != null && !TestGallerySeederReceiver.OPERATION_ID.matches(operationId)
        val invalidIndex = action == ACTION_INDEX && (operationId == null || maxCycles !in 1..MAX_INDEX_CYCLES)
        if (action !in setOf(ACTION_SEED, ACTION_CLEANUP, ACTION_IMPORT, ACTION_INDEX) || runId == null ||
            !TestGallerySeederReceiver.RUN_ID.matches(runId) || invalidOperation || invalidIndex
        ) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(
                when (action) {
                    ACTION_SEED -> "Preparing $runId"
                    ACTION_CLEANUP -> "Cleaning $runId"
                    ACTION_INDEX -> "Indexing $runId on device"
                    else -> "Importing $runId"
                },
                0,
                0,
            ),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (seedJob?.isActive == true) {
            if (activeRunId != runId || activeAction != action) {
                TestGallerySeederReceiver().writeStatus(
                    this,
                    runId,
                    when (action) {
                        ACTION_CLEANUP -> "cleanup-status.json"
                        ACTION_IMPORT -> "import-status.json"
                        ACTION_INDEX -> "foreground-index-status.json"
                        else -> "status.json"
                    },
                    org.json.JSONObject().put("state", "FAILED").put("runId", runId)
                        .put("error", "Another test gallery operation is active").also {
                            operationId?.let { value -> it.put("operationId", value) }
                        },
                )
                return START_NOT_STICKY
            }
            return START_REDELIVER_INTENT
        }
        activeRunId = runId
        activeAction = action
        val engine = TestGallerySeedEngine(this) { created, total ->
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification("Created or recovered $created of $total", created, total),
            )
        }
        seedJob = scope.launch {
            try {
                when (action) {
                    ACTION_SEED -> engine.seed(runId)
                    ACTION_CLEANUP -> TestGallerySeederReceiver().cleanup(this@TestGallerySeederService, runId, operationId)
                    ACTION_IMPORT -> TestGallerySeederReceiver().importSeeded(this@TestGallerySeederService, runId, operationId)
                    else -> indexSeeded(runId, requireNotNull(operationId), maxCycles)
                }
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(
                        when (action) {
                            ACTION_SEED -> "Test gallery ready"
                            ACTION_CLEANUP -> "Test gallery removed"
                            ACTION_INDEX -> "Foreground index pass finished"
                            else -> "Test gallery imported"
                        },
                        1,
                        1,
                    ),
                )
            } catch (_: CancellationException) {
                throw CancellationException("Test gallery seeding interrupted for process recovery")
            } catch (error: Throwable) {
                if (action == ACTION_SEED) {
                    engine.writeFailure(runId, error)
                } else {
                    val statusName = when (action) {
                        ACTION_CLEANUP -> "cleanup-status.json"
                        ACTION_INDEX -> "foreground-index-status.json"
                        else -> "import-status.json"
                    }
                    TestGallerySeederReceiver().writeStatus(
                        this@TestGallerySeederService,
                        runId,
                        statusName,
                        JSONObject().put("state", "FAILED").put("runId", runId)
                            .put("resumable", true).put("error", error.message ?: error.javaClass.simpleName).also {
                                operationId?.let { value -> it.put("operationId", value) }
                            },
                    )
                }
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification("Test gallery paused: ${error.javaClass.simpleName}", 1, 1),
                )
            } finally {
                activeRunId = null
                activeAction = null
                ServiceCompat.stopForeground(this@TestGallerySeederService, ServiceCompat.STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun indexSeeded(runId: String, operationId: String, maxCycles: Int) {
        val receiver = TestGallerySeederReceiver()
        val expectedUris = receiver.seededUris(this, runId).map(Uri::toString).toSet()
        val app = application as AskAlbumApplication
        val allowedIds = app.repository.allItems()
            .asSequence()
            .filter { it.contentUri in expectedUris }
            .mapTo(linkedSetOf()) { it.id }
        require(allowedIds.size == expectedUris.size) {
            "Foreground scope has ${allowedIds.size} of ${expectedUris.size} imported rows"
        }
        receiver.writeStatus(
            this,
            runId,
            "foreground-index-status.json",
            JSONObject().put("state", "RUNNING").put("runId", runId).put("operationId", operationId)
                .put("expectedCount", expectedUris.size).put("maxCycles", maxCycles),
        )
        val result = ForegroundIndexCoordinator(this).run(
            allowedMediaIds = allowedIds,
            limits = ForegroundIndexRunLimits(maxCycles = maxCycles, maxDurationMs = 6 * 60 * 60_000L),
            onProgress = { progress ->
                receiver.writeStatus(
                    this,
                    runId,
                    "foreground-index-status.json",
                    JSONObject().put("state", "RUNNING").put("runId", runId).put("operationId", operationId)
                        .put("expectedCount", expectedUris.size).put("maxCycles", maxCycles)
                        .put("cycles", progress.cycle).put("galleryProcessed", progress.galleryProcessed)
                        .put("embeddingsProcessed", progress.embeddingsProcessed)
                        .put("retryableFailures", progress.retryableFailures)
                        .put("permanentFailures", progress.permanentFailures)
                        .put("thermalStatus", progress.thermalStatus),
                )
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(
                        "Analyzed ${progress.galleryProcessed}; vectors ${progress.embeddingsProcessed}",
                        progress.cycle,
                        maxCycles,
                    ),
                )
            },
        )
        receiver.writeStatus(
            this,
            runId,
            "foreground-index-status.json",
            JSONObject().put("state", "COMPLETE").put("runId", runId).put("operationId", operationId)
                .put("expectedCount", expectedUris.size).put("maxCycles", maxCycles)
                .put("reason", result.reason.name).put("cycles", result.cycles)
                .put("galleryProcessed", result.galleryProcessed)
                .put("embeddingsProcessed", result.embeddingsProcessed)
                .put("retryableFailures", result.retryableFailures)
                .put("permanentFailures", result.permanentFailures)
                .put("elapsedMs", result.elapsedMs).put("thermalStatus", result.thermalStatus),
        )
    }

    private fun notification(message: String, progress: Int, total: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("AskAlbum test gallery")
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(total == 0 || progress < total)
            .setProgress(total, progress, total == 0)
            .build()

    companion object {
        const val ACTION_SEED = "io.github.anup42.askalbum.test.SEED_GALLERY_FOREGROUND"
        const val ACTION_CLEANUP = "io.github.anup42.askalbum.test.CLEANUP_GALLERY_FOREGROUND"
        const val ACTION_IMPORT = "io.github.anup42.askalbum.test.IMPORT_SEEDED_FOREGROUND"
        const val ACTION_INDEX = "io.github.anup42.askalbum.test.INDEX_SEEDED_FOREGROUND"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_OPERATION_ID = "operation_id"
        const val EXTRA_MAX_CYCLES = "max_cycles"
        private const val CHANNEL_ID = "test_gallery_seed"
        private const val NOTIFICATION_ID = 4903
        private const val DEFAULT_INDEX_CYCLES = 2
        private const val MAX_INDEX_CYCLES = 5_000

        fun start(
            context: Context,
            runId: String,
            action: String = ACTION_SEED,
            operationId: String? = null,
            maxCycles: Int = DEFAULT_INDEX_CYCLES,
        ) {
            require(TestGallerySeederReceiver.RUN_ID.matches(runId))
            require(action == ACTION_SEED || action == ACTION_CLEANUP || action == ACTION_IMPORT || action == ACTION_INDEX)
            operationId?.let { require(TestGallerySeederReceiver.OPERATION_ID.matches(it)) }
            require(maxCycles in 1..MAX_INDEX_CYCLES)
            ContextCompat.startForegroundService(
                context,
                Intent(context, TestGallerySeederService::class.java).setAction(action).putExtra(EXTRA_RUN_ID, runId).also {
                    operationId?.let { value -> it.putExtra(EXTRA_OPERATION_ID, value) }
                }.putExtra(EXTRA_MAX_CYCLES, maxCycles),
            )
        }
    }
}
