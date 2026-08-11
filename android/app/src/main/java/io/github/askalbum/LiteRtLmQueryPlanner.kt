package io.github.anup42.askalbum

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class PlannerInferenceBackend { GPU, CPU, DETERMINISTIC }

data class PlannerExecutionTrace(
    val plan: GalleryQueryPlan,
    val usedGemma: Boolean,
    val backend: PlannerInferenceBackend,
    val modelTier: GemmaModelTier? = null,
    val modelRevision: String? = null,
    val generationCalls: Int = 0,
    val repaired: Boolean = false,
    val engineLoadMs: Long = 0,
    val generationMs: Long = 0,
    val engineCloseMs: Long = 0,
    val deterministicOverlayApplied: Boolean = false,
    val elapsedMs: Long = 0,
    val fallbackReason: String? = null,
)

/** Executes one constrained Gemma plan call plus at most one schema-repair call. */
class BoundedGemmaPlanCompiler(private val codec: GemmaPlanCodec = GemmaPlanCodec()) {
    suspend fun compile(
        query: String,
        activeResultIds: Set<String>?,
        initialPrompt: String,
        generate: suspend (String) -> String,
    ): GalleryQueryPlan {
        val first = generate(initialPrompt)
        return runCatching { codec.decode(query, first, activeResultIds) }.getOrElse { firstError ->
            val repaired = generate(codec.repairPrompt(query, first, firstError.message ?: "Invalid plan"))
            codec.decode(query, repaired, activeResultIds)
        }
    }
}

/** LiteRT-LM planner with central high-memory leasing, GPU/CPU fallback, bounded repair, and safe deterministic fallback. */
class LiteRtLmQueryPlanner(
    private val modelPacks: ModelPackManager,
    private val sessions: GemmaSessionManager,
    private val fallback: QueryCompiler = QueryCompiler(),
    private val boundedCompiler: BoundedGemmaPlanCompiler = BoundedGemmaPlanCompiler(),
    private val deterministicOverlay: DeterministicPlanOverlay = DeterministicPlanOverlay(),
) {

    suspend fun compile(query: String, activeResultIds: Set<String>?): GalleryQueryPlan =
        compileWithTrace(query, activeResultIds).plan

    suspend fun compileFollowUp(
        query: String,
        context: FollowUpPlanningContext,
    ): GalleryQueryPlan = compileWithTrace(query, context.state.activeResultIds, context).plan

    suspend fun compileWithTrace(
        query: String,
        activeResultIds: Set<String>?,
        followUpContext: FollowUpPlanningContext? = null,
    ): PlannerExecutionTrace {
        val started = android.os.SystemClock.elapsedRealtime()
        val status = modelPacks.status()
        val path = status.path ?: return fallbackTrace(query, activeResultIds, started, "No verified Gemma pack is active")
        if (status.deviceAssessment?.supported == false) {
            return fallbackTrace(query, activeResultIds, started, status.deviceAssessment.reason)
        }
        return try {
            sessions.withEngine(path, status.multimodal, priority = InferencePriority.INTERACTIVE) { initialized ->
                withContext(Dispatchers.IO) {
                    require(File(path).isFile) { "Verified Gemma artifact is unavailable" }
                    var calls = 0
                    var generationMs = 0L
                    val generationStarted = android.os.SystemClock.elapsedRealtime()
                    val compiledPlan = boundedCompiler.compile(query, activeResultIds, plannerPrompt(query, followUpContext)) { prompt ->
                        calls++
                        initialized.engine.generateText(
                            prompt,
                            GemmaGenerationOptions(
                                seed = 17,
                                maximumOutputTokens = GemmaOutputBudget.PLANNER,
                                temperature = 0f,
                                structuredOutput = true,
                            ),
                        )
                    }
                    generationMs = android.os.SystemClock.elapsedRealtime() - generationStarted
                    val overlay = deterministicOverlay.apply(query, requireNotNull(compiledPlan), activeResultIds)
                    PlannerExecutionTrace(
                        plan = overlay.plan,
                        usedGemma = true,
                        backend = initialized.engine.backend,
                        modelTier = status.tier,
                        modelRevision = status.packVersion,
                        generationCalls = calls,
                        repaired = calls > 1,
                        engineLoadMs = initialized.loadMs,
                        generationMs = generationMs,
                        engineCloseMs = 0L,
                        deterministicOverlayApplied = overlay.applied,
                        elapsedMs = android.os.SystemClock.elapsedRealtime() - started,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (load: GemmaModelLoadFailure) {
            modelPacks.rollbackAfterLoadFailure(path)
            fallbackTrace(query, activeResultIds, started, load.message ?: "Gemma load failed")
        } catch (error: Throwable) {
            fallbackTrace(query, activeResultIds, started, error.message ?: error::class.java.simpleName)
        }
    }

    private fun fallbackTrace(
        query: String,
        activeResultIds: Set<String>?,
        started: Long,
        reason: String,
    ): PlannerExecutionTrace {
        val fallbackPlan = fallback.compile(query, activeResultIds)
        val overlay = deterministicOverlay.apply(query, fallbackPlan, activeResultIds)
        return PlannerExecutionTrace(
            plan = overlay.plan,
            usedGemma = false,
            backend = PlannerInferenceBackend.DETERMINISTIC,
            deterministicOverlayApplied = overlay.applied,
            elapsedMs = android.os.SystemClock.elapsedRealtime() - started,
            fallbackReason = reason.take(240),
        )
    }

    private fun plannerPrompt(query: String, followUpContext: FollowUpPlanningContext?) = """
        Compile the personal-gallery request into exactly one JSON object. Return JSON only.
        Never emit SQL, code, file paths, content URIs, tool names, result IDs, or more than the declared bounds.
        Allowed root fields: version,intent,followUp,mediaScope,filter,semanticClauses,peopleClauses,ocrClause,grouping,aggregation,sort,verification,answerMode,limit,terms,place,comparisonScopes.
        Allowed intents: ${CapabilityRegistry.plannerIntentNames()}.
        Allowed mediaScope: ALL,IMAGES,VIDEOS,DOCUMENTS. limit is 1..100; terms and semanticClauses max 16; peopleClauses max 8.
        filter is {"op":"TRUE"}, {"op":"AND","clauses":[]}, {"op":"TIME_RANGE","startEpochMs":null,"endEpochMs":null}, {"op":"MEDIA_KIND","kind":"IMAGE"}, or {"op":"ALBUM","album":"name"}.
        Default to terms and place. For ordinary category, scene, activity, place, event-name, or free-text search, semanticClauses must be [].
        Use semanticClauses only for relational, negative, comparative, or fine-grained visual conditions that terms cannot express.
        A semantic clause has text, optional canonicalText, polarity POSITIVE|NEGATIVE, hardness HARD|SOFT, subject WHOLE_MEDIA|PERSON|EVENT|DOCUMENT, optional relationToPerson. Subject is the evidence carrier, not the search category: put family, pet, trip, food, clothing, and similar concepts in text/canonicalText and use WHOLE_MEDIA.
        For wearing, carrying, holding, using, pose, or person-to-person conditions, use subject PERSON, set relationToPerson to the exact peopleClauses personId, keep the visible item and attributes in text/canonicalText, and require visual verification.
        A people clause has personId, mustBePresent, hardness and means that identity must be visibly present. Do not add one for ownership, authorship, or request-recipient grammar such as "my photos", "my trip", "photos I took", or "show me beach photos". ocrClause has optional query,merchant,requestedField. When requestedField is present, use exactly one allowlisted key: ${OcrFactAllowlist.fields.joinToString(",") { it.key }}. For SUM or MIN_MAX, aggregation.field must be one of the numeric allowlisted keys. For COMPARE, put each requested place or event name in comparisonScopes (maximum 4) and do not reduce the comparison to one place filter.
        verification is exactly one quoted scalar enum string: AUTO, REQUIRED, or NEVER. Never emit an array or object there.
        Set followUp to true only when the current utterance modifies or narrows the active result set described below. Set it to false for a new gallery-wide request, even when a previous result set exists. Never emit or infer result-set IDs.
        For "Same event but videos" or an equivalent media-type refinement, set followUp to true and mediaScope to VIDEOS. The active result set carries event continuity; do not emit event, eventId, eventScope, result IDs, or media IDs.
        Preserve the user's language in text and add a short English canonicalText when useful for retrieval.
        Set verification to REQUIRED only for relational, negative, comparative, or fine-grained visual conditions. Otherwise use AUTO. Do not relax HARD constraints.
        Active conversation context: ${followUpContext?.let(::followUpContextJson) ?: "none"}
        Query: ${JSONObject.quote(query)}
    """.trimIndent()

    private fun followUpContextJson(context: FollowUpPlanningContext): String {
        val previous = context.previousPlan
        return JSONObject().apply {
            put("activeResultSetPresent", context.state.activeResultSetId != null)
            put("activeItemCount", context.state.activeResultIds.size)
            put("lastQueryAvailable", !context.state.lastQuery.isNullOrBlank())
            put("lastQuery", context.state.lastQuery?.take(MAX_CONTEXT_TEXT) ?: JSONObject.NULL)
            put("referencedPeople", JSONArray(context.state.referencedPeople.take(MAX_CONTEXT_ENTITIES)))
            put("referencedEvents", JSONArray(context.state.referencedEvents.take(MAX_CONTEXT_ENTITIES)))
            put("currentGrouping", context.state.grouping.name)
            put("currentPlaceScope", JSONArray(context.state.currentPlaceScope.take(8)))
            context.state.currentTimeScope?.let { range ->
                put("currentTimeScope", JSONObject().apply {
                    put("startEpochMs", range.startEpochMs)
                    put("endEpochMs", range.endEpochMs)
                })
            }
            if (previous == null) {
                put("previousPlan", JSONObject.NULL)
            } else {
                put("previousPlan", JSONObject().apply {
                    put("intent", previous.intent.name)
                    put("mediaScope", previous.mediaScope.name)
                    put("terms", JSONArray(previous.terms.take(16)))
                    put("place", previous.place ?: JSONObject.NULL)
                    put("grouping", previous.grouping.name)
                    put("sort", previous.sort.name)
                    put("people", JSONArray(previous.peopleClauses.take(8).map { it.personId }))
                    put("semanticClauses", JSONArray(previous.semanticClauses.take(8).map { it.text }))
                })
            }
        }.toString()
    }

    private companion object {
        const val MAX_CONTEXT_TEXT = 240
        const val MAX_CONTEXT_ENTITIES = 8
    }
}

class GemmaModelLoadFailure(message: String, cause: Throwable) : IllegalStateException(message, cause)
