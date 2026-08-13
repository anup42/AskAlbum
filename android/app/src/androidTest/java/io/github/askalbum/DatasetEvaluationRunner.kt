package io.github.anup42.askalbum

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Run-scoped evaluation entry point for the isolated evaluation application ID.
 *
 * Relevance IDs and reference answers remain on the host. The target receives only media,
 * capture times, query IDs, and query text, so the retrieval and answer paths cannot read the
 * scoring oracle. One instrumentation invocation evaluates the complete batch to preserve the
 * application-level Gemma session across queries.
 */
internal class DatasetEvaluationRunner(
    private val context: Context,
    private val runId: String,
) {
    fun run(arguments: Bundle) = runBlocking {
        require(BuildConfig.DISTRIBUTION == "evaluation") {
            "Dataset evaluation is restricted to the isolated evaluation variant"
        }
        val operationId = requireNotNull(arguments.getString(ARG_OPERATION_ID))
            .also { require(OPERATION_ID.matches(it)) { "Invalid evaluation operation ID" } }
        val requests = requests(arguments)
        require(requests.isNotEmpty() && requests.size <= MAX_QUERY_COUNT) {
            "Evaluation must contain 1-$MAX_QUERY_COUNT queries"
        }
        val application = context.applicationContext as AskAlbumApplication
        val modelStatus = application.modelPackManager.status()
        require(modelStatus.installed && modelStatus.tier == GemmaModelTier.E2B) {
            "A verified Gemma E2B generation must be active in the evaluation package"
        }
        val (scopeIds, datasetIdsByMediaId) = resolveRunScope(application.repository)
        val outputRoot = File(context.filesDir, "test-seed/$runId/evaluation/$operationId")
        outputRoot.mkdirs()
        val resume = arguments.getString(ARG_RESUME)?.toBooleanStrictOrNull() ?: true
        val startedAt = System.currentTimeMillis()
        val startedElapsed = SystemClock.elapsedRealtime()
        val initializationsBefore = application.services.gemmaSessions.initializationCount
        var completed = 0
        var failed = 0
        var reused = 0

        requests.forEachIndexed { index, request ->
            val target = File(outputRoot, "query-${request.id}.json")
            val existing = target.takeIf(File::isFile)
                ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
                ?.takeIf {
                    resume && it.optString("state") == STATE_COMPLETE &&
                        it.optString("queryId") == request.id && it.optString("query") == request.query
                }
            if (existing != null) {
                reused += 1
                completed += 1
            } else {
                val report = evaluateOne(
                    repository = application.repository,
                    request = request,
                    scopeIds = scopeIds,
                    datasetIdsByMediaId = datasetIdsByMediaId,
                )
                writeJsonAtomic(target, report)
                if (report.optString("state") == STATE_COMPLETE) completed += 1 else failed += 1
            }
            writeJsonAtomic(
                File(outputRoot, "progress.json"),
                JSONObject()
                    .put("state", STATE_RUNNING)
                    .put("runId", runId)
                    .put("operationId", operationId)
                    .put("completed", completed)
                    .put("failed", failed)
                    .put("reused", reused)
                    .put("processed", index + 1)
                    .put("total", requests.size),
            )
        }

        val initializationDelta = application.services.gemmaSessions.initializationCount - initializationsBefore
        val summary = JSONObject()
            .put("state", if (failed == 0) STATE_COMPLETE else STATE_PARTIAL)
            .put("runId", runId)
            .put("operationId", operationId)
            .put("startedAt", startedAt)
            .put("completedAt", System.currentTimeMillis())
            .put("elapsedMs", SystemClock.elapsedRealtime() - startedElapsed)
            .put("queryCount", requests.size)
            .put("completedCount", completed)
            .put("failedCount", failed)
            .put("reusedCount", reused)
            .put("scopeMediaCount", scopeIds.size)
            .put("gemmaTier", modelStatus.tier.name)
            .put("gemmaPackVersion", modelStatus.packVersion ?: JSONObject.NULL)
            .put("gemmaInitializationDelta", initializationDelta)
        writeJsonAtomic(File(outputRoot, "summary.json"), summary)
    }

    private suspend fun evaluateOne(
        repository: GalleryRepository,
        request: QueryRequest,
        scopeIds: Set<String>,
        datasetIdsByMediaId: Map<String, String>,
    ): JSONObject {
        val startedAt = System.currentTimeMillis()
        val startedElapsed = SystemClock.elapsedRealtime()
        return try {
            val timeline = JSONArray()
            val stamps = mutableListOf<ProgressStamp>()
            var previousElapsed = 0L
            var outcome: SearchOutcome? = null
            withTimeout(PER_QUERY_TIMEOUT_MS) {
                repository.searchProgressive(request.query, activeResultIds = scopeIds).collect { progress ->
                    val elapsed = SystemClock.elapsedRealtime() - startedElapsed
                    val event = progressEvent(progress, datasetIdsByMediaId)
                        .put("elapsedMs", elapsed)
                        .put("sincePreviousMs", elapsed - previousElapsed)
                    timeline.put(event)
                    stamps += ProgressStamp(event.getString("stage"), elapsed)
                    previousElapsed = elapsed
                    if (progress is QueryProgress.Completed) outcome = progress.outcome
                }
            }
            val completed = requireNotNull(outcome) { "Search flow completed without an outcome" }
            serializeOutcome(completed, request, datasetIdsByMediaId, timeline, stamps)
                .put("state", STATE_COMPLETE)
                .put("startedAt", startedAt)
                .put("completedAt", System.currentTimeMillis())
                .put("latencyMs", SystemClock.elapsedRealtime() - startedElapsed)
        } catch (error: Throwable) {
            JSONObject()
                .put("state", STATE_FAILED)
                .put("queryId", request.id)
                .put("query", request.query)
                .put("startedAt", startedAt)
                .put("completedAt", System.currentTimeMillis())
                .put("latencyMs", SystemClock.elapsedRealtime() - startedElapsed)
                .put("errorType", error::class.java.simpleName.take(MAX_TEXT))
                .put("error", error.message.safeText())
        }
    }

    private fun progressEvent(
        progress: QueryProgress,
        datasetIdsByMediaId: Map<String, String>,
    ): JSONObject = when (progress) {
        QueryProgress.Understanding -> JSONObject().put("stage", "UNDERSTANDING")
        is QueryProgress.PlanReady -> JSONObject()
            .put("stage", "PLAN_READY")
            .put("output", serializePlan(progress.plan))
        is QueryProgress.InitialResults -> JSONObject()
            .put("stage", "INITIAL_RESULTS")
            .put("output", JSONObject()
                .put("hitCount", progress.hits.size)
                .put("matchedImageIds", JSONArray(progress.hits.map { datasetId(it.item.id, datasetIdsByMediaId) })))
        is QueryProgress.SemanticScan -> JSONObject()
            .put("stage", "SEMANTIC_SCAN")
            .put("output", JSONObject()
                .put("searchedCount", progress.searchedCount)
                .put("eligibleCount", progress.eligibleCount))
        is QueryProgress.Verifying -> JSONObject()
            .put("stage", "VERIFYING")
            .put("output", JSONObject().put("candidateCount", progress.candidateCount))
        QueryProgress.ComposingAnswer -> JSONObject().put("stage", "COMPOSING_ANSWER")
        is QueryProgress.Completed -> JSONObject()
            .put("stage", "COMPLETED")
            .put("output", JSONObject()
                .put("hitCount", progress.outcome.hits.size)
                .put("exactness", progress.outcome.answer.exactness.name))
    }

    private fun serializeOutcome(
        outcome: SearchOutcome,
        request: QueryRequest,
        datasetIdsByMediaId: Map<String, String>,
        timeline: JSONArray,
        stamps: List<ProgressStamp>,
    ): JSONObject {
        val redactEvidence = outcome.answer.requiresAuthentication
        val hits = outcome.hits.map { hit -> serializeHit(hit, datasetIdsByMediaId, redactEvidence) }
        val answerText = listOf(outcome.answer.headline.trim(), outcome.answer.detail.trim())
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .joinToString("\n")
        return JSONObject()
            .put("queryId", request.id)
            .put("query", request.query)
            .put("matchedImageIds", JSONArray(outcome.hits.map { datasetId(it.item.id, datasetIdsByMediaId) }))
            .put("answerText", answerText)
            .put("answer", JSONObject()
                .put("headline", outcome.answer.headline)
                .put("detail", outcome.answer.detail)
                .put("exactness", outcome.answer.exactness.name)
                .put("requiresAuthentication", outcome.answer.requiresAuthentication)
                .put("warnings", JSONArray(outcome.answer.warnings))
                .put("evidenceIds", JSONArray(outcome.answer.evidenceIds))
                .put("indexedEligibleCount", outcome.answer.indexedEligibleCount)
                .put("totalEligibleCount", outcome.answer.totalEligibleCount))
            .put("hits", JSONArray(hits))
            .put("trace", JSONObject()
                .put("plan", serializePlan(outcome.plan))
                .put("timeline", timeline)
                .put("moduleLatencyMs", moduleLatencies(stamps))
                .put("channels", JSONArray(outcome.channelReports.map { report ->
                    JSONObject()
                        .put("channel", report.channel.name)
                        .put("status", report.status.name)
                        .put("eligibleCount", report.eligibleCount)
                        .put("indexedCount", report.indexedCount)
                        .put("searchedCount", report.searchedCount)
                        .put("hitCount", report.hits.size)
                        .put("matchedImageIds", JSONArray(report.hits.map {
                            datasetId(it.item.id, datasetIdsByMediaId)
                        }))
                        .put("modelVersion", report.modelVersion ?: JSONObject.NULL)
                        .put("errorCode", report.errorCode ?: JSONObject.NULL)
                })))
            .put("repositoryElapsedMs", outcome.elapsedMs)
            .put("resultSetId", outcome.resultSetId ?: JSONObject.NULL)
    }

    private fun serializePlan(plan: GalleryQueryPlan): JSONObject = JSONObject()
        .put("intent", plan.intent.name)
        .put("mediaScope", plan.mediaScope.name)
        .put("terms", JSONArray(plan.terms))
        .put("semanticClauses", JSONArray(plan.semanticClauses.map { clause ->
            JSONObject()
                .put("text", clause.text)
                .put("canonicalText", clause.canonicalText ?: JSONObject.NULL)
                .put("polarity", clause.polarity.name)
        }))
        .put("peopleClauses", JSONArray(plan.peopleClauses.map { clause ->
            JSONObject()
                .put("personId", clause.personId)
                .put("mustBePresent", clause.mustBePresent)
        }))
        .put("place", plan.place ?: JSONObject.NULL)
        .put("filter", plan.filter.toString())
        .put("sort", plan.sort.toString())
        .put("grouping", plan.grouping.toString())
        .put("baseResultCount", plan.baseResultIds?.size ?: 0)

    private fun serializeHit(
        hit: SearchHit,
        datasetIdsByMediaId: Map<String, String>,
        redactEvidence: Boolean,
    ): JSONObject = JSONObject()
        .put("imageId", datasetId(hit.item.id, datasetIdsByMediaId))
        .put("mediaId", hit.item.id)
        .put("filename", hit.item.filename)
        .put("score", hit.score)
        .put("duplicateImageIds", JSONArray(hit.duplicateIds.map {
            datasetId(it, datasetIdsByMediaId)
        }))
        .put("evidence", JSONArray(hit.evidence.map { evidence ->
            JSONObject()
                .put("id", evidence.id)
                .put("sourceField", evidence.sourceField)
                .put("text", if (redactEvidence) REDACTED else evidence.text.take(MAX_EVIDENCE_TEXT))
                .put("confidence", evidence.confidence.toDouble())
                .put("producerVersion", evidence.producerVersion)
                .put("timestampMs", evidence.timestampMs ?: JSONObject.NULL)
                .put("pageIndex", evidence.pageIndex ?: JSONObject.NULL)
                .put("scope", evidence.scope?.name ?: JSONObject.NULL)
                .put("scopeId", evidence.scopeId ?: JSONObject.NULL)
                .put("clusterId", evidence.clusterId ?: JSONObject.NULL)
                .put("applicability", evidence.applicability ?: JSONObject.NULL)
        }))

    private fun moduleLatencies(stamps: List<ProgressStamp>): JSONObject {
        fun first(stage: String): Long? = stamps.firstOrNull { it.stage == stage }?.elapsedMs
        val plan = first("PLAN_READY")
        val initial = first("INITIAL_RESULTS")
        val verifying = first("VERIFYING")
        val composing = first("COMPOSING_ANSWER")
        val complete = first("COMPLETED")
        val semanticEnd = verifying ?: composing ?: complete
        return JSONObject()
            .put("planner", plan ?: JSONObject.NULL)
            .put("initialRetrieval", difference(initial, plan))
            .put("semanticRetrievalAndFusion", difference(semanticEnd, initial))
            .put("visualVerification", difference(composing, verifying))
            .put("answerComposition", difference(complete, composing))
    }

    private fun difference(end: Long?, start: Long?): Any =
        if (end == null || start == null) JSONObject.NULL else (end - start).coerceAtLeast(0L)

    private fun resolveRunScope(repository: GalleryRepository): Pair<Set<String>, Map<String, String>> {
        val seed = JSONObject(File(context.filesDir, "test-seed/$runId/seed-result.json").readText())
        val uris = seed.getJSONArray("createdUris").let { values ->
            (0 until values.length()).mapTo(mutableSetOf()) { values.getString(it) }
        }
        val runItems = repository.allItems().filter { it.contentUri in uris }
        require(runItems.size == uris.size) { "Only ${runItems.size}/${uris.size} seeded media rows resolved" }
        val mapping = runItems.associate { item ->
            item.id to item.filename.substringBeforeLast('.', item.filename)
        }
        return mapping.keys to mapping
    }

    private fun requests(arguments: Bundle): List<QueryRequest> {
        val encodedQuery = arguments.getString(ARG_QUERY_BASE64)
        if (!encodedQuery.isNullOrBlank()) {
            val id = requireNotNull(arguments.getString(ARG_QUERY_ID))
                .also { require(QUERY_ID.matches(it)) { "Invalid query ID" } }
            val query = String(Base64.decode(encodedQuery, Base64.DEFAULT), Charsets.UTF_8)
                .trim().also { require(it.isNotEmpty() && it.length <= MAX_QUERY_LENGTH) { "Invalid query" } }
            return listOf(QueryRequest(id, query))
        }
        val encodedQueries = requireNotNull(arguments.getString(ARG_QUERIES_BASE64)) {
            "Compressed evaluation queries were not supplied"
        }
        val decoded = Base64.decode(encodedQueries, Base64.DEFAULT)
        val queryJson = GZIPInputStream(ByteArrayInputStream(decoded)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val values = JSONArray(queryJson)
        return (0 until values.length()).map { index ->
            val value = values.getJSONObject(index)
            val id = value.getString("id").also { require(QUERY_ID.matches(it)) { "Invalid query ID" } }
            val query = value.getString("query").trim()
                .also { require(it.isNotEmpty() && it.length <= MAX_QUERY_LENGTH) { "Invalid query" } }
            QueryRequest(id, query)
        }.also { requests -> require(requests.map(QueryRequest::id).distinct().size == requests.size) }
    }

    private fun datasetId(mediaId: String, mapping: Map<String, String>): String = mapping[mediaId] ?: mediaId

    private fun writeJsonAtomic(target: File, value: JSONObject) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(value.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (target.exists()) require(target.delete()) { "Could not replace ${target.name}" }
        require(temporary.renameTo(target)) { "Could not commit ${target.name}" }
    }

    private fun String?.safeText(): String = this
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(MAX_TEXT)
        ?: "Unknown evaluation failure"

    private data class QueryRequest(val id: String, val query: String)
    private data class ProgressStamp(val stage: String, val elapsedMs: Long)

    private companion object {
        const val ARG_OPERATION_ID = "galleryOperationId"
        const val ARG_QUERY_BASE64 = "galleryQueryBase64"
        const val ARG_QUERIES_BASE64 = "galleryQueriesBase64"
        const val ARG_QUERY_ID = "galleryQueryId"
        const val ARG_RESUME = "galleryResume"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_COMPLETE = "COMPLETE"
        const val STATE_PARTIAL = "PARTIAL"
        const val STATE_FAILED = "FAILED"
        const val REDACTED = "[REDACTED_AUTH_REQUIRED]"
        const val MAX_QUERY_COUNT = 1_000
        const val MAX_QUERY_LENGTH = 2_000
        const val MAX_TEXT = 1_000
        const val MAX_EVIDENCE_TEXT = 2_000
        const val PER_QUERY_TIMEOUT_MS = 10 * 60_000L
        val OPERATION_ID = Regex("[a-f0-9]{32}")
        val QUERY_ID = Regex("[A-Za-z0-9_-]{1,96}")
    }
}
