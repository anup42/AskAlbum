package com.samsung.agenticgallery

import android.content.Context
import android.os.StatFs
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
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

object EmbeddedFaceModel {
    const val ASSET_PATH = "models/face/face_recognition_sface_2021dec.onnx"
}

class EmbeddedFaceModelProvisioner(
    private val context: Context,
    private val packs: FaceModelPackManager,
) {
    private val workManager = WorkManager.getInstance(context)

    fun enqueueIfNeeded(): UUID? {
        if (packs.status().installed) return null
        val required = FaceModelCatalog.sface.sizeBytes * 2 + MIN_FREE_AFTER_EMBEDDED_FACE_INSTALL
        require(StatFs(context.filesDir.absolutePath).availableBytes > required) {
            "Not enough app-private storage to install embedded SFace"
        }
        val request = OneTimeWorkRequestBuilder<EmbeddedFaceModelInstallWorker>()
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    fun progress(): FaceModelDownloadProgress {
        if (packs.status().installed) {
            val total = FaceModelCatalog.sface.sizeBytes
            return FaceModelDownloadProgress(GemmaDownloadState.INSTALLED, total, total)
        }
        val info = runCatching {
            workManager.getWorkInfosForUniqueWork(WORK_NAME).get(5, TimeUnit.SECONDS).maxByOrNull { it.runAttemptCount }
        }.getOrNull() ?: return FaceModelDownloadProgress()
        val state = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> GemmaDownloadState.QUEUED
            WorkInfo.State.RUNNING -> if (info.progress.getBoolean(KEY_EMBEDDED_FACE_VERIFYING, false)) {
                GemmaDownloadState.VERIFYING
            } else {
                GemmaDownloadState.DOWNLOADING
            }
            WorkInfo.State.SUCCEEDED -> GemmaDownloadState.INSTALLED
            WorkInfo.State.FAILED -> GemmaDownloadState.FAILED
            WorkInfo.State.CANCELLED -> GemmaDownloadState.CANCELLED
        }
        return FaceModelDownloadProgress(
            state = state,
            bytesDownloaded = info.progress.getLong(KEY_EMBEDDED_FACE_COPIED, 0),
            totalBytes = FaceModelCatalog.sface.sizeBytes,
            error = info.outputData.getString(KEY_EMBEDDED_FACE_ERROR),
        )
    }

    companion object {
        const val WORK_NAME = "embedded-sface-model-install"
    }
}

class EmbeddedFaceModelInstallWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val application = applicationContext as AgenticGalleryApplication
        val packs = application.services.faceModelPackManager
        if (packs.status().installed) return@withContext Result.success()
        val spec = FaceModelCatalog.sface
        val partial = File(applicationContext.filesDir, "models/face/embedded/${spec.fileName}.part").apply {
            parentFile?.mkdirs()
        }
        try {
            copyEmbeddedModel(partial)
            setProgress(progressData(partial.length(), verifying = true))
            packs.installVerified(partial)
            partial.delete()
            if (application.repository.peopleIndexStatus().enabled) application.repository.onFaceModelInstalled()
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            partial.delete()
            Result.failure(Data.Builder().putString(KEY_EMBEDDED_FACE_ERROR, (error.message ?: "Embedded SFace installation failed").take(300)).build())
        }
    }

    private suspend fun copyEmbeddedModel(partial: File) {
        val spec = FaceModelCatalog.sface
        val assetLength = applicationContext.assets.openFd(EmbeddedFaceModel.ASSET_PATH).use { it.length }
        require(assetLength == spec.sizeBytes) { "Embedded SFace asset has the wrong size" }
        var existing = partial.length().coerceAtMost(spec.sizeBytes)
        if (partial.length() > spec.sizeBytes) {
            require(partial.delete()) { "Could not discard an oversized embedded SFace copy" }
            existing = 0
        }
        applicationContext.assets.open(EmbeddedFaceModel.ASSET_PATH).buffered().use { input ->
            var skipped = 0L
            while (skipped < existing) {
                val count = input.skip(existing - skipped)
                require(count > 0) { "Could not resume embedded SFace extraction" }
                skipped += count
            }
            var copied = existing
            var lastProgress = 0L
            FileOutputStream(partial, existing > 0).use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= spec.sizeBytes) { "Embedded SFace exceeds the pinned size" }
                    output.write(buffer, 0, count)
                    val now = System.currentTimeMillis()
                    if (now - lastProgress >= 500) {
                        setProgress(progressData(copied))
                        lastProgress = now
                    }
                }
                output.fd.sync()
            }
            require(copied == spec.sizeBytes) { "Embedded SFace copy is incomplete" }
        }
    }

    private fun progressData(copied: Long, verifying: Boolean = false) = Data.Builder()
        .putLong(KEY_EMBEDDED_FACE_COPIED, copied)
        .putBoolean(KEY_EMBEDDED_FACE_VERIFYING, verifying)
        .build()
}

private const val KEY_EMBEDDED_FACE_COPIED = "embedded_sface_copied"
private const val KEY_EMBEDDED_FACE_VERIFYING = "embedded_sface_verifying"
private const val KEY_EMBEDDED_FACE_ERROR = "embedded_sface_error"
private const val MIN_FREE_AFTER_EMBEDDED_FACE_INSTALL = 128L * 1024 * 1024
