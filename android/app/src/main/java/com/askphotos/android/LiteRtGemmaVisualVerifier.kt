package com.askphotos.android

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Bounded one-image-at-a-time Gemma verification. It never accepts a candidate after an inference failure. */
class LiteRtGemmaVisualVerifier(
    context: Context,
    private val modelPacks: ModelPackManager,
    private val resources: InferenceResourceManager,
    private val imageLoader: GalleryImageLoader = GalleryImageLoader(context.applicationContext),
    private val compiler: BoundedGemmaVerificationCompiler = BoundedGemmaVerificationCompiler(),
) : CandidateVerifier {
    override suspend fun verifyWhenNeeded(plan: GalleryQueryPlan, candidates: List<SearchHit>): VerificationResult {
        if (!VisualVerificationPolicy.requiresVerification(plan)) {
            return VerificationResult(candidates.mapTo(linkedSetOf()) { it.item.id }, emptyList())
        }
        val started = SystemClock.elapsedRealtime()
        val bounded = candidates.filter { it.item.kind == MediaKind.IMAGE }.take(MAX_CANDIDATES)
        val conditions = VisualVerificationPolicy.conditions(plan)
        if (bounded.isEmpty() || conditions.isEmpty()) {
            return failedBeforeInference(started, bounded.size, "No bounded image conditions were available")
        }
        val status = modelPacks.status()
        val path = status.path
        if (path == null || !status.installed || !status.multimodal) {
            return failedBeforeInference(started, bounded.size, "No verified multimodal Gemma pack is active")
        }
        if (status.deviceAssessment?.supported == false) {
            return failedBeforeInference(started, bounded.size, status.deviceAssessment.reason)
        }

        return try {
            resources.withModel(ModelCapability.GENERATIVE) {
                withContext(Dispatchers.IO) {
                    require(File(path).isFile) { "Verified Gemma artifact is unavailable" }
                    val initialized = createEngine(path)
                    val accepted = linkedSetOf<String>()
                    val evidence = mutableListOf<EvidenceRecord>()
                    val evaluations = mutableListOf<CandidateVerification>()
                    val failures = mutableListOf<VerificationFailure>()
                    var generationCalls = 0
                    var repairedCandidates = 0
                    var generationMs = 0L
                    var closeMs = 0L
                    try {
                        bounded.forEach { hit ->
                            runCatching {
                                val bytes = imageLoader.loadJpeg(hit.item)
                                val generationStarted = SystemClock.elapsedRealtime()
                                val decoded = compiler.compile(conditions, prompt(plan, conditions)) { prompt ->
                                    generate(initialized.engine, bytes, prompt)
                                }
                                generationMs += SystemClock.elapsedRealtime() - generationStarted
                                generationCalls += decoded.generationCalls
                                if (decoded.generationCalls > 1) repairedCandidates++
                                val candidate = CandidateVerification(hit.item.id, decoded.payload.conditions, decoded.payload.overallMatch)
                                evaluations += candidate
                                if (candidate.overallMatch) accepted += hit.item.id
                                decoded.payload.conditions.filter { it.satisfied }.forEach { evaluation ->
                                    val spec = conditions.single { it.id == evaluation.id }
                                    evidence += EvidenceRecord(
                                        id = "${hit.item.id}:visual_verification:${spec.id}",
                                        mediaId = hit.item.id,
                                        sourceField = "visual_verification",
                                        text = spec.text,
                                        confidence = evaluation.confidence,
                                        producerVersion = producerVersion(status),
                                    )
                                }
                            }.onFailure { error ->
                                failures += VerificationFailure(hit.item.id, sanitize(error))
                            }
                        }
                    } finally {
                        val closeStarted = SystemClock.elapsedRealtime()
                        initialized.engine.close()
                        closeMs = SystemClock.elapsedRealtime() - closeStarted
                    }
                    VerificationResult(
                        acceptedIds = accepted,
                        evidence = evidence,
                        applied = true,
                        evaluations = evaluations,
                        failures = failures,
                        trace = VerificationExecutionTrace(
                            usedGemma = true,
                            backend = initialized.backend,
                            modelTier = status.tier,
                            modelRevision = status.packVersion,
                            requestedCandidates = bounded.size,
                            verifiedCandidates = evaluations.size,
                            generationCalls = generationCalls,
                            repairedCandidates = repairedCandidates,
                            engineLoadMs = initialized.loadMs,
                            generationMs = generationMs,
                            engineCloseMs = closeMs,
                            elapsedMs = SystemClock.elapsedRealtime() - started,
                        ),
                    )
                }
            }
        } catch (error: Throwable) {
            failedBeforeInference(started, bounded.size, sanitize(error))
        }
    }

    private fun prompt(plan: GalleryQueryPlan, conditions: List<VerificationConditionSpec>): String {
        val array = JSONArray().apply {
            conditions.forEach { condition ->
                put(JSONObject().apply {
                    put("id", condition.id)
                    put("text", condition.text)
                    put("polarity", condition.polarity.name)
                    put("hardness", condition.hardness.name)
                    put("subject", condition.subject.name)
                    condition.relationToPerson?.let { put("relationToPerson", it) }
                })
            }
        }
        return """
            Inspect the one supplied gallery image against the Kotlin-owned conditions below.
            Return exactly one JSON object and no markdown or explanation.
            Decide whether each condition's literal natural-language text is satisfied by visible evidence. The polarity field is metadata; do not invert text that is already phrased negatively.
            For synthetic cards or diagrams, visible labels and illustrated clothing are valid image evidence.
            Query context: ${JSONObject.quote(plan.originalQuery)}
            Conditions: $array
            Required shape: {"conditions":[{"id":"c1","satisfied":true,"confidence":0.95}],"overallMatch":true}
            Include every supplied ID exactly once. confidence must be from 0 to 1.
            overallMatch is true exactly when every HARD condition is satisfied. SOFT conditions do not control it.
            Never emit media IDs, paths, URIs, boxes, tools, or additional fields.
        """.trimIndent()
    }

    private fun generate(engine: Engine, imageBytes: ByteArray, prompt: String): String {
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 23),
            extraContext = mapOf("enable_thinking" to false),
        )
        return engine.createConversation(config).use { conversation ->
            val message = conversation.sendMessage(
                Contents.of(Content.ImageBytes(imageBytes), Content.Text(prompt)),
                extraContext = mapOf("enable_thinking" to false),
            )
            message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        }
    }

    private fun createEngine(path: String): InitializedVerificationEngine {
        val started = SystemClock.elapsedRealtime()
        val gpu = runCatching {
            initializeEngine(
                EngineConfig(
                    modelPath = path,
                    backend = Backend.GPU(),
                    visionBackend = Backend.GPU(),
                    maxNumTokens = 4096,
                    maxNumImages = 1,
                ),
            ).let { InitializedVerificationEngine(it, VerificationInferenceBackend.GPU, SystemClock.elapsedRealtime() - started) }
        }
        return gpu.getOrElse { gpuFailure ->
            runCatching {
                initializeEngine(
                    EngineConfig(
                        modelPath = path,
                        backend = Backend.CPU(),
                        visionBackend = Backend.CPU(),
                        maxNumTokens = 4096,
                        maxNumImages = 1,
                    ),
                ).let { InitializedVerificationEngine(it, VerificationInferenceBackend.CPU, SystemClock.elapsedRealtime() - started) }
            }.getOrElse { cpuFailure ->
                throw GemmaModelLoadFailure("Gemma visual verification failed on GPU and CPU", cpuFailure.also { it.addSuppressed(gpuFailure) })
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

    private fun failedBeforeInference(started: Long, count: Int, reason: String): VerificationResult {
        val safe = reason.take(240)
        return VerificationResult(
            acceptedIds = emptySet(),
            evidence = emptyList(),
            applied = true,
            failures = listOf(VerificationFailure(null, safe)),
            trace = VerificationExecutionTrace(
                usedGemma = false,
                backend = VerificationInferenceBackend.NOT_RUN,
                requestedCandidates = count,
                elapsedMs = SystemClock.elapsedRealtime() - started,
                fallbackReason = safe,
            ),
        )
    }

    private fun sanitize(error: Throwable): String = when (error) {
        is SecurityException -> "Gallery image access was denied"
        is java.io.FileNotFoundException -> "Gallery image is unavailable"
        is IllegalArgumentException -> "Visual verification input was rejected"
        is GemmaModelLoadFailure -> "The on-device multimodal model could not be loaded"
        else -> "On-device visual verification failed (${error::class.java.simpleName.take(80)})"
    }

    private fun producerVersion(status: ModelPackStatus): String =
        "gemma-4-${status.tier?.name?.lowercase() ?: "unknown"}-${status.packVersion ?: "unknown"}"

    private data class InitializedVerificationEngine(
        val engine: Engine,
        val backend: VerificationInferenceBackend,
        val loadMs: Long,
    )

    companion object {
        const val MAX_CANDIDATES = 8
    }
}

internal object VisualVerificationPolicy {
    private const val MAX_CONDITIONS = 16
    private val hardVisualTerms = setOf("only", "wearing", "behind", "in front", "taller", "shorter", "same person")

    fun requiresVerification(plan: GalleryQueryPlan): Boolean = when (plan.verification) {
        VerificationPolicy.NEVER -> false
        VerificationPolicy.REQUIRED -> true
        VerificationPolicy.AUTO -> plan.semanticClauses.any { clause ->
            clause.hardness == ConstraintStrength.HARD ||
                clause.polarity == Polarity.NEGATIVE ||
                clause.subject == SemanticSubject.PERSON ||
                clause.relationToPerson != null ||
                hardVisualTerms.any { it in clause.text.lowercase() }
        }
    }

    fun conditions(plan: GalleryQueryPlan): List<VerificationConditionSpec> {
        val clauses = plan.semanticClauses.ifEmpty {
            listOf(SemanticClause(plan.originalQuery, hardness = ConstraintStrength.HARD))
        }
        return clauses.take(MAX_CONDITIONS).mapIndexed { index, clause ->
            VerificationConditionSpec(
                id = "c${index + 1}",
                text = clause.text,
                polarity = clause.polarity,
                hardness = clause.hardness,
                subject = clause.subject,
                relationToPerson = clause.relationToPerson,
            )
        }
    }
}
