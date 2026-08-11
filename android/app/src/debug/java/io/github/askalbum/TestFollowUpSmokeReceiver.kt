package io.github.anup42.askalbum

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Same-UID real-E2B follow-up gate over synthetic result IDs; no gallery database is opened. */
class TestFollowUpSmokeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SMOKE_FOLLOW_UP) return
        val operationId = intent.getStringExtra(EXTRA_OPERATION_ID)
            ?.takeIf(OPERATION_ID::matches)
            ?: return
        val application = context.applicationContext as AskAlbumApplication
        val reportFile = File(context.filesDir, "test-models/follow-up-smoke-$operationId.json")
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
                        .put("errorType", error::class.java.simpleName.take(MAX_REPORT_TEXT))
                        .put("error", error.message.safeReportText()),
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
        val status = application.modelPackManager.status()
        check(status.installed) { "No verified Gemma pack is active" }
        check(status.tier == GemmaModelTier.E2B) { "The active default pack is not E2B" }
        val modelPath = requireNotNull(status.path) { "The verified Gemma artifact path is absent" }
        val sessions = application.services.gemmaSessions
        val initializationsBefore = sessions.initializationCount
        val planner = LiteRtLmQueryPlanner(application.modelPackManager, sessions)
        val validator = GalleryQueryPlanValidator()
        val resolver = ResultSetPlanPatchResolver(validator)
        val activeIds = linkedSetOf("synthetic_follow_up_media_1", "synthetic_follow_up_media_2")

        val initialTrace = planner.compileWithTrace(INITIAL_QUERY, activeResultIds = null)
        check(initialTrace.usedGemma) { initialTrace.fallbackReason ?: "Initial query did not use Gemma" }
        check(initialTrace.modelTier == GemmaModelTier.E2B) { "Initial query did not use E2B" }
        check(initialTrace.plan.baseResultIds == null) { "Initial query was incorrectly scoped as a follow-up" }
        check(validator.validate(initialTrace.plan).isValid) { "Initial plan failed validation" }
        check("singapore" in searchableText(initialTrace.plan)) { "Initial plan lost the Singapore scope" }

        var previousPlan = initialTrace.plan
        var state = ConversationSearchState(
            sessionId = "debug_follow_up",
            activeResultSetId = resultSetId(operationId, 0),
            activeResultIds = activeIds,
            lastQuery = INITIAL_QUERY,
            referencedPeople = previousPlan.peopleClauses.mapTo(linkedSetOf(), PersonClause::personId),
            currentTimeScope = firstTimeRange(previousPlan.filter),
            currentPlaceScope = setOfNotNull(previousPlan.place),
            grouping = previousPlan.grouping,
        )
        val turns = JSONArray()
        val traces = mutableListOf(initialTrace)

        FOLLOW_UP_CASES.forEachIndexed { index, case ->
            val trace = planner.compileWithTrace(
                query = case.query,
                activeResultIds = state.activeResultIds,
                followUpContext = FollowUpPlanningContext(state, previousPlan),
            )
            traces += trace
            if (trace.usedGemma) {
                check(trace.modelTier == GemmaModelTier.E2B) { "${case.id}: planner did not use E2B" }
            } else {
                check(trace.backend == PlannerInferenceBackend.DETERMINISTIC) {
                    "${case.id}: failed model planning did not use the deterministic fallback"
                }
                check(!trace.fallbackReason.isNullOrBlank()) { "${case.id}: fallback omitted its reason" }
            }
            check(trace.plan.baseResultIds == state.activeResultIds) { "${case.id}: active result scope was lost" }
            check(validator.validate(trace.plan, state.activeResultIds).isValid) { "${case.id}: plan failed validation" }

            val (patch, resolved) = resolver.createAndApply(trace.plan, state, previousPlan)
            val matchingOperation = patch.operations.firstOrNull { operation ->
                operation.field in case.expectedFields && operation.type in case.expectedTypes
            }
            check(matchingOperation != null) {
                "${case.id}: expected ${case.expectedTypes} on ${case.expectedFields}, received ${patch.operations}"
            }
            check(resolved.baseResultIds == activeIds) { "${case.id}: patch escaped the app-owned scope" }
            validateTurn(case.id, resolved)

            turns.put(JSONObject()
                .put("id", case.id)
                .put("query", case.query)
                .put("usedGemma", trace.usedGemma)
                .put("backend", trace.backend.name)
                .put("generationCalls", trace.generationCalls)
                .put("repaired", trace.repaired)
                .put("fallbackReason", trace.fallbackReason ?: JSONObject.NULL)
                .put("generationMs", trace.generationMs)
                .put("baseResultSetId", patch.baseResultSetId)
                .put("matchedField", matchingOperation.field.name)
                .put("matchedOperation", matchingOperation.type.name)
                .put("operations", JSONArray(patch.operations.map { "${it.type}:${it.field}" }))
                .put("searchableText", searchableText(resolved).take(MAX_REPORT_TEXT)))

            previousPlan = resolved
            state = state.copy(
                activeResultSetId = resultSetId(operationId, index + 1),
                lastQuery = case.query,
                referencedPeople = resolved.peopleClauses.mapTo(linkedSetOf(), PersonClause::personId),
                currentTimeScope = firstTimeRange(resolved.filter),
                currentPlaceScope = setOfNotNull(resolved.place),
                grouping = resolved.grouping,
            )
        }

        val runtime = sessions.withEngine(
            modelPath = modelPath,
            multimodal = status.multimodal,
            priority = InferencePriority.INTERACTIVE,
        ) { lease ->
            RuntimeEvidence(lease.engine.backend, lease.engine.mtpSupported, lease.engine.mtpEnabled)
        }
        check(traces.filter(PlannerExecutionTrace::usedGemma).all { it.backend == runtime.backend }) {
            "Model-backed planner turns used inconsistent backends"
        }
        val modelBackedFollowUpCount = traces.drop(1).count(PlannerExecutionTrace::usedGemma)
        val fallbackFollowUpCount = FOLLOW_UP_CASES.size - modelBackedFollowUpCount
        check(modelBackedFollowUpCount > 0) { "No follow-up turn completed with real Gemma" }
        val initializationDelta = sessions.initializationCount - initializationsBefore
        check(initializationDelta in 0..1) { "Follow-up chain initialized Gemma $initializationDelta times" }

        return JSONObject()
            .put("state", "COMPLETE")
            .put("operationId", operationId)
            .put("startedAt", startedAt)
            .put("completedAt", System.currentTimeMillis())
            .put("elapsedMs", SystemClock.elapsedRealtime() - startedElapsed)
            .put("activeTier", status.tier.name)
            .put("packVersion", status.packVersion ?: JSONObject.NULL)
            .put("backend", runtime.backend.name)
            .put("mtpSupported", runtime.mtpSupported)
            .put("mtpEnabled", runtime.mtpEnabled)
            .put("initializationDelta", initializationDelta)
            .put("initialQuery", INITIAL_QUERY)
            .put("initialSearchableText", searchableText(initialTrace.plan).take(MAX_REPORT_TEXT))
            .put("turnCount", FOLLOW_UP_CASES.size)
            .put("modelBackedFollowUpCount", modelBackedFollowUpCount)
            .put("fallbackFollowUpCount", fallbackFollowUpCount)
            .put("turns", turns)
    }

    private fun validateTurn(id: String, plan: GalleryQueryPlan) {
        when (id) {
            "marina_bay" -> {
                val text = searchableText(plan)
                check("marina" in text && "bay" in text) { "Marina Bay refinement was not searchable" }
            }
            "with_dad" -> check(plan.peopleClauses.any { clause ->
                val person = clause.personId.lowercase(Locale.ROOT)
                clause.mustBePresent && ("dad" in person || "father" in person)
            }) { "Dad was not represented as a required person" }
            "close_ups" -> check("close-up" in searchableText(plan)) {
                "Close-ups did not retain a searchable composition predicate"
            }
            "year_2024" -> check(firstTimeRange(plan.filter) == calendarYear(2024)) { "2024 was not an exact calendar range" }
            "exclude_screenshots" -> check(plan.semanticClauses.any { clause ->
                clause.polarity == Polarity.NEGATIVE && "screenshot" in clause.text.lowercase(Locale.ROOT)
            }) { "Screenshot exclusion was not a negative predicate" }
            "videos" -> check(plan.mediaScope == MediaScope.VIDEOS) { "Video refinement did not select videos" }
        }
    }

    private fun searchableText(plan: GalleryQueryPlan): String = buildList {
        addAll(plan.terms)
        plan.place?.let(::add)
        addAll(plan.semanticClauses.flatMap { listOfNotNull(it.text, it.canonicalText) })
        addAll(plan.peopleClauses.map(PersonClause::personId))
    }.joinToString(" ").lowercase(Locale.ROOT)

    private fun firstTimeRange(filter: FilterExpression): FilterExpression.TimeRange? = when (filter) {
        is FilterExpression.TimeRange -> filter
        is FilterExpression.And -> filter.clauses.firstNotNullOfOrNull(::firstTimeRange)
        else -> null
    }

    private fun calendarYear(year: Int): FilterExpression.TimeRange {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return FilterExpression.TimeRange(start, end)
    }

    private fun resultSetId(operationId: String, index: Int): String =
        "rs_followup_${operationId.take(12)}_$index"

    private suspend fun writeReport(file: File, report: JSONObject) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(report.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (file.exists()) check(file.delete()) { "Could not replace the prior follow-up report" }
        check(temporary.renameTo(file)) { "Could not commit the follow-up report" }
    }

    private fun String?.safeReportText(): String = this
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(MAX_REPORT_TEXT)
        ?: "Unknown follow-up smoke failure"

    private data class FollowUpCase(
        val id: String,
        val query: String,
        val expectedFields: Set<PlanPatchField>,
        val expectedTypes: Set<PlanPatchOperationType>,
    )

    private data class RuntimeEvidence(
        val backend: PlannerInferenceBackend,
        val mtpSupported: Boolean,
        val mtpEnabled: Boolean,
    )

    private companion object {
        const val ACTION_SMOKE_FOLLOW_UP = "io.github.anup42.askalbum.test.SMOKE_FOLLOW_UP"
        const val EXTRA_OPERATION_ID = "operation_id"
        const val INITIAL_QUERY = "Show my Singapore trip."
        const val SMOKE_TIMEOUT_MS = 8 * 60_000L
        const val MAX_REPORT_TEXT = 500
        val OPERATION_ID = Regex("[a-fA-F0-9]{32}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val FOLLOW_UP_CASES = listOf(
            FollowUpCase(
                "marina_bay",
                "Only Marina Bay.",
                setOf(PlanPatchField.PLACE, PlanPatchField.SEMANTIC_CLAUSES),
                setOf(PlanPatchOperationType.ADD, PlanPatchOperationType.REPLACE),
            ),
            FollowUpCase(
                "with_dad",
                "Only with Dad.",
                setOf(PlanPatchField.PEOPLE),
                setOf(PlanPatchOperationType.ADD, PlanPatchOperationType.REPLACE),
            ),
            FollowUpCase(
                "close_ups",
                "Show close-ups.",
                setOf(PlanPatchField.SEMANTIC_CLAUSES),
                setOf(PlanPatchOperationType.ADD, PlanPatchOperationType.REPLACE),
            ),
            FollowUpCase(
                "year_2024",
                "Now 2024.",
                setOf(PlanPatchField.TIME),
                setOf(PlanPatchOperationType.ADD, PlanPatchOperationType.REPLACE),
            ),
            FollowUpCase(
                "exclude_screenshots",
                "Exclude screenshots.",
                setOf(PlanPatchField.SEMANTIC_CLAUSES),
                setOf(PlanPatchOperationType.ADD, PlanPatchOperationType.REPLACE),
            ),
            FollowUpCase(
                "videos",
                "Same event but videos.",
                setOf(PlanPatchField.MEDIA_KIND),
                setOf(PlanPatchOperationType.ADD, PlanPatchOperationType.REPLACE),
            ),
        )
    }
}
