package io.github.anup42.askalbum

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class FaceModelSpec(
    val packId: String,
    val packVersion: String,
    val displayName: String,
    val repository: String,
    val revision: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    val embeddingDimension: Int,
    val inputSize: Int,
    val cosineThreshold: Float,
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$repository/resolve/$revision/$fileName?download=true"

    val producerVersion: String
        get() = "$packId@$packVersion:$revision:d$embeddingDimension"
}

/** Pinned OpenCV Zoo SFace model embedded in the APK and activated only after SHA-256 verification. */
object FaceModelCatalog {
    val sface = FaceModelSpec(
        packId = "opencv-sface",
        packVersion = "2021dec-fp32-v1",
        displayName = "OpenCV SFace",
        repository = "opencv/face_recognition_sface",
        revision = "c140188d35b7d0050f2dcfdfb8fe3e98d516744f",
        fileName = "face_recognition_sface_2021dec.onnx",
        sizeBytes = 38_696_353L,
        sha256 = "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79",
        license = "Apache-2.0",
        embeddingDimension = 128,
        inputSize = 112,
        cosineThreshold = .363f,
    )
}

data class FaceModelStatus(
    val installed: Boolean = false,
    val name: String = FaceModelCatalog.sface.displayName,
    val version: String = FaceModelCatalog.sface.packVersion,
    val sizeBytes: Long = FaceModelCatalog.sface.sizeBytes,
    val sha256: String = FaceModelCatalog.sface.sha256,
    val license: String = FaceModelCatalog.sface.license,
    val producerVersion: String? = null,
    val error: String? = null,
)

data class InstalledFaceModel(val file: File, val spec: FaceModelSpec)

class FaceModelPackManager(private val context: Context) {
    private val root = File(context.filesDir, "models/face/sface")
    private val modelFile = File(root, FaceModelCatalog.sface.fileName)
    private val verificationMarker = File(root, "verified.sha256")

    fun status(): FaceModelStatus = runCatching {
        val current = current()
        FaceModelStatus(
            installed = current != null,
            producerVersion = current?.spec?.producerVersion,
        )
    }.getOrElse { FaceModelStatus(error = it.message) }

    fun current(): InstalledFaceModel? {
        if (!modelFile.isFile || !verificationMarker.isFile) return null
        val spec = FaceModelCatalog.sface
        require(modelFile.length() == spec.sizeBytes) { "SFace model is incomplete" }
        require(verificationMarker.readText().trim() == spec.sha256) { "SFace verification marker is invalid" }
        return InstalledFaceModel(modelFile, spec)
    }

    suspend fun import(uri: Uri): FaceModelStatus = withContext(Dispatchers.IO) {
        val incoming = File(root, "incoming-${UUID.randomUUID()}.onnx")
        root.mkdirs()
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected SFace model cannot be opened" }
                FileOutputStream(incoming).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= FaceModelCatalog.sface.sizeBytes) { "Selected SFace model is too large" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            installVerified(incoming)
            status()
        } finally {
            incoming.delete()
        }
    }

    internal fun installVerified(source: File): InstalledFaceModel {
        val spec = FaceModelCatalog.sface
        require(source.isFile && source.length() == spec.sizeBytes) { "SFace model has the wrong size" }
        require(sha256(source) == spec.sha256) { "SFace SHA-256 does not match the pinned OpenCV artifact" }
        require(StatFs(context.filesDir.absolutePath).availableBytes > spec.sizeBytes + MIN_FREE_AFTER_FACE_INSTALL) {
            "Not enough app-private storage for SFace"
        }
        require(root.mkdirs() || root.isDirectory) { "Could not create the private face-model directory" }
        val staged = File(root, "${spec.fileName}.next")
        if (staged.exists()) require(staged.delete())
        source.inputStream().use { input ->
            FileOutputStream(staged).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        if (modelFile.exists()) require(modelFile.delete()) { "Could not replace the previous SFace model" }
        require(staged.renameTo(modelFile)) { "Could not activate the verified SFace model" }
        verificationMarker.writeText(spec.sha256)
        return InstalledFaceModel(modelFile, spec)
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
}

data class FaceModelDownloadProgress(
    val state: GemmaDownloadState = GemmaDownloadState.IDLE,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = FaceModelCatalog.sface.sizeBytes,
    val error: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes == 0L) 0f else (bytesDownloaded.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
}

class FaceModelDownloader(private val context: Context, private val packs: FaceModelPackManager) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueue(): UUID {
        require(BuildConfig.ALLOW_MODEL_DOWNLOAD) { "This offline build does not permit network model downloads" }
        val spec = FaceModelCatalog.sface
        require(StatFs(context.filesDir.absolutePath).availableBytes > spec.sizeBytes + MIN_FREE_AFTER_FACE_INSTALL) {
            "Not enough app-private storage for SFace"
        }
        val request = OneTimeWorkRequestBuilder<FaceModelDownloadWorker>()
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

    fun progress(): FaceModelDownloadProgress {
        if (packs.status().installed) {
            return FaceModelDownloadProgress(GemmaDownloadState.INSTALLED, FaceModelCatalog.sface.sizeBytes, FaceModelCatalog.sface.sizeBytes)
        }
        val info = runCatching {
            workManager.getWorkInfosForUniqueWork(WORK_NAME).get(5, TimeUnit.SECONDS).maxByOrNull { it.runAttemptCount }
        }.getOrNull() ?: return FaceModelDownloadProgress()
        val state = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> GemmaDownloadState.QUEUED
            WorkInfo.State.RUNNING -> if (info.progress.getBoolean(KEY_VERIFYING, false)) GemmaDownloadState.VERIFYING else GemmaDownloadState.DOWNLOADING
            WorkInfo.State.SUCCEEDED -> GemmaDownloadState.INSTALLED
            WorkInfo.State.FAILED -> GemmaDownloadState.FAILED
            WorkInfo.State.CANCELLED -> GemmaDownloadState.CANCELLED
        }
        return FaceModelDownloadProgress(
            state = state,
            bytesDownloaded = info.progress.getLong(KEY_DOWNLOADED, 0),
            error = info.outputData.getString(KEY_ERROR),
        )
    }

    private companion object {
        const val WORK_NAME = "opencv-sface-model-download"
    }
}

class FaceModelDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!BuildConfig.ALLOW_MODEL_DOWNLOAD) return@withContext failure("Network model downloads are disabled in this build")
        val spec = FaceModelCatalog.sface
        val partial = File(applicationContext.filesDir, "models/face/downloads/${spec.fileName}.part").apply { parentFile?.mkdirs() }
        try {
            setForeground(notification(partial.length()))
            download(spec, partial)
            setProgress(progress(partial.length(), verifying = true))
            (applicationContext as AskAlbumApplication).services.faceModelPackManager.installVerified(partial)
            partial.delete()
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failure(error.message ?: "SFace download failed")
        }
    }

    private suspend fun download(spec: FaceModelSpec, partial: File) {
        var existing = partial.length()
        if (existing > spec.sizeBytes) {
            require(partial.delete()) { "Could not discard an oversized SFace download" }
            existing = 0
        }
        val connection = openConnection(spec.downloadUrl, existing)
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
                    require(written <= spec.sizeBytes) { "Downloaded SFace model exceeds the pinned size" }
                    output.write(buffer, 0, count)
                    val now = System.currentTimeMillis()
                    if (now - lastProgress >= 500) {
                        setProgress(progress(written))
                        setForeground(notification(written))
                        lastProgress = now
                    }
                }
                output.fd.sync()
            }
        }
        connection.disconnect()
        require(written == spec.sizeBytes) { "Downloaded SFace model is incomplete" }
    }

    private fun openConnection(downloadUrl: String, existing: Long): HttpURLConnection {
        var url = ModelDownloadEndpointPolicy.requireAllowed(URL(downloadUrl))
        repeat(MAX_FACE_REDIRECTS + 1) { redirectCount ->
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "AskAlbum/${BuildConfig.VERSION_NAME}")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                connect()
            }
            if (connection.responseCode !in FACE_REDIRECT_CODES) return connection
            val location = connection.getHeaderField("Location") ?: error("Model redirect has no location")
            connection.disconnect()
            require(redirectCount < MAX_FACE_REDIRECTS) { "SFace download exceeded the redirect limit" }
            url = ModelDownloadEndpointPolicy.requireAllowed(URL(url, location))
        }
        error("SFace redirect handling failed")
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = notification(0)

    private fun notification(downloaded: Long): ForegroundInfo {
        val spec = FaceModelCatalog.sface
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(FACE_DOWNLOAD_CHANNEL, "Face model downloads", NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val percent = ((downloaded.toDouble() / spec.sizeBytes) * 100).toInt().coerceIn(0, 100)
        val notification = NotificationCompat.Builder(applicationContext, FACE_DOWNLOAD_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${spec.displayName}")
            .setContentText("$percent% • verified on completion")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        return ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun failure(message: String) = Result.failure(Data.Builder().putString(KEY_ERROR, message.take(300)).build())
    private fun progress(downloaded: Long, verifying: Boolean = false) = Data.Builder()
        .putLong(KEY_DOWNLOADED, downloaded)
        .putBoolean(KEY_VERIFYING, verifying)
        .build()
}

private const val KEY_DOWNLOADED = "sface_downloaded"
private const val KEY_VERIFYING = "sface_verifying"
private const val KEY_ERROR = "sface_error"
private const val FACE_DOWNLOAD_CHANNEL = "face-model-download"
private const val MIN_FREE_AFTER_FACE_INSTALL = 128L * 1024 * 1024
private const val MAX_FACE_REDIRECTS = 5
private val FACE_REDIRECT_CODES = setOf(
    HttpURLConnection.HTTP_MOVED_PERM,
    HttpURLConnection.HTTP_MOVED_TEMP,
    HttpURLConnection.HTTP_SEE_OTHER,
    307,
    308,
)
