package com.samsung.agenticgallery

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
import java.util.zip.ZipInputStream

data class OcrModelArtifact(
    val targetName: String,
    val repository: String,
    val revision: String,
    val sourceName: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$repository/resolve/$revision/$sourceName?download=true"
}

data class OcrModelSpec(
    val packId: String,
    val version: String,
    val displayName: String,
    val license: String,
    val languages: String,
    val artifacts: List<OcrModelArtifact>,
) {
    val producerVersion: String get() = "$packId@$version"
    val sizeBytes: Long get() = artifacts.sumOf(OcrModelArtifact::sizeBytes)
}

object OcrModelCatalog {
    val paddleV5Multilingual = OcrModelSpec(
        packId = "paddleocr-ppocrv5-mobile",
        version = "official-onnx-latin-devanagari-v1",
        displayName = "PaddleOCR PP-OCRv5 Mobile",
        license = "Apache-2.0",
        languages = "Latin-script languages plus Hindi/Devanagari",
        artifacts = listOf(
            OcrModelArtifact(
                "det.onnx", "PaddlePaddle/PP-OCRv5_mobile_det_onnx",
                "e6f4fa85f00e168c862bc462aebca69eef9b3d3d", "inference.onnx", 4_826_518,
                "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d",
            ),
            OcrModelArtifact(
                "latin.onnx", "PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx",
                "89d3a50e2c27e2e7cceeab0e944c25c807d5db4f", "inference.onnx", 8_042_023,
                "7888113072263cb471b93f66dd5e2ad70548dc526fa1ace760d0d973dd121498",
            ),
            OcrModelArtifact(
                "latin.yml", "PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx",
                "89d3a50e2c27e2e7cceeab0e944c25c807d5db4f", "inference.yml", 6_817,
                "0bbe984570f597af3638e50bdf2e8276f3ab26a61966096538b3b0d1849f5c84",
            ),
            OcrModelArtifact(
                "devanagari.onnx", "PaddlePaddle/devanagari_PP-OCRv5_mobile_rec_onnx",
                "251aec19e36739540d35e2cc943f6aa7503b98e5", "inference.onnx", 7_912_311,
                "cb789212ce96c69d3e74728ae4309d179281d68cb3945d0616b67cafab41c986",
            ),
            OcrModelArtifact(
                "devanagari.yml", "PaddlePaddle/devanagari_PP-OCRv5_mobile_rec_onnx",
                "251aec19e36739540d35e2cc943f6aa7503b98e5", "inference.yml", 5_027,
                "9bd172dd26440c8ce94d1cde5d5baea6aefdc7cf3c5c8492e0beedef656d4e54",
            ),
        ),
    )
}

data class InstalledOcrModelPack(
    val root: File,
    val spec: OcrModelSpec,
) {
    val detector: File get() = File(root, "det.onnx")
    val latinRecognizer: File get() = File(root, "latin.onnx")
    val latinConfig: File get() = File(root, "latin.yml")
    val devanagariRecognizer: File get() = File(root, "devanagari.onnx")
    val devanagariConfig: File get() = File(root, "devanagari.yml")
}

data class OcrModelStatus(
    val installed: Boolean = false,
    val name: String = OcrModelCatalog.paddleV5Multilingual.displayName,
    val version: String = OcrModelCatalog.paddleV5Multilingual.version,
    val sizeBytes: Long = OcrModelCatalog.paddleV5Multilingual.sizeBytes,
    val license: String = OcrModelCatalog.paddleV5Multilingual.license,
    val languages: String = OcrModelCatalog.paddleV5Multilingual.languages,
    val producerVersion: String? = null,
    val error: String? = null,
)

class OcrModelPackManager(private val context: Context) {
    private val modelRoot = File(context.filesDir, "models/ocr/paddle-v5-multilingual")
    private val activeRoot = File(modelRoot, "active")
    private val marker = File(activeRoot, "verified.pack")

    fun status(): OcrModelStatus = runCatching {
        val installed = current()
        OcrModelStatus(installed = installed != null, producerVersion = installed?.spec?.producerVersion)
    }.getOrElse { OcrModelStatus(error = it.message) }

    fun current(): InstalledOcrModelPack? {
        val spec = OcrModelCatalog.paddleV5Multilingual
        if (!marker.isFile || marker.readText().trim() != spec.producerVersion) return null
        spec.artifacts.forEach { artifact ->
            require(File(activeRoot, artifact.targetName).let { it.isFile && it.length() == artifact.sizeBytes }) {
                "PaddleOCR model pack is incomplete"
            }
        }
        return InstalledOcrModelPack(activeRoot, spec)
    }

    suspend fun importArchive(uri: Uri): OcrModelStatus = withContext(Dispatchers.IO) {
        val staging = File(modelRoot, "import-${UUID.randomUUID()}")
        require(staging.mkdirs()) { "Could not create OCR import staging" }
        try {
            val allowed = OcrModelCatalog.paddleV5Multilingual.artifacts.associateBy(OcrModelArtifact::targetName)
            val seen = mutableSetOf<String>()
            context.contentResolver.openInputStream(uri).use { raw ->
                requireNotNull(raw) { "The selected OCR pack cannot be opened" }
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory) {
                            val name = entry.name.substringAfterLast('/')
                            val artifact = allowed[name] ?: error("Unexpected OCR pack file: $name")
                            require(seen.add(name)) { "Duplicate OCR pack file: $name" }
                            FileOutputStream(File(staging, name)).use { output ->
                                copyBounded(zip, output, artifact.sizeBytes)
                                output.fd.sync()
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            require(seen == allowed.keys) { "OCR pack archive is missing required files" }
            installVerified(staging)
            status()
        } finally {
            staging.deleteRecursively()
        }
    }

    internal fun installVerified(sourceDirectory: File): InstalledOcrModelPack {
        val spec = OcrModelCatalog.paddleV5Multilingual
        verifyDirectory(sourceDirectory, spec)
        require(StatFs(context.filesDir.absolutePath).availableBytes > spec.sizeBytes + MIN_FREE_AFTER_OCR_INSTALL) {
            "Not enough app-private storage for PaddleOCR"
        }
        require(modelRoot.mkdirs() || modelRoot.isDirectory)
        val next = File(modelRoot, "active.next")
        next.deleteRecursively()
        require(next.mkdirs())
        spec.artifacts.forEach { artifact ->
            File(sourceDirectory, artifact.targetName).copyTo(File(next, artifact.targetName), overwrite = true)
        }
        File(next, "verified.pack").writeText(spec.producerVersion)
        activeRoot.deleteRecursively()
        require(next.renameTo(activeRoot)) { "Could not activate the verified PaddleOCR pack" }
        return InstalledOcrModelPack(activeRoot, spec)
    }

    private fun verifyDirectory(directory: File, spec: OcrModelSpec) {
        spec.artifacts.forEach { artifact ->
            val file = File(directory, artifact.targetName)
            require(file.isFile && file.length() == artifact.sizeBytes) { "${artifact.targetName} has the wrong size" }
            require(sha256(file) == artifact.sha256) { "${artifact.targetName} failed SHA-256 verification" }
        }
    }

    private fun copyBounded(input: ZipInputStream, output: FileOutputStream, expected: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= expected) { "OCR pack entry exceeds its pinned size" }
            output.write(buffer, 0, count)
        }
        require(total == expected) { "OCR pack entry is incomplete" }
    }
}

data class OcrModelDownloadProgress(
    val state: GemmaDownloadState = GemmaDownloadState.IDLE,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = OcrModelCatalog.paddleV5Multilingual.sizeBytes,
    val error: String? = null,
) {
    val fraction: Float get() = if (totalBytes == 0L) 0f else (bytesDownloaded.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
}

class OcrModelDownloader(private val context: Context, private val manager: OcrModelPackManager) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueue(): UUID {
        require(BuildConfig.ALLOW_MODEL_DOWNLOAD) { "This offline build does not permit network model downloads" }
        val spec = OcrModelCatalog.paddleV5Multilingual
        require(StatFs(context.filesDir.absolutePath).availableBytes > spec.sizeBytes + MIN_FREE_AFTER_OCR_INSTALL)
        val request = OneTimeWorkRequestBuilder<OcrModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .build()
        workManager.enqueueUniqueWork(OCR_DOWNLOAD_WORK, ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    fun cancel() = workManager.cancelUniqueWork(OCR_DOWNLOAD_WORK)

    fun progress(): OcrModelDownloadProgress {
        if (manager.status().installed) {
            val size = OcrModelCatalog.paddleV5Multilingual.sizeBytes
            return OcrModelDownloadProgress(GemmaDownloadState.INSTALLED, size, size)
        }
        val info = runCatching { workManager.getWorkInfosForUniqueWork(OCR_DOWNLOAD_WORK).get(5, TimeUnit.SECONDS).maxByOrNull { it.runAttemptCount } }
            .getOrNull() ?: return OcrModelDownloadProgress()
        val state = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> GemmaDownloadState.QUEUED
            WorkInfo.State.RUNNING -> if (info.progress.getBoolean(OCR_VERIFYING, false)) GemmaDownloadState.VERIFYING else GemmaDownloadState.DOWNLOADING
            WorkInfo.State.SUCCEEDED -> GemmaDownloadState.INSTALLED
            WorkInfo.State.FAILED -> GemmaDownloadState.FAILED
            WorkInfo.State.CANCELLED -> GemmaDownloadState.CANCELLED
        }
        return OcrModelDownloadProgress(state, info.progress.getLong(OCR_DOWNLOADED, 0), error = info.outputData.getString(OCR_ERROR))
    }
}

class OcrModelDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!BuildConfig.ALLOW_MODEL_DOWNLOAD) return@withContext failure("Network model downloads are disabled")
        val spec = OcrModelCatalog.paddleV5Multilingual
        val staging = File(applicationContext.filesDir, "models/ocr/downloads/${spec.version}").apply { mkdirs() }
        try {
            setForeground(notification(downloaded(staging)))
            spec.artifacts.forEach { artifact -> download(artifact, staging, spec) }
            setProgress(progress(spec.sizeBytes, verifying = true))
            (applicationContext as AgenticGalleryApplication).services.ocrModelPackManager.installVerified(staging)
            staging.deleteRecursively()
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failure(error.message ?: "PaddleOCR download failed")
        }
    }

    private suspend fun download(artifact: OcrModelArtifact, staging: File, spec: OcrModelSpec) {
        val destination = File(staging, artifact.targetName)
        var existing = destination.length()
        if (existing > artifact.sizeBytes) {
            require(destination.delete())
            existing = 0
        }
        if (existing == artifact.sizeBytes && sha256(destination) == artifact.sha256) return
        val connection = openConnection(artifact.downloadUrl, existing)
        val response = connection.responseCode
        require(response == HttpURLConnection.HTTP_OK || response == HttpURLConnection.HTTP_PARTIAL) { "Model host returned HTTP $response" }
        if (existing > 0 && response == HttpURLConnection.HTTP_OK) {
            FileOutputStream(destination, false).use { }
            existing = 0
        }
        if (response == HttpURLConnection.HTTP_PARTIAL) {
            val start = connection.getHeaderField("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
            require(start == existing) { "Model host returned an invalid resume range" }
        }
        var written = existing
        connection.inputStream.buffered().use { input ->
            FileOutputStream(destination, existing > 0).use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    written += count
                    require(written <= artifact.sizeBytes) { "OCR artifact exceeds pinned size" }
                    output.write(buffer, 0, count)
                    setProgress(progress(downloaded(staging)))
                }
                output.fd.sync()
            }
        }
        connection.disconnect()
        require(written == artifact.sizeBytes && sha256(destination) == artifact.sha256) { "OCR artifact verification failed" }
        setForeground(notification(downloaded(staging)))
    }

    private fun openConnection(downloadUrl: String, existing: Long): HttpURLConnection {
        var url = ModelDownloadEndpointPolicy.requireAllowed(URL(downloadUrl))
        repeat(MAX_OCR_REDIRECTS + 1) { redirectCount ->
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "AgenticGallery/${BuildConfig.VERSION_NAME}")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                connect()
            }
            if (connection.responseCode !in OCR_REDIRECT_CODES) return connection
            val location = connection.getHeaderField("Location") ?: error("Model redirect has no location")
            connection.disconnect()
            require(redirectCount < MAX_OCR_REDIRECTS) { "PaddleOCR download exceeded redirect limit" }
            url = ModelDownloadEndpointPolicy.requireAllowed(URL(url, location))
        }
        error("PaddleOCR redirect handling failed")
    }

    private fun downloaded(staging: File): Long = OcrModelCatalog.paddleV5Multilingual.artifacts.sumOf {
        File(staging, it.targetName).length().coerceAtMost(it.sizeBytes)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = notification(0)

    private fun notification(downloaded: Long): ForegroundInfo {
        val spec = OcrModelCatalog.paddleV5Multilingual
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(OCR_DOWNLOAD_CHANNEL, "OCR model downloads", NotificationManager.IMPORTANCE_LOW),
        )
        val pending = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val percent = ((downloaded.toDouble() / spec.sizeBytes) * 100).toInt().coerceIn(0, 100)
        val notification = NotificationCompat.Builder(applicationContext, OCR_DOWNLOAD_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${spec.displayName}")
            .setContentText("$percent% • verified on completion")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        return ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun failure(message: String) = Result.failure(Data.Builder().putString(OCR_ERROR, message.take(300)).build())
    private fun progress(downloaded: Long, verifying: Boolean = false) = Data.Builder()
        .putLong(OCR_DOWNLOADED, downloaded).putBoolean(OCR_VERIFYING, verifying).build()
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

private const val OCR_DOWNLOAD_WORK = "paddleocr-v5-model-download"
private const val OCR_DOWNLOADED = "paddleocr_downloaded"
private const val OCR_VERIFYING = "paddleocr_verifying"
private const val OCR_ERROR = "paddleocr_error"
private const val OCR_DOWNLOAD_CHANNEL = "ocr-model-download"
private const val MIN_FREE_AFTER_OCR_INSTALL = 128L * 1024 * 1024
private const val MAX_OCR_REDIRECTS = 5
private val OCR_REDIRECT_CODES = setOf(
    HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP,
    HttpURLConnection.HTTP_SEE_OTHER, 307, 308,
)
