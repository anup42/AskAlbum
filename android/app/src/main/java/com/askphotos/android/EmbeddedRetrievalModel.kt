package com.askphotos.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

data class EmbeddedRetrievalSpec(
    val packId: String,
    val packVersion: String,
    val displayName: String,
    val assetPath: String,
    val archiveSizeBytes: Long,
    val archiveSha256: String,
    val installedBytes: Long,
    val sourceRevision: String,
    val artifactRevision: String,
) {
    fun validate(manifest: RetrievalPackManifest) {
        require(manifest.packId == packId) { "Embedded pack ID is not the pinned pack" }
        require(manifest.packVersion == packVersion) { "Embedded pack version is not the pinned version" }
        require(manifest.sourceRevision == sourceRevision) { "Embedded pack has the wrong source revision" }
        require(manifest.artifactRevision == artifactRevision) { "Embedded pack has the wrong artifact revision" }
        require(manifest.files.sumOf { it.sizeBytes } == installedBytes) { "Embedded pack has the wrong installed size" }
    }
}

object EmbeddedRetrievalModel {
    val siglip2BaseQuantized = EmbeddedRetrievalSpec(
        packId = "siglip2-base-p16-224-q8",
        packVersion = "ba1f3b0-q8-core05",
        displayName = "SigLIP2 Base quantized",
        assetPath = "models/retrieval/siglip2-base-p16-224-q8-core05.agretrieval",
        archiveSizeBytes = 267_744_234L,
        archiveSha256 = "5966d528a7ddf73be52a299251e5c0071d878ba1e0fcc70d39fcf38ec6a8f010",
        installedBytes = 384_737_099L,
        sourceRevision = "022b6f71160ffb0169ca4709e2d7e25be659598a",
        artifactRevision = "ba1f3b0843f24bc5417d38e19c37b287d719b2f4",
    )
}

data class RetrievalProvisionProgress(
    val state: GemmaDownloadState = GemmaDownloadState.IDLE,
    val bytesCopied: Long = 0,
    val totalBytes: Long = EmbeddedRetrievalModel.siglip2BaseQuantized.archiveSizeBytes,
    val error: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (bytesCopied.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
}

class EmbeddedRetrievalModelProvisioner(
    private val context: Context,
    private val modelPacks: RetrievalModelPackManager,
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueueIfNeeded(): UUID? {
        if (modelPacks.status().installed) return null
        val spec = EmbeddedRetrievalModel.siglip2BaseQuantized
        val required = spec.archiveSizeBytes + spec.installedBytes + MIN_FREE_AFTER_EMBEDDED_INSTALL
        require(StatFs(context.filesDir.absolutePath).availableBytes > required) {
            "Not enough app-private storage to install embedded ${spec.displayName}"
        }
        val request = OneTimeWorkRequestBuilder<EmbeddedRetrievalInstallWorker>()
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    fun progress(): RetrievalProvisionProgress {
        if (modelPacks.status().installed) {
            val total = EmbeddedRetrievalModel.siglip2BaseQuantized.archiveSizeBytes
            return RetrievalProvisionProgress(GemmaDownloadState.INSTALLED, total, total)
        }
        val info = runCatching {
            workManager.getWorkInfosForUniqueWork(WORK_NAME).get(5, TimeUnit.SECONDS).maxByOrNull { it.runAttemptCount }
        }.getOrNull() ?: return RetrievalProvisionProgress()
        val state = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> GemmaDownloadState.QUEUED
            WorkInfo.State.RUNNING -> if (info.progress.getBoolean(KEY_VERIFYING, false)) GemmaDownloadState.VERIFYING else GemmaDownloadState.DOWNLOADING
            WorkInfo.State.SUCCEEDED -> GemmaDownloadState.INSTALLED
            WorkInfo.State.FAILED -> GemmaDownloadState.FAILED
            WorkInfo.State.CANCELLED -> GemmaDownloadState.CANCELLED
        }
        return RetrievalProvisionProgress(
            state = state,
            bytesCopied = info.progress.getLong(KEY_COPIED, 0),
            error = info.outputData.getString(KEY_ERROR),
        )
    }

    companion object {
        const val WORK_NAME = "embedded-siglip2-retrieval-install"
    }
}

class EmbeddedRetrievalInstallWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val spec = EmbeddedRetrievalModel.siglip2BaseQuantized
        val manager = (applicationContext as AskPhotosApplication).services.retrievalModelPackManager
        if (manager.status().installed) return@withContext Result.success()
        val incomingDir = File(applicationContext.filesDir, "models/retrieval/embedded").apply { mkdirs() }
        val partial = File(incomingDir, "${spec.packVersion}.agretrieval.part")
        try {
            setForeground(installForeground(spec, partial.length()))
            copyEmbeddedArchive(spec, partial)
            setProgress(progressData(partial.length(), verifying = true))
            manager.installEmbedded(spec, partial)
            partial.delete()
            EmbeddingIndexScheduler.schedule(applicationContext)
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failure(error.message ?: "Embedded retrieval model installation failed")
        }
    }

    private suspend fun copyEmbeddedArchive(spec: EmbeddedRetrievalSpec, partial: File) {
        val assetLength = applicationContext.assets.openFd(spec.assetPath).use { it.length }
        require(assetLength == spec.archiveSizeBytes) { "Embedded retrieval asset has the wrong size" }
        var existing = partial.length().coerceAtMost(spec.archiveSizeBytes)
        if (partial.length() > spec.archiveSizeBytes) {
            require(partial.delete()) { "Could not discard an oversized embedded-model copy" }
            existing = 0
        }
        applicationContext.assets.open(spec.assetPath).buffered().use { input ->
            var skipped = 0L
            while (skipped < existing) {
                val count = input.skip(existing - skipped)
                require(count > 0) { "Could not resume embedded-model extraction" }
                skipped += count
            }
            var written = existing
            var lastProgress = 0L
            FileOutputStream(partial, existing > 0).use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    written += count
                    require(written <= spec.archiveSizeBytes) { "Embedded model exceeds the pinned size" }
                    output.write(buffer, 0, count)
                    val now = System.currentTimeMillis()
                    if (now - lastProgress >= 500) {
                        setProgress(progressData(written))
                        setForeground(installForeground(spec, written))
                        lastProgress = now
                    }
                }
                output.fd.sync()
            }
            require(written == spec.archiveSizeBytes) { "Embedded retrieval model copy is incomplete" }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        installForeground(EmbeddedRetrievalModel.siglip2BaseQuantized, 0)

    private fun installForeground(spec: EmbeddedRetrievalSpec, copied: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(EMBEDDED_INSTALL_CHANNEL, "Embedded AI model setup", NotificationManager.IMPORTANCE_LOW))
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val percent = ((copied.toDouble() / spec.archiveSizeBytes) * 100).toInt().coerceIn(0, 100)
        val notification = NotificationCompat.Builder(applicationContext, EMBEDDED_INSTALL_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Installing embedded ${spec.displayName}")
            .setContentText("$percent% - verified before activation")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        return ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun failure(message: String) = Result.failure(Data.Builder().putString(KEY_ERROR, message.take(300)).build())
    private fun progressData(copied: Long, verifying: Boolean = false) = Data.Builder()
        .putLong(KEY_COPIED, copied)
        .putBoolean(KEY_VERIFYING, verifying)
        .build()
}

private const val KEY_COPIED = "retrieval_embedded_copied"
private const val KEY_VERIFYING = "retrieval_embedded_verifying"
private const val KEY_ERROR = "retrieval_embedded_error"
private const val EMBEDDED_INSTALL_CHANNEL = "embedded-siglip2-install"
private const val MIN_FREE_AFTER_EMBEDDED_INSTALL = 512L * 1024 * 1024
