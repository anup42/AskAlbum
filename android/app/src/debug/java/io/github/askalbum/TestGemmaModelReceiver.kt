package io.github.anup42.askalbum

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Debug-only bridge for exercising the production Gemma downloader from the device harness. */
class TestGemmaModelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        executor.execute {
            val tierName = intent.getStringExtra(EXTRA_TIER).orEmpty()
            val operationId = intent.getStringExtra(EXTRA_OPERATION_ID).orEmpty()
            try {
                require(OPERATION_ID.matches(operationId)) { "Invalid operation ID" }
                val tier = GemmaModelTier.valueOf(tierName)
                val application = context.applicationContext as AskAlbumApplication
                val downloader = GemmaModelDownloader(application, application.modelPackManager)
                when (intent.action) {
                    ACTION_DOWNLOAD -> if (!application.modelPackManager.isInstalled(tier)) downloader.enqueue(tier)
                    ACTION_CANCEL -> downloader.cancel(tier)
                    ACTION_REPORT -> Unit
                    else -> error("Unsupported Gemma test action")
                }
                writeStatus(context, tier, operationId, downloader.progress(tier), null)
            } catch (error: Throwable) {
                val tier = runCatching { GemmaModelTier.valueOf(tierName) }.getOrDefault(GemmaModelTier.E2B)
                writeStatus(context, tier, operationId, null, error.message ?: error.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }
    }

    private fun writeStatus(
        context: Context,
        tier: GemmaModelTier,
        operationId: String,
        progress: GemmaDownloadProgress?,
        error: String?,
    ) {
        val application = context.applicationContext as AskAlbumApplication
        val spec = GemmaModelCatalog.require(tier)
        val assessment = application.modelPackManager.assess(spec)
        val decision = BackgroundWorkAdmissionPolicy(context).evaluate()
        val payload = JSONObject()
            .put("state", if (error == null) "COMPLETE" else "FAILED")
            .put("operationId", operationId)
            .put("tier", tier.name)
            .put("downloadState", progress?.state?.name ?: GemmaDownloadState.FAILED.name)
            .put("bytesDownloaded", progress?.bytesDownloaded ?: 0L)
            .put("totalBytes", spec.sizeBytes)
            .put("installed", application.modelPackManager.isInstalled(tier))
            .put("repository", spec.repository)
            .put("revision", spec.revision)
            .put("fileName", spec.fileName)
            .put("sha256", spec.sha256)
            .put("deviceSupported", assessment.supported)
            .put("recommendedTier", assessment.recommendedTier.name)
            .put("deviceReason", assessment.reason)
            .put("thermalStatus", decision.thermalStatus)
            .put("error", error ?: progress?.error)
        val directory = File(context.filesDir, "test-models").apply { mkdirs() }
        val target = File(directory, "gemma-${tier.name.lowercase()}-status.json")
        val temporary = File(directory, "${target.name}.next")
        FileOutputStream(temporary).use { output ->
            output.write(payload.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (target.exists()) require(target.delete()) { "Could not replace Gemma test status" }
        require(temporary.renameTo(target)) { "Could not publish Gemma test status" }
    }

    private companion object {
        const val ACTION_DOWNLOAD = "io.github.anup42.askalbum.test.DOWNLOAD_GEMMA"
        const val ACTION_REPORT = "io.github.anup42.askalbum.test.REPORT_GEMMA"
        const val ACTION_CANCEL = "io.github.anup42.askalbum.test.CANCEL_GEMMA"
        const val EXTRA_TIER = "tier"
        const val EXTRA_OPERATION_ID = "operation_id"
        val OPERATION_ID = Regex("[a-f0-9]{32}")
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-gemma-model").apply { isDaemon = true }
        }
    }
}
