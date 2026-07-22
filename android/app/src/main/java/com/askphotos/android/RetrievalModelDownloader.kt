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
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

data class RetrievalDownloadSpec(
    val packId: String,
    val packVersion: String,
    val displayName: String,
    val downloadUrl: String,
    val archiveSizeBytes: Long,
    val archiveSha256: String,
    val installedBytes: Long,
    val sourceRevision: String,
    val artifactRevision: String,
) {
    fun validate(manifest: RetrievalPackManifest) {
        require(manifest.packId == packId) { "Downloaded pack ID is not the catalog pack" }
        require(manifest.packVersion == packVersion) { "Downloaded pack version is not the catalog version" }
        require(manifest.sourceRevision == sourceRevision) { "Downloaded pack has the wrong source revision" }
        require(manifest.artifactRevision == artifactRevision) { "Downloaded pack has the wrong artifact revision" }
        require(manifest.files.sumOf { it.sizeBytes } == installedBytes) { "Downloaded pack has the wrong installed size" }
    }
}

object RetrievalModelCatalog {
    val siglip2BaseQuantized = RetrievalDownloadSpec(
        packId = "siglip2-base-p16-224-q8",
        packVersion = "ba1f3b0-q8-core05",
        displayName = "SigLIP2 Base quantized",
        downloadUrl = "https://github.com/anup42/AgenticGallery/releases/download/model-packs-v0.0.1/siglip2-base-p16-224-q8-core05.agretrieval",
        archiveSizeBytes = 267_744_234L,
        archiveSha256 = "5966d528a7ddf73be52a299251e5c0071d878ba1e0fcc70d39fcf38ec6a8f010",
        installedBytes = 384_737_099L,
        sourceRevision = "022b6f71160ffb0169ca4709e2d7e25be659598a",
        artifactRevision = "ba1f3b0843f24bc5417d38e19c37b287d719b2f4",
    )
}

object RetrievalDownloadEndpointPolicy {
    private val allowedHosts = setOf(
        "github.com",
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com",
    )

    fun requireAllowed(url: URL): URL {
        require(url.protocol.equals("https", ignoreCase = true)) { "Retrieval downloads require HTTPS" }
        require(url.userInfo == null) { "Retrieval download URLs cannot contain credentials" }
        require(url.port == -1 || url.port == 443) { "Retrieval downloads require the HTTPS port" }
        require(url.host.lowercase() in allowedHosts) { "Retrieval download host is not allowlisted" }
        return url
    }
}

data class RetrievalDownloadProgress(
    val state: GemmaDownloadState = GemmaDownloadState.IDLE,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = RetrievalModelCatalog.siglip2BaseQuantized.archiveSizeBytes,
    val error: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes <= 0) 0f else (bytesDownloaded.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
}

class RetrievalModelDownloader(
    private val context: Context,
    private val modelPacks: RetrievalModelPackManager,
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueue(): UUID {
        require(BuildConfig.ALLOW_MODEL_DOWNLOAD) { "This offline build does not permit network model downloads" }
        val spec = RetrievalModelCatalog.siglip2BaseQuantized
        val required = spec.archiveSizeBytes + spec.installedBytes + MIN_FREE_AFTER_RETRIEVAL_DOWNLOAD
        require(StatFs(context.filesDir.absolutePath).availableBytes > required) {
            "Not enough app-private storage for ${spec.displayName}"
        }
        val request = OneTimeWorkRequestBuilder<RetrievalModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    fun cancel() = workManager.cancelUniqueWork(WORK_NAME)

    fun progress(): RetrievalDownloadProgress {
        if (modelPacks.status().installed) {
            val total = RetrievalModelCatalog.siglip2BaseQuantized.archiveSizeBytes
            return RetrievalDownloadProgress(GemmaDownloadState.INSTALLED, total, total)
        }
        val info = runCatching {
            workManager.getWorkInfosForUniqueWork(WORK_NAME).get(5, TimeUnit.SECONDS).maxByOrNull { it.runAttemptCount }
        }.getOrNull() ?: return RetrievalDownloadProgress()
        val state = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> GemmaDownloadState.QUEUED
            WorkInfo.State.RUNNING -> if (info.progress.getBoolean(KEY_VERIFYING, false)) GemmaDownloadState.VERIFYING else GemmaDownloadState.DOWNLOADING
            WorkInfo.State.SUCCEEDED -> GemmaDownloadState.INSTALLED
            WorkInfo.State.FAILED -> GemmaDownloadState.FAILED
            WorkInfo.State.CANCELLED -> GemmaDownloadState.CANCELLED
        }
        return RetrievalDownloadProgress(
            state = state,
            bytesDownloaded = info.progress.getLong(KEY_DOWNLOADED, 0),
            error = info.outputData.getString(KEY_ERROR),
        )
    }

    companion object {
        const val WORK_NAME = "siglip2-retrieval-pack-download"
    }
}

class RetrievalModelDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!BuildConfig.ALLOW_MODEL_DOWNLOAD) return@withContext failure("Network model downloads are disabled in this build")
        val spec = RetrievalModelCatalog.siglip2BaseQuantized
        val manager = (applicationContext as AskPhotosApplication).services.retrievalModelPackManager
        val downloadDir = File(applicationContext.filesDir, "models/retrieval/downloads").apply { mkdirs() }
        val partial = File(downloadDir, "${spec.packVersion}.agretrieval.part")
        try {
            setForeground(downloadForeground(spec, partial.length()))
            download(spec, partial)
            setProgress(progressData(partial.length(), verifying = true))
            manager.installCatalogDownload(spec, partial)
            partial.delete()
            EmbeddingIndexScheduler.schedule(applicationContext)
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failure(error.message ?: "Retrieval model download failed")
        }
    }

    private suspend fun download(spec: RetrievalDownloadSpec, partial: File) {
        var existing = partial.length().coerceAtMost(spec.archiveSizeBytes)
        if (partial.length() > spec.archiveSizeBytes) {
            require(partial.delete()) { "Could not discard an oversized partial download" }
            existing = 0
        }
        val connection = openAllowlistedConnection(spec.downloadUrl, existing)
        val response = connection.responseCode
        if (response !in setOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
            connection.disconnect()
            throw IOException("Model host returned HTTP $response")
        }
        if (existing > 0 && response == HttpURLConnection.HTTP_OK) {
            FileOutputStream(partial, false).use { }
            existing = 0
        }
        if (response == HttpURLConnection.HTTP_PARTIAL) {
            val start = connection.getHeaderField("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
            require(start == existing) { "Model host returned an invalid resume range" }
        }
        var written = existing
        var lastProgress = 0L
        connection.inputStream.buffered().use { input ->
            FileOutputStream(partial, existing > 0).use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    written += count
                    require(written <= spec.archiveSizeBytes) { "Downloaded pack exceeds the pinned size" }
                    output.write(buffer, 0, count)
                    val now = System.currentTimeMillis()
                    if (now - lastProgress >= 500) {
                        setProgress(progressData(written))
                        setForeground(downloadForeground(spec, written))
                        lastProgress = now
                    }
                }
                output.fd.sync()
            }
        }
        connection.disconnect()
        require(written == spec.archiveSizeBytes) { "Downloaded retrieval pack is incomplete" }
    }

    private fun openAllowlistedConnection(downloadUrl: String, existing: Long): HttpURLConnection {
        var url = RetrievalDownloadEndpointPolicy.requireAllowed(URL(downloadUrl))
        repeat(MAX_RETRIEVAL_REDIRECTS + 1) { redirectCount ->
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "AgenticGallery/${BuildConfig.VERSION_NAME}")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                connect()
            }
            if (connection.responseCode !in RETRIEVAL_REDIRECT_CODES) return connection
            val location = connection.getHeaderField("Location") ?: run {
                connection.disconnect()
                throw IOException("Model host returned a redirect without a location")
            }
            connection.disconnect()
            require(redirectCount < MAX_RETRIEVAL_REDIRECTS) { "Model download exceeded the redirect limit" }
            url = RetrievalDownloadEndpointPolicy.requireAllowed(URL(url, location))
        }
        error("Model download redirect handling failed")
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        downloadForeground(RetrievalModelCatalog.siglip2BaseQuantized, 0)

    private fun downloadForeground(spec: RetrievalDownloadSpec, downloaded: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(RETRIEVAL_DOWNLOAD_CHANNEL, "SigLIP2 model downloads", NotificationManager.IMPORTANCE_LOW))
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val percent = ((downloaded.toDouble() / spec.archiveSizeBytes) * 100).toInt().coerceIn(0, 100)
        val notification = NotificationCompat.Builder(applicationContext, RETRIEVAL_DOWNLOAD_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${spec.displayName}")
            .setContentText("$percent% - verified on completion")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        return ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun failure(message: String) = Result.failure(Data.Builder().putString(KEY_ERROR, message.take(300)).build())
    private fun progressData(downloaded: Long, verifying: Boolean = false) = Data.Builder()
        .putLong(KEY_DOWNLOADED, downloaded)
        .putBoolean(KEY_VERIFYING, verifying)
        .build()
}

private const val KEY_DOWNLOADED = "retrieval_downloaded"
private const val KEY_VERIFYING = "retrieval_verifying"
private const val KEY_ERROR = "retrieval_error"
private const val RETRIEVAL_DOWNLOAD_CHANNEL = "siglip2-model-download"
private const val MIN_FREE_AFTER_RETRIEVAL_DOWNLOAD = 512L * 1024 * 1024
private const val MAX_RETRIEVAL_REDIRECTS = 5
private val RETRIEVAL_REDIRECT_CODES = setOf(
    HttpURLConnection.HTTP_MOVED_PERM,
    HttpURLConnection.HTTP_MOVED_TEMP,
    HttpURLConnection.HTTP_SEE_OTHER,
    307,
    308,
)
