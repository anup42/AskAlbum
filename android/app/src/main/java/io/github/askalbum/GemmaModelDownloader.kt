package io.github.anup42.askalbum

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

data class GemmaDownloadSpec(
    val tier: GemmaModelTier,
    val displayName: String,
    val repository: String,
    val revision: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val deviceClassRamGb: Int,
    val minimumRamBytes: Long,
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$repository/resolve/$revision/$fileName?download=true"
}

/** The only hosts the consumer build may contact, all for a user-selected model download. */
object ModelDownloadEndpointPolicy {
    private val allowedHosts = setOf("huggingface.co")
    private val allowedHostSuffixes = setOf(".huggingface.co", ".hf.co")

    fun requireAllowed(url: URL): URL {
        require(url.protocol.equals("https", ignoreCase = true)) { "Model downloads require HTTPS" }
        require(url.userInfo == null) { "Model download URLs cannot contain credentials" }
        require(url.port == -1 || url.port == 443) { "Model downloads require the HTTPS port" }
        val host = url.host.lowercase()
        require(host in allowedHosts || allowedHostSuffixes.any(host::endsWith)) {
            "Model download host is not allowlisted"
        }
        return url
    }
}

/** Immutable catalog mirrored from Google AI Edge Gallery 1.0.15 and pinned to Hugging Face LFS digests. */
object GemmaModelCatalog {
    val e2b = GemmaDownloadSpec(
        tier = GemmaModelTier.E2B,
        displayName = "Gemma 4 E2B",
        repository = "litert-community/gemma-4-E2B-it-litert-lm",
        revision = "7fa1d78473894f7e736a21d920c3aa80f950c0db",
        fileName = "gemma-4-E2B-it.litertlm",
        sizeBytes = 2_583_085_056L,
        sha256 = "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42",
        deviceClassRamGb = 8,
        minimumRamBytes = 6L * DOWNLOAD_GIB,
    )
    val e4b = GemmaDownloadSpec(
        tier = GemmaModelTier.E4B,
        displayName = "Gemma 4 E4B",
        repository = "litert-community/gemma-4-E4B-it-litert-lm",
        revision = "9695417f248178c63a9f318c6e0c56cb917cb837",
        fileName = "gemma-4-E4B-it.litertlm",
        sizeBytes = 3_654_467_584L,
        sha256 = "f335f2bfd1b758dc6476db16c0f41854bd6237e2658d604cbe566bcefd00a7bc",
        deviceClassRamGb = 12,
        minimumRamBytes = 10L * DOWNLOAD_GIB,
    )
    val all = listOf(e2b, e4b)

    fun require(tier: GemmaModelTier): GemmaDownloadSpec = all.single { it.tier == tier }
}

enum class GemmaDownloadState { IDLE, QUEUED, DOWNLOADING, VERIFYING, INSTALLED, FAILED, CANCELLED }

data class GemmaDownloadProgress(
    val tier: GemmaModelTier = GemmaModelTier.E2B,
    val state: GemmaDownloadState = GemmaDownloadState.IDLE,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = GemmaModelCatalog.e2b.sizeBytes,
    val error: String? = null,
) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else (bytesDownloaded.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
}

class GemmaModelDownloader(
    private val context: Context,
    private val modelPacks: ModelPackManager,
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueue(tier: GemmaModelTier): UUID {
        require(BuildConfig.ALLOW_MODEL_DOWNLOAD) { "This offline build does not permit network model downloads" }
        val spec = GemmaModelCatalog.require(tier)
        val assessment = modelPacks.assess(spec)
        require(assessment.supported) { assessment.reason }
        require(tier != GemmaModelTier.E4B || assessment.recommendedTier == GemmaModelTier.E4B) { assessment.reason }
        require(StatFs(context.filesDir.absolutePath).availableBytes > spec.sizeBytes + MIN_FREE_AFTER_GEMMA_DOWNLOAD) {
            "Not enough app-private storage for ${spec.displayName}"
        }
        modelPacks.selectTier(tier)
        val request = OneTimeWorkRequestBuilder<GemmaDownloadWorker>()
            .setInputData(Data.Builder().putString(KEY_GEMMA_TIER, tier.name).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(workName(tier), ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    fun cancel(tier: GemmaModelTier) = workManager.cancelUniqueWork(workName(tier))

    fun progress(tier: GemmaModelTier): GemmaDownloadProgress {
        if (modelPacks.isInstalled(tier)) {
            return GemmaDownloadProgress(tier, GemmaDownloadState.INSTALLED, GemmaModelCatalog.require(tier).sizeBytes, GemmaModelCatalog.require(tier).sizeBytes)
        }
        val info = runCatching {
            workManager.getWorkInfosForUniqueWork(workName(tier)).get(5, TimeUnit.SECONDS).maxByOrNull { it.runAttemptCount }
        }.getOrNull() ?: return GemmaDownloadProgress(tier = tier, totalBytes = GemmaModelCatalog.require(tier).sizeBytes)
        val state = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> GemmaDownloadState.QUEUED
            WorkInfo.State.RUNNING -> if (info.progress.getBoolean(KEY_GEMMA_VERIFYING, false)) GemmaDownloadState.VERIFYING else GemmaDownloadState.DOWNLOADING
            WorkInfo.State.SUCCEEDED -> GemmaDownloadState.INSTALLED
            WorkInfo.State.FAILED -> GemmaDownloadState.FAILED
            WorkInfo.State.CANCELLED -> GemmaDownloadState.CANCELLED
        }
        return GemmaDownloadProgress(
            tier = tier,
            state = state,
            bytesDownloaded = info.progress.getLong(KEY_GEMMA_DOWNLOADED, 0),
            totalBytes = GemmaModelCatalog.require(tier).sizeBytes,
            error = info.outputData.getString(KEY_GEMMA_ERROR),
        )
    }

    companion object {
        fun workName(tier: GemmaModelTier) = "gemma-4-${tier.name.lowercase()}-download"
    }
}

class GemmaDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!BuildConfig.ALLOW_MODEL_DOWNLOAD) return@withContext failure("Network model downloads are disabled in this build")
        val tier = runCatching { GemmaModelTier.valueOf(requireNotNull(inputData.getString(KEY_GEMMA_TIER))) }
            .getOrElse { return@withContext failure("Invalid Gemma tier") }
        val spec = GemmaModelCatalog.require(tier)
        val modelPacks = (applicationContext as AskAlbumApplication).modelPackManager
        val assessment = modelPacks.assess(spec)
        if (!assessment.supported || (tier == GemmaModelTier.E4B && assessment.recommendedTier != GemmaModelTier.E4B)) {
            return@withContext failure(assessment.reason)
        }
        val downloadDir = File(applicationContext.filesDir, "models/gemma/downloads").apply { mkdirs() }
        val partial = File(downloadDir, "${tier.name.lowercase()}.litertlm.part")
        try {
            setForeground(downloadForeground(spec, partial.length()))
            download(spec, partial)
            setProgress(progressData(partial.length(), verifying = true))
            modelPacks.installDownloaded(spec, partial)
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failure(error.message ?: "Model download failed")
        }
    }

    private suspend fun download(spec: GemmaDownloadSpec, partial: File) {
        var existing = partial.length().coerceAtMost(spec.sizeBytes)
        if (partial.length() > spec.sizeBytes) {
            require(partial.delete()) { "Could not discard an oversized partial download" }
            existing = 0
        }
        val connection = openAllowlistedConnection(spec, existing)
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
                    require(written <= spec.sizeBytes) { "Downloaded model exceeds the pinned size" }
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
        require(written == spec.sizeBytes) { "Downloaded model is incomplete" }
    }

    private fun openAllowlistedConnection(spec: GemmaDownloadSpec, existing: Long): HttpURLConnection {
        var url = ModelDownloadEndpointPolicy.requireAllowed(URL(spec.downloadUrl))
        repeat(MAX_MODEL_REDIRECTS + 1) { redirectCount ->
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "AskAlbum/${BuildConfig.VERSION_NAME}")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                connect()
            }
            if (connection.responseCode !in MODEL_REDIRECT_CODES) return connection
            val location = connection.getHeaderField("Location")
                ?: run {
                    connection.disconnect()
                    throw IOException("Model host returned a redirect without a location")
                }
            connection.disconnect()
            require(redirectCount < MAX_MODEL_REDIRECTS) { "Model download exceeded the redirect limit" }
            url = ModelDownloadEndpointPolicy.requireAllowed(URL(url, location))
        }
        error("Model download redirect handling failed")
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val tier = runCatching { GemmaModelTier.valueOf(inputData.getString(KEY_GEMMA_TIER).orEmpty()) }.getOrDefault(GemmaModelTier.E2B)
        return downloadForeground(GemmaModelCatalog.require(tier), 0)
    }

    private fun downloadForeground(spec: GemmaDownloadSpec, downloaded: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(GEMMA_DOWNLOAD_CHANNEL, "Gemma model downloads", NotificationManager.IMPORTANCE_LOW))
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val percent = ((downloaded.toDouble() / spec.sizeBytes) * 100).toInt().coerceIn(0, 100)
        val notification = NotificationCompat.Builder(applicationContext, GEMMA_DOWNLOAD_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${spec.displayName}")
            .setContentText("$percent% • verified on completion")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        return ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun failure(message: String) = Result.failure(Data.Builder().putString(KEY_GEMMA_ERROR, message.take(300)).build())
    private fun progressData(downloaded: Long, verifying: Boolean = false) = Data.Builder()
        .putLong(KEY_GEMMA_DOWNLOADED, downloaded)
        .putBoolean(KEY_GEMMA_VERIFYING, verifying)
        .build()
}

private const val KEY_GEMMA_TIER = "gemma_tier"
private const val KEY_GEMMA_DOWNLOADED = "gemma_downloaded"
private const val KEY_GEMMA_VERIFYING = "gemma_verifying"
private const val KEY_GEMMA_ERROR = "gemma_error"
private const val GEMMA_DOWNLOAD_CHANNEL = "gemma-model-download"
private const val MIN_FREE_AFTER_GEMMA_DOWNLOAD = 512L * 1024 * 1024
private const val DOWNLOAD_GIB = 1024L * 1024 * 1024
private const val MAX_MODEL_REDIRECTS = 5
private val MODEL_REDIRECT_CODES = setOf(
    HttpURLConnection.HTTP_MOVED_PERM,
    HttpURLConnection.HTTP_MOVED_TEMP,
    HttpURLConnection.HTTP_SEE_OTHER,
    307,
    308,
)
