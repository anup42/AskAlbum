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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
        if (intent?.action == ACTION_STOP) {
            importJob?.cancel()
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_IMPORT && intent?.action != ACTION_INDEX) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Reading your permitted gallery", indeterminate = true),
            if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else 0,
        )
        if (importJob?.isActive != true) {
            ForegroundIndexRuntime.started()
            importJob = scope.launch {
                val app = application as AskAlbumApplication
                val result = runCatching {
                    val imported = if (intent.action == ACTION_IMPORT) app.repository.scanAccessibleGallery() else 0
                    val indexed = ForegroundIndexCoordinator(this@InitialImportService).run(
                        onProgress = { progress ->
                            val summary = app.repository.indexSummary()
                            getSystemService(NotificationManager::class.java).notify(
                                NOTIFICATION_ID,
                                notification(
                                    "Media ${summary.discovered - summary.pending}/${summary.discovered}; " +
                                        "vectors ${summary.siglipVectorsReady}/${summary.discovered}",
                                    indeterminate = true,
                                ),
                            )
                        },
                    )
                    imported to indexed
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

    private fun notification(message: String, indeterminate: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("AskAlbum local import")
            .setContentText(message)
            .setContentIntent(openApp)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, InitialImportService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(indeterminate)
            .setProgress(if (indeterminate) 0 else 1, if (indeterminate) 0 else 1, indeterminate)
            .build()
    }

    companion object {
        private const val ACTION_IMPORT = "io.github.anup42.askalbum.action.INITIAL_IMPORT"
        private const val ACTION_INDEX = "io.github.anup42.askalbum.action.INDEX"
        private const val ACTION_STOP = "io.github.anup42.askalbum.action.STOP_INDEX"
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

    private fun stopForegroundAndSelf() {
        ForegroundIndexRuntime.stopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }
}
