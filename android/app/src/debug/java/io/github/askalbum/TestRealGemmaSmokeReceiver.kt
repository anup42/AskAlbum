package io.github.anup42.askalbum

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Same-UID debug hook for real-model acceptance without consumer instrumentation.
 *
 * The receiver uses synthetic, non-sensitive evidence and writes only a bounded private report.
 * It never changes model selection, gallery rows, indexes, People data, or consent.
 */
class TestRealGemmaSmokeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SMOKE_REAL_GEMMA) return
        val operationId = intent.getStringExtra(EXTRA_OPERATION_ID)
            ?.takeIf(OPERATION_ID::matches)
            ?: return
        val pending = goAsync()
        val application = context.applicationContext as AskAlbumApplication
        val reportFile = File(context.filesDir, "test-models/real-gemma-smoke-$operationId.json")
        val startedAt = System.currentTimeMillis()
        val startedElapsed = SystemClock.elapsedRealtime()

        scope.launch {
            try {
                writeReport(
                    reportFile,
                    JSONObject()
                        .put("state", "RUNNING")
                        .put("operationId", operationId)
                        .put("startedAt", startedAt),
                )
                val report = withTimeout(SMOKE_TIMEOUT_MS) {
                    runSmoke(application, operationId, startedAt, startedElapsed)
                }
                writeReport(reportFile, report)
            } catch (error: Throwable) {
                writeReport(
                    reportFile,
                    JSONObject()
                        .put("state", "FAILED")
                        .put("operationId", operationId)
                        .put("startedAt", startedAt)
                        .put("completedAt", System.currentTimeMillis())
                        .put("errorType", error::class.java.simpleName.take(MAX_TEXT_LENGTH))
                        .put("error", error.message.safeReportText()),
                )
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun runSmoke(
        application: AskAlbumApplication,
        operationId: String,
        startedAt: Long,
        startedElapsed: Long,
    ): JSONObject {
        val status = application.modelPackManager.status()
        check(status.installed) { "No verified Gemma pack is active" }
        check(status.tier == GemmaModelTier.E2B) { "The active default pack is not E2B" }
        val modelPath = requireNotNull(status.path) { "The verified Gemma artifact path is absent" }
        val sessions = application.services.gemmaSessions
        val initializationsBefore = sessions.initializationCount
        val plannerTrace = LiteRtLmQueryPlanner(application.modelPackManager, sessions)
            .compileWithTrace(SMOKE_QUERY, activeResultIds = null)
        val validation = GalleryQueryPlanValidator().validate(plannerTrace.plan)
        check(plannerTrace.usedGemma) { plannerTrace.fallbackReason ?: "Planner did not use Gemma" }
        check(validation.isValid) { "Planner returned an invalid plan: ${validation.errors.joinToString()}" }
        check(plannerTrace.modelTier == GemmaModelTier.E2B) { "Planner did not use E2B" }

        val runtime = sessions.withEngine(
            modelPath = modelPath,
            multimodal = status.multimodal,
            priority = InferencePriority.INTERACTIVE,
        ) { lease ->
            RuntimeEvidence(
                backend = lease.engine.backend,
                mtpSupported = lease.engine.mtpSupported,
                mtpEnabled = lease.engine.mtpEnabled,
            )
        }
        check(runtime.backend == plannerTrace.backend) { "Planner and shared session reported different backends" }

        val item = GalleryItem(
            id = SMOKE_MEDIA_ID,
            filename = "real-gemma-smoke.jpg",
            title = "Synthetic beach sunset",
            creator = "AskAlbum debug acceptance",
            location = "Synthetic fixture",
            latitude = null,
            longitude = null,
            tags = listOf("beach", "sunset"),
            description = "Synthetic non-sensitive evidence",
            license = "CC0-1.0",
            sourceUrl = "local-synthetic-fixture",
            assetPath = null,
        )
        val evidence = EvidenceRecord(
            SMOKE_EVIDENCE_ID,
            item.id,
            "debug_real_gemma_smoke",
            "A beach sunset is visible in the synthetic evidence.",
            0.99f,
            "askalbum-debug-smoke",
            scope = SemanticFactScope.QUERY_VERIFICATION,
            scopeId = item.id,
            evidenceMediaId = item.id,
        )
        val baseline = SearchAnswer(
            headline = "Found 1 likely match",
            detail = "One synthetic candidate is supported by local evidence.",
            evidenceIds = listOf(evidence.id),
            exactness = ResultExactness.ESTIMATED_FROM_RETRIEVAL,
            indexedEligibleCount = 1,
            totalEligibleCount = 1,
        )
        val composition = application.services.groundedAnswerComposer.compose(
            GroundedAnswerInput(
                plan = plannerTrace.plan,
                hits = listOf(SearchHit(item, 1.0, listOf(evidence))),
                deterministicAnswer = baseline,
            ),
        )
        check(composition.trace.usedGemma) {
            composition.trace.fallbackReason ?: "Grounded composer did not use Gemma"
        }
        check(composition.trace.modelTier == GemmaModelTier.E2B) { "Grounded composer did not use E2B" }
        check(composition.answer.evidenceIds.isNotEmpty()) { "Grounded answer omitted evidence" }
        check(composition.answer.evidenceIds.all { it == evidence.id }) { "Grounded answer invented evidence" }
        check(composition.answer.claims.all { claim ->
            claim.evidenceIds.isNotEmpty() && claim.evidenceIds.all { it == evidence.id }
        }) { "A grounded claim lacks the synthetic evidence" }

        val initializationsAfter = sessions.initializationCount
        val initializationDelta = initializationsAfter - initializationsBefore
        check(initializationDelta in 0..1) {
            "Planner and composer initialized Gemma $initializationDelta times"
        }

        return JSONObject()
            .put("state", "COMPLETE")
            .put("operationId", operationId)
            .put("startedAt", startedAt)
            .put("completedAt", System.currentTimeMillis())
            .put("elapsedMs", SystemClock.elapsedRealtime() - startedElapsed)
            .put("activeTier", status.tier.name)
            .put("selectedTier", status.selectedTier.name)
            .put("packVersion", status.packVersion ?: JSONObject.NULL)
            .put("modelSha256", status.sha256 ?: JSONObject.NULL)
            .put("multimodal", status.multimodal)
            .put("backend", runtime.backend.name)
            .put("mtpSupported", runtime.mtpSupported)
            .put("mtpEnabled", runtime.mtpEnabled)
            .put("initializationsBefore", initializationsBefore)
            .put("initializationsAfter", initializationsAfter)
            .put("initializationDelta", initializationDelta)
            .put("planner", JSONObject()
                .put("usedGemma", plannerTrace.usedGemma)
                .put("generationCalls", plannerTrace.generationCalls)
                .put("repaired", plannerTrace.repaired)
                .put("loadMs", plannerTrace.engineLoadMs)
                .put("generationMs", plannerTrace.generationMs)
                .put("intent", plannerTrace.plan.intent.name)
                .put("validationErrors", JSONArray(validation.errors)))
            .put("composer", JSONObject()
                .put("usedGemma", composition.trace.usedGemma)
                .put("generationCalls", composition.trace.generationCalls)
                .put("repaired", composition.trace.repaired)
                .put("loadMs", composition.trace.engineLoadMs)
                .put("generationMs", composition.trace.generationMs)
                .put("claimCount", composition.answer.claims.size)
                .put("evidenceIds", JSONArray(composition.answer.evidenceIds)))
    }

    private suspend fun writeReport(file: File, report: JSONObject) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(report.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (file.exists()) check(file.delete()) { "Could not replace the prior smoke report" }
        check(temporary.renameTo(file)) { "Could not commit the smoke report" }
    }

    private data class RuntimeEvidence(
        val backend: PlannerInferenceBackend,
        val mtpSupported: Boolean,
        val mtpEnabled: Boolean,
    )

    private fun String?.safeReportText(): String = this
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(MAX_TEXT_LENGTH)
        ?: "Unknown real-model smoke failure"

    private companion object {
        const val ACTION_SMOKE_REAL_GEMMA = "io.github.anup42.askalbum.test.SMOKE_REAL_GEMMA"
        const val EXTRA_OPERATION_ID = "operation_id"
        const val SMOKE_TIMEOUT_MS = 12 * 60_000L
        const val MAX_TEXT_LENGTH = 500
        const val SMOKE_QUERY = "Show beach sunset photos."
        const val SMOKE_MEDIA_ID = "debug_real_gemma_smoke_media"
        const val SMOKE_EVIDENCE_ID = "DEBUG_REAL_GEMMA_SMOKE_EVIDENCE"
        val OPERATION_ID = Regex("[a-fA-F0-9]{32}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
