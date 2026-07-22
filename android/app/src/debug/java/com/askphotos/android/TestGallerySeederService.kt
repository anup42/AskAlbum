package com.askphotos.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
        val invalidOperation = operationId != null && !TestGallerySeederReceiver.OPERATION_ID.matches(operationId)
        if (action !in setOf(ACTION_SEED, ACTION_CLEANUP, ACTION_IMPORT) || runId == null ||
            !TestGallerySeederReceiver.RUN_ID.matches(runId) || invalidOperation
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
                    else -> TestGallerySeederReceiver().importSeeded(this@TestGallerySeederService, runId, operationId)
                }
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(
                        when (action) {
                            ACTION_SEED -> "Test gallery ready"
                            ACTION_CLEANUP -> "Test gallery removed"
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
                    TestGallerySeederReceiver().writeStatus(
                        this@TestGallerySeederService,
                        runId,
                        if (action == ACTION_CLEANUP) "cleanup-status.json" else "import-status.json",
                        org.json.JSONObject().put("state", "FAILED").put("runId", runId)
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

    private fun notification(message: String, progress: Int, total: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("AskPhotos test gallery")
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(total == 0 || progress < total)
            .setProgress(total, progress, total == 0)
            .build()

    companion object {
        const val ACTION_SEED = "com.askphotos.android.test.SEED_GALLERY_FOREGROUND"
        const val ACTION_CLEANUP = "com.askphotos.android.test.CLEANUP_GALLERY_FOREGROUND"
        const val ACTION_IMPORT = "com.askphotos.android.test.IMPORT_SEEDED_FOREGROUND"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_OPERATION_ID = "operation_id"
        private const val CHANNEL_ID = "test_gallery_seed"
        private const val NOTIFICATION_ID = 4903

        fun start(context: Context, runId: String, action: String = ACTION_SEED, operationId: String? = null) {
            require(TestGallerySeederReceiver.RUN_ID.matches(runId))
            require(action == ACTION_SEED || action == ACTION_CLEANUP || action == ACTION_IMPORT)
            operationId?.let { require(TestGallerySeederReceiver.OPERATION_ID.matches(it)) }
            ContextCompat.startForegroundService(
                context,
                Intent(context, TestGallerySeederService::class.java).setAction(action).putExtra(EXTRA_RUN_ID, runId).also {
                    operationId?.let { value -> it.putExtra(EXTRA_OPERATION_ID, value) }
                },
            )
        }
    }
}
