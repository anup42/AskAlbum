package com.samsung.agenticgallery

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
        if (intent?.action != ACTION_IMPORT) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Reading your permitted gallery", indeterminate = true),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (importJob?.isActive != true) {
            importJob = scope.launch {
                val app = application as AgenticGalleryApplication
                val result = runCatching {
                    val imported = app.repository.scanAccessibleGallery()
                    val indexed = ForegroundIndexCoordinator(this@InitialImportService).run(
                        onProgress = { progress ->
                            getSystemService(NotificationManager::class.java).notify(
                                NOTIFICATION_ID,
                                notification(
                                    "Analyzed ${progress.galleryProcessed}; visual memory ${progress.embeddingsProcessed}",
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
                ServiceCompat.stopForeground(this@InitialImportService, ServiceCompat.STOP_FOREGROUND_DETACH)
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
            .setContentTitle("AgenticGallery local import")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(indeterminate)
            .setProgress(if (indeterminate) 0 else 1, if (indeterminate) 0 else 1, indeterminate)
            .build()
    }

    companion object {
        private const val ACTION_IMPORT = "com.samsung.agenticgallery.action.INITIAL_IMPORT"
        private const val CHANNEL_ID = "gallery_initial_import"
        private const val NOTIFICATION_ID = 4102

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, InitialImportService::class.java).setAction(ACTION_IMPORT),
            )
        }
    }
}
