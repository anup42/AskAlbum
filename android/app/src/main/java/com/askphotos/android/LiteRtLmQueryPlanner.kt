package com.askphotos.android

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val resources: InferenceResourceManager = SerializedInferenceResourceManager(),
    private val fallback: QueryCompiler = QueryCompiler(),
    private val boundedCompiler: BoundedGemmaPlanCompiler = BoundedGemmaPlanCompiler(),
) {
    suspend fun compile(query: String, activeResultIds: Set<String>?): GalleryQueryPlan =
        compileWithTrace(query, activeResultIds).plan

    suspend fun compileWithTrace(query: String, activeResultIds: Set<String>?): PlannerExecutionTrace {
        val started = android.os.SystemClock.elapsedRealtime()
        val status = modelPacks.status()
        val path = status.path ?: return fallbackTrace(query, activeResultIds, started, "No verified Gemma pack is active")
        if (status.deviceAssessment?.supported == false) {
            return fallbackTrace(query, activeResultIds, started, status.deviceAssessment.reason)
        }
        return try {
            resources.withModel(ModelCapability.GENERATIVE) {
                withContext(Dispatchers.IO) {
                    require(File(path).isFile) { "Verified Gemma artifact is unavailable" }
                    val initialized = createEngine(path)
                    var calls = 0
                    try {
                        val plan = boundedCompiler.compile(query, activeResultIds, plannerPrompt(query)) { prompt ->
                            calls++
                            generate(initialized.engine, prompt)
                        }
                        PlannerExecutionTrace(
                            plan = plan,
                            usedGemma = true,
                            backend = initialized.backend,
                            modelTier = status.tier,
                            modelRevision = status.packVersion,
                            generationCalls = calls,
                            repaired = calls > 1,
                            elapsedMs = android.os.SystemClock.elapsedRealtime() - started,
                        )
                    } finally {
                        initialized.engine.close()
                    }
                }
            }
        } catch (load: GemmaModelLoadFailure) {
            modelPacks.rollbackAfterLoadFailure(path)
            fallbackTrace(query, activeResultIds, started, load.message ?: "Gemma load failed")
        } catch (error: Throwable) {
            fallbackTrace(query, activeResultIds, started, error.message ?: error::class.java.simpleName)
        }
    }

    private fun generate(engine: Engine, prompt: String): String {
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 17),
            extraContext = mapOf("enable_thinking" to false),
        )
        return engine.createConversation(config).use { conversation ->
            val message = conversation.sendMessage(prompt, extraContext = mapOf("enable_thinking" to false))
            message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        }
    }

    private fun createEngine(path: String): InitializedPlannerEngine {
        val gpu = runCatching {
            initializeEngine(EngineConfig(modelPath = path, backend = Backend.GPU(), maxNumTokens = 4096))
                .let { InitializedPlannerEngine(it, PlannerInferenceBackend.GPU) }
        }
        return gpu.getOrElse { gpuFailure ->
            runCatching {
                initializeEngine(EngineConfig(modelPath = path, backend = Backend.CPU(), maxNumTokens = 4096))
                    .let { InitializedPlannerEngine(it, PlannerInferenceBackend.CPU) }
            }.getOrElse { cpuFailure ->
                throw GemmaModelLoadFailure("Gemma failed on GPU and CPU", cpuFailure.also { it.addSuppressed(gpuFailure) })
            }
        }
    }

    private fun initializeEngine(config: EngineConfig): Engine {
        val engine = Engine(config)
        return try {
            engine.initialize()
            engine
        } catch (error: Throwable) {
            runCatching { engine.close() }
            throw error
        }
    }

    private fun fallbackTrace(
        query: String,
        activeResultIds: Set<String>?,
        started: Long,
        reason: String,
    ) = PlannerExecutionTrace(
        plan = fallback.compile(query, activeResultIds),
        usedGemma = false,
        backend = PlannerInferenceBackend.DETERMINISTIC,
        elapsedMs = android.os.SystemClock.elapsedRealtime() - started,
        fallbackReason = reason.take(240),
    )

    private fun plannerPrompt(query: String) = """
        Compile the personal-gallery request into exactly one JSON object. Return JSON only.
        Never emit SQL, code, file paths, content URIs, tool names, result IDs, or more than the declared bounds.
        Allowed root fields: version,intent,mediaScope,filter,semanticClauses,peopleClauses,ocrClause,grouping,aggregation,sort,verification,answerMode,limit,terms,place.
        Allowed intents: FIND_MEDIA,ANSWER_FACT,LIST,COUNT,SUM,MIN_MAX,COMPARE,TIMELINE,EVENT_SUMMARY,DOCUMENT_QA.
        Allowed mediaScope: ALL,IMAGES,VIDEOS,DOCUMENTS. limit is 1..100; terms and semanticClauses max 16; peopleClauses max 8.
        filter is {"op":"TRUE"}, {"op":"AND","clauses":[]}, {"op":"TIME_RANGE","startEpochMs":null,"endEpochMs":null}, {"op":"MEDIA_KIND","kind":"IMAGE"}, or {"op":"ALBUM","album":"name"}.
        A semantic clause has text, optional canonicalText, polarity POSITIVE|NEGATIVE, hardness HARD|SOFT, subject WHOLE_MEDIA|PERSON|EVENT|DOCUMENT, optional relationToPerson.
        A people clause has personId, mustBePresent, hardness. ocrClause has optional query,merchant,requestedField.
        Preserve the user's language in text and add a short English canonicalText when useful for retrieval.
        Mark relational, negative, comparative, or fine-grained visual conditions as verification REQUIRED. Do not relax HARD constraints.
        Query: ${JSONObject.quote(query)}
    """.trimIndent()
}

private data class InitializedPlannerEngine(val engine: Engine, val backend: PlannerInferenceBackend)

class GemmaModelLoadFailure(message: String, cause: Throwable) : IllegalStateException(message, cause)
