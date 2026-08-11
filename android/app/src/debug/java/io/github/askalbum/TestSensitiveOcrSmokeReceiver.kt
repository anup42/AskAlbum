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
import org.json.JSONObject

/** Same-UID connected acceptance for the protected OCR boundary. */
class TestSensitiveOcrSmokeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SMOKE_SENSITIVE_OCR) return
        val operationId = intent.getStringExtra(EXTRA_OPERATION_ID)
            ?.takeIf(OPERATION_ID::matches)
            ?: return
        val application = context.applicationContext as AskAlbumApplication
        val reportFile = File(context.filesDir, "test-models/sensitive-ocr-smoke-$operationId.json")
        val startedAt = System.currentTimeMillis()
        val startedElapsed = SystemClock.elapsedRealtime()

        scope.launch {
            try {
                writeReport(reportFile, runningReport(operationId, startedAt))
                writeReport(
                    reportFile,
                    withTimeout(SMOKE_TIMEOUT_MS) {
                        runSmoke(application, operationId, startedAt, startedElapsed)
                    },
                )
            } catch (error: Throwable) {
                writeReport(
                    reportFile,
                    JSONObject()
                        .put("state", "FAILED")
                        .put("operationId", operationId)
                        .put("startedAt", startedAt)
                        .put("completedAt", System.currentTimeMillis())
                        .put("errorType", error::class.java.simpleName.take(MAX_TEXT_LENGTH)),
                )
            }
        }
    }

    private suspend fun runSmoke(
        application: AskAlbumApplication,
        operationId: String,
        startedAt: Long,
        startedElapsed: Long,
    ): JSONObject {
        val databaseName = "debug-sensitive-ocr-$operationId.db"
        val database = GalleryDatabase(application, databaseName)
        try {
            database.seedDemoIfEmpty()
            database.ensureStageRows()
            val item = database.allItems().first { it.kind == MediaKind.IMAGE }
            database.completeIndex(
                id = item.id,
                labels = listOf("screenshot", "wifi", "travel document"),
                description = "Synthetic authentication-bound OCR fixture",
                ocrText = "Wi-Fi password: $SYNTHETIC_SECRET\nFlight number: $FLIGHT_NUMBER",
                faceCount = 0,
                previewPath = item.previewPath,
                blocks = listOf(
                    OcrBlockRecord(
                        text = "Wi-Fi password: $SYNTHETIC_SECRET\nFlight number: $FLIGHT_NUMBER",
                        confidence = .99f,
                        left = .1f,
                        top = .2f,
                        right = .9f,
                        bottom = .4f,
                    ),
                ),
                entities = listOf(
                    OcrEntityRecord(
                        type = OcrEntityType.PASSWORD,
                        rawText = SYNTHETIC_SECRET,
                        normalizedValue = SYNTHETIC_SECRET,
                        label = "Wi-Fi password",
                        confidence = .99f,
                        left = .1f,
                        top = .2f,
                        right = .9f,
                        bottom = .3f,
                        producerVersion = OCR_PRODUCER,
                    ),
                    OcrEntityRecord(
                        type = OcrEntityType.FLIGHT_NUMBER,
                        rawText = FLIGHT_NUMBER,
                        normalizedValue = FLIGHT_NUMBER,
                        label = "Flight number",
                        confidence = .98f,
                        left = .1f,
                        top = .3f,
                        right = .9f,
                        bottom = .4f,
                        producerVersion = OCR_PRODUCER,
                    ),
                ),
                ocrAttempted = true,
                ocrProducerVersion = OCR_PRODUCER,
                visualFeatures = VisualFeatures(0L, .1f, .5f, .9f),
                keyframes = emptyList(),
            )
            val storedPassword = database.ocrEntities(item.id, OcrEntityType.PASSWORD).single()
            check(storedPassword.normalizedValue == SYNTHETIC_SECRET) { "Protected OCR did not round-trip" }
            check(!databaseFiles(application, databaseName).any { it.containsUtf8(SYNTHETIC_SECRET) }) {
                "Protected OCR was present as plaintext at rest"
            }

            val initializationsBefore = application.services.gemmaSessions.initializationCount
            val explicitRepository = repository(
                application,
                database,
                GalleryQueryPlan(
                    originalQuery = EXPLICIT_QUERY,
                    intent = QueryIntent.ANSWER_FACT,
                    mediaScope = MediaScope.IMAGES,
                    ocrClause = OcrClause(requestedField = "password"),
                    verification = VerificationPolicy.NEVER,
                    answerMode = AnswerMode.RESULTS_AND_SUMMARY,
                ),
            )
            val explicit = explicitRepository.search(EXPLICIT_QUERY, setOf(item.id))
            val explicitReveal = verifyLockedAndReveal(explicitRepository, explicit, requireFlight = false)

            val genericRepository = repository(
                application,
                database,
                GalleryQueryPlan(
                    originalQuery = GENERIC_QUERY,
                    intent = QueryIntent.DOCUMENT_QA,
                    mediaScope = MediaScope.IMAGES,
                    ocrClause = OcrClause(),
                    verification = VerificationPolicy.NEVER,
                    answerMode = AnswerMode.RESULTS_AND_SUMMARY,
                ),
            )
            val generic = genericRepository.search(GENERIC_QUERY, setOf(item.id))
            val genericReveal = verifyLockedAndReveal(genericRepository, generic, requireFlight = true)
            val initializationsAfter = application.services.gemmaSessions.initializationCount
            check(initializationsAfter == initializationsBefore) { "Sensitive OCR initialized Gemma before authentication" }

            return JSONObject()
                .put("state", "COMPLETE")
                .put("operationId", operationId)
                .put("startedAt", startedAt)
                .put("completedAt", System.currentTimeMillis())
                .put("elapsedMs", SystemClock.elapsedRealtime() - startedElapsed)
                .put("encryptedAtRest", true)
                .put("publicEvidenceRedacted", true)
                .put("gemmaInitializationsBefore", initializationsBefore)
                .put("gemmaInitializationsAfter", initializationsAfter)
                .put("gemmaInitializationDelta", initializationsAfter - initializationsBefore)
                .put("explicit", explicitReveal)
                .put("genericDocument", genericReveal)
        } finally {
            database.close()
            application.deleteDatabase(databaseName)
            File(application.cacheDir, "$databaseName.lck").delete()
        }
    }

    private fun repository(
        application: AskAlbumApplication,
        database: GalleryDatabase,
        plan: GalleryQueryPlan,
    ) = GalleryRepository(
        context = application,
        database = database,
        planner = FixedPlanCompiler(plan),
        visualVerifier = PassThroughVerifier,
    )

    private fun verifyLockedAndReveal(
        repository: GalleryRepository,
        outcome: SearchOutcome,
        requireFlight: Boolean,
    ): JSONObject {
        check(outcome.answer.requiresAuthentication) { "Sensitive answer was not locked" }
        check(outcome.answer.headline == SensitiveEvidencePolicy.LOCKED_HEADLINE) { "Locked headline was not used" }
        check(outcome.answer.evidenceIds.isEmpty() && outcome.answer.claims.isEmpty()) { "Locked answer exposed citations" }
        check(outcome.answer.exactness == ResultExactness.EXACT) { "Deterministic OCR answer was not exact" }
        check(outcome.publicTexts().none { SYNTHETIC_SECRET in it }) { "Public outcome exposed protected OCR" }
        val publicEvidence = outcome.publicEvidence()
        check(publicEvidence.any { it.sourceField == "document_password" }) { "Password evidence provenance was lost" }
        check(publicEvidence.filter(SensitiveEvidencePolicy::requiresAuthentication).all {
            it.text == SensitiveEvidencePolicy.REDACTED_EVIDENCE_TEXT
        }) { "Protected evidence was not redacted" }
        val token = requireNotNull(outcome.sensitiveAnswerToken) { "Sensitive answer token was absent" }
        check(SYNTHETIC_SECRET !in token) { "Sensitive token included protected OCR" }
        val revealed = requireNotNull(repository.revealSensitiveAnswer(token)) { "Sensitive answer could not be revealed" }
        val revealedText = listOf(revealed.headline, revealed.detail) + revealed.claims.map(GroundedClaim::text)
        check(revealedText.any { SYNTHETIC_SECRET in it }) { "Authenticated answer omitted the password" }
        if (requireFlight) check(revealedText.any { FLIGHT_NUMBER in it }) { "Generic document answer omitted a safe field" }
        check(repository.revealSensitiveAnswer(token) == null) { "Sensitive answer token was reusable" }
        return JSONObject()
            .put("intent", outcome.plan.intent.name)
            .put("exactness", outcome.answer.exactness.name)
            .put("hitCount", outcome.hits.size)
            .put("redactedEvidenceCount", publicEvidence.count {
                it.text == SensitiveEvidencePolicy.REDACTED_EVIDENCE_TEXT
            })
            .put("revealedOnce", true)
            .put("tokenReusable", false)
    }

    private fun SearchOutcome.publicEvidence(): List<EvidenceRecord> =
        hits.flatMap(SearchHit::evidence) +
            channelReports.flatMap { it.hits }.flatMap(SearchHit::evidence) +
            answer.channelReports.flatMap { it.hits }.flatMap(SearchHit::evidence)

    private fun SearchOutcome.publicTexts(): List<String> = buildList {
        add(answer.headline)
        add(answer.detail)
        addAll(answer.warnings)
        addAll(answer.claims.map(GroundedClaim::text))
        addAll(publicEvidence().map(EvidenceRecord::text))
    }

    private fun databaseFiles(context: Context, databaseName: String): List<File> {
        val database = context.getDatabasePath(databaseName)
        return listOf(database, File("${database.path}-wal"), File("${database.path}-shm"))
    }

    private fun File.containsUtf8(value: String): Boolean =
        isFile && readBytes().containsSubsequence(value.toByteArray(Charsets.UTF_8))

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || size < needle.size) return false
        for (start in 0..size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private suspend fun writeReport(file: File, report: JSONObject) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(report.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(temporary.renameTo(file)) { "Could not publish sensitive OCR smoke report" }
    }

    private fun runningReport(operationId: String, startedAt: Long) = JSONObject()
        .put("state", "RUNNING")
        .put("operationId", operationId)
        .put("startedAt", startedAt)

    private class FixedPlanCompiler(private val plan: GalleryQueryPlan) : GalleryPlanCompiler {
        override suspend fun compile(query: String, activeResultIds: Set<String>?): GalleryQueryPlan =
            plan.copy(originalQuery = query, baseResultIds = activeResultIds)
    }

    private data object PassThroughVerifier : CandidateVerifier {
        override suspend fun verifyWhenNeeded(
            plan: GalleryQueryPlan,
            candidates: List<SearchHit>,
        ): VerificationResult = VerificationResult(
            acceptedIds = candidates.mapTo(linkedSetOf()) { it.item.id },
            evidence = emptyList(),
        )
    }

    private companion object {
        const val ACTION_SMOKE_SENSITIVE_OCR = "io.github.anup42.askalbum.test.SMOKE_SENSITIVE_OCR"
        const val EXTRA_OPERATION_ID = "operation_id"
        const val SYNTHETIC_SECRET = "test-wifi-cobalt-7319"
        const val FLIGHT_NUMBER = "AG204"
        const val EXPLICIT_QUERY = "What is the Wi-Fi password in the latest screenshot?"
        const val GENERIC_QUERY = "What details are on this document?"
        const val OCR_PRODUCER = "debug-sensitive-ocr-v1"
        const val SMOKE_TIMEOUT_MS = 90_000L
        const val MAX_TEXT_LENGTH = 120
        val OPERATION_ID = Regex("[a-fA-F0-9]{32}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
