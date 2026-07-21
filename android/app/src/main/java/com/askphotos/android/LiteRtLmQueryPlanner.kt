package com.askphotos.android

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

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
    suspend fun compile(query: String, activeResultIds: Set<String>?): GalleryQueryPlan {
        val status = modelPacks.status()
        val path = status.path ?: return fallback.compile(query, activeResultIds)
        if (status.deviceAssessment?.supported == false) return fallback.compile(query, activeResultIds)
        return try {
            resources.withModel(ModelCapability.GENERATIVE) {
                boundedCompiler.compile(query, activeResultIds, plannerPrompt(query)) { prompt -> generate(path, prompt) }
            }
        } catch (load: GemmaModelLoadFailure) {
            modelPacks.rollbackAfterLoadFailure(path)
            fallback.compile(query, activeResultIds)
        } catch (_: Throwable) {
            fallback.compile(query, activeResultIds)
        }
    }

    private suspend fun generate(path: String, prompt: String): String = withContext(Dispatchers.IO) {
        require(File(path).isFile) { "Verified Gemma artifact is unavailable" }
        val activeEngine = createEngine(path)
        try {
            activeEngine.createConversation().use { conversation ->
                val message = conversation.sendMessage(prompt)
                message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            }
        } finally {
            activeEngine.close()
        }
    }

    private fun createEngine(path: String): Engine {
        val gpu = runCatching {
            Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), maxNumTokens = 1536)).also(Engine::initialize)
        }
        return gpu.getOrElse { gpuFailure ->
            runCatching {
                Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), maxNumTokens = 1536)).also(Engine::initialize)
            }.getOrElse { cpuFailure ->
                throw GemmaModelLoadFailure("Gemma failed on GPU and CPU", cpuFailure.also { it.addSuppressed(gpuFailure) })
            }
        }
    }

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

class GemmaModelLoadFailure(message: String, cause: Throwable) : IllegalStateException(message, cause)
