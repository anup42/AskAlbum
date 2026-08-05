package io.github.anup42.askalbum

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class InitialImportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var importJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            importJob?.cancel()
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_PAUSE) {
            pauseIndexing()
            return START_NOT_STICKY
        }
        if (action != ACTION_IMPORT && action != ACTION_INDEX && action != ACTION_RESUME) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        IndexingJobControlsStore(this).setForegroundPaused(false)
        startIndexingForeground(notification("Reading your permitted gallery", indeterminate = true))
        if (importJob?.isActive != true) {
            ForegroundIndexRuntime.started()
            importJob = scope.launch {
                val app = application as AskAlbumApplication
                val result = runCatching {
                    val imported = if (action == ACTION_IMPORT) app.repository.scanAccessibleGallery() else 0
                    val progressStartedAt = SystemClock.elapsedRealtime()
                    val indexed = ForegroundIndexCoordinator(this@InitialImportService).run(
                        onProgress = { progress ->
                            val summary = app.repository.indexSummary()
                            val discovered = summary.discovered
                            val completed = (discovered - summary.pending - summary.failed)
                                .coerceIn(0, discovered)
                            val processed = progress.galleryProcessed + progress.embeddingsProcessed
                            val elapsedMs = (SystemClock.elapsedRealtime() - progressStartedAt).coerceAtLeast(1L)
                            val ratePerMinute = processed * 60_000.0 / elapsedMs
                            val remaining = (summary.pending +
                                summary.siglipVectorsPending)
                                .coerceAtLeast(0)
                            val etaMillis = if (ratePerMinute > 0.0 && remaining > 0) {
                                (remaining * 60_000.0 / ratePerMinute).toLong()
                            } else {
                                null
                            }
                            val pipeline = when {
                                progress.galleryHasMore && progress.embeddingsHaveMore -> "media + vectors"
                                progress.galleryHasMore -> "media analysis"
                                progress.embeddingsHaveMore -> "SigLIP2 vectors"
                                else -> "finalizing"
                            }
                            val rateText = if (ratePerMinute >= 1.0) {
                                " | ${ratePerMinute.toInt()}/min"
                            } else {
                                ""
                            }
                            val etaText = etaMillis?.let { " | ETA ${formatDuration(it)}" } ?: ""
                            val vectorFailureText = if (summary.siglipVectorsFailed > 0) {
                                " (${summary.siglipVectorsFailed} quarantined)"
                            } else {
                                ""
                            }
                            getSystemService(NotificationManager::class.java).notify(
                                NOTIFICATION_ID,
                                notification(
                                    "$pipeline | media $completed/$discovered; vectors " +
                                        "${summary.siglipVectorsReady}/$discovered$vectorFailureText$rateText$etaText",
                                    indeterminate = discovered <= 0,
                                    progress = completed,
                                    total = discovered,
                                ),
                            )
                        },
                    )
                    imported to indexed
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                }
                val message = result.fold(
                    onSuccess = { (imported, indexed) ->
                        when (indexed.reason) {
                            ForegroundIndexStopReason.COMPLETE -> "$imported gallery records indexed locally"
                            ForegroundIndexStopReason.THERMAL ->
                                "Indexed ${indexed.galleryProcessed}; paused to keep your phone cool"
                            ForegroundIndexStopReason.RETRYABLE_FAILURE ->
                                "Indexed ${indexed.galleryProcessed}; a retry is scheduled"
                            else -> "Indexed ${indexed.galleryProcessed}; private indexing will resume"
                        }
                    },
                    onFailure = { error ->
                        IndexScheduler.schedule(this@InitialImportService)
                        if (app.services.semanticVectorStore.producerVersion() != null) {
                            EmbeddingIndexScheduler.schedule(this@InitialImportService)
                        }
                        "Gallery import paused: ${error.javaClass.simpleName}"
                    },
                )
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(message, indeterminate = false),
                )
                stopForegroundAndSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        ForegroundIndexRuntime.stopped()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        importJob?.cancel()
        IndexScheduler.schedule(this)
        EmbeddingIndexScheduler.schedule(this)
        stopForegroundAndSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Private gallery import", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress for on-device gallery discovery and indexing"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun startIndexingForeground(notification: Notification) {
        when {
            Build.VERSION.SDK_INT >= 35 -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
            Build.VERSION.SDK_INT >= 29 -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(
        message: String,
        indeterminate: Boolean,
        progress: Int? = null,
        total: Int? = null,
        paused: Boolean = false,
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID).apply {
            setSmallIcon(android.R.drawable.stat_sys_download)
            setContentTitle("AskAlbum local import")
            setContentText(message)
            setContentIntent(openApp)
            if (paused) {
                addAction(
                    android.R.drawable.ic_media_play,
                    "Resume",
                    serviceAction(ACTION_RESUME, 3),
                )
            } else {
                addAction(
                    android.R.drawable.ic_media_pause,
                    "Pause",
                    serviceAction(ACTION_PAUSE, 2),
                )
                addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    serviceAction(ACTION_STOP, 1),
                )
            }
            setOnlyAlertOnce(true)
            setOngoing(indeterminate && !paused)
            setProgress(
                total?.takeIf { it > 0 } ?: 0,
                progress?.coerceIn(0, total?.coerceAtLeast(0) ?: 0) ?: 0,
                indeterminate || total == null || total <= 0,
            )
        }.build()
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, InitialImportService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun formatDuration(millis: Long): String {
        val totalSeconds = (millis / 1_000L).coerceAtLeast(1L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0L) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    companion object {
        private const val ACTION_IMPORT = "io.github.anup42.askalbum.action.INITIAL_IMPORT"
        private const val ACTION_INDEX = "io.github.anup42.askalbum.action.INDEX"
        private const val ACTION_STOP = "io.github.anup42.askalbum.action.STOP_INDEX"
        private const val ACTION_PAUSE = "io.github.anup42.askalbum.action.PAUSE_INDEX"
        private const val ACTION_RESUME = "io.github.anup42.askalbum.action.RESUME_INDEX"
        private const val CHANNEL_ID = "gallery_initial_import"
        private const val NOTIFICATION_ID = 4102

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, InitialImportService::class.java).setAction(ACTION_IMPORT),
            )
        }

        fun startIndexing(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, InitialImportService::class.java).setAction(ACTION_INDEX),
            )
        }
    }

    private fun pauseIndexing() {
        IndexingJobControlsStore(this).setForegroundPaused(true)
        importJob?.cancel()
        scope.launch {
            runCatching {
                IndexScheduler.cancelAndWait(this@InitialImportService)
                EmbeddingIndexScheduler.cancelAndWait(this@InitialImportService)
                CaptionEmbeddingScheduler.cancelAndWait(this@InitialImportService)
                PeopleIndexScheduler.cancelAndWait(this@InitialImportService)
                SemanticEnrichmentScheduler.cancelAndWait(this@InitialImportService)
            }
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification("Indexing paused. Your completed data is preserved.", indeterminate = false, paused = true),
            )
            stopForegroundAndSelf()
        }
    }

    private fun stopForegroundAndSelf() {
        ForegroundIndexRuntime.stopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }
}
