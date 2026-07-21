package com.askphotos.android

import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GroundedAnswerCompositionTrace(
    val usedGemma: Boolean,
    val backend: PlannerInferenceBackend,
    val modelTier: GemmaModelTier? = null,
    val modelRevision: String? = null,
    val generationCalls: Int = 0,
    val repaired: Boolean = false,
    val evidenceCount: Int = 0,
    val engineLoadMs: Long = 0,
    val generationMs: Long = 0,
    val engineCloseMs: Long = 0,
    val elapsedMs: Long = 0,
    val fallbackReason: String? = null,
)

data class GroundedAnswerCompositionResult(
    val answer: SearchAnswer,
    val trace: GroundedAnswerCompositionTrace,
)

/** Optional text-only wording stage. Deterministic answers remain authoritative on every failure. */
class LiteRtGemmaGroundedAnswerComposer(
    private val modelPacks: ModelPackManager,
    private val resources: InferenceResourceManager,
    private val compiler: BoundedGemmaAnswerCompiler = BoundedGemmaAnswerCompiler(),
) {
    suspend fun compose(input: GroundedAnswerInput): GroundedAnswerCompositionResult {
        val started = SystemClock.elapsedRealtime()
        val baseline = requireNotNull(input.deterministicAnswer) { "A deterministic baseline answer is required" }
        val packet = runCatching { GroundedEvidencePacketBuilder.build(input) }.getOrElse {
            return fallback(baseline, started, "No valid evidence packet was available")
        }
        if (input.hits.isEmpty() || packet.evidence.isEmpty()) {
            return fallback(baseline, started, "No answer generation was needed for an empty evidence set")
        }
        val status = modelPacks.status()
        val path = status.path ?: return fallback(baseline, started, "No verified Gemma pack is active")
        if (status.deviceAssessment?.supported == false) return fallback(baseline, started, status.deviceAssessment.reason)

        return try {
            resources.withModel(ModelCapability.GENERATIVE) {
                withContext(Dispatchers.IO) {
                    require(File(path).isFile) { "Verified Gemma artifact is unavailable" }
                    val initialized = createEngine(path)
                    var generationMs = 0L
                    var closeMs = 0L
                    var decoded: BoundedGroundedAnswerDecode? = null
                    try {
                        val generationStarted = SystemClock.elapsedRealtime()
                        decoded = compiler.compile(packet, prompt(packet)) { generate(initialized.engine, it) }
                        generationMs = SystemClock.elapsedRealtime() - generationStarted
                    } finally {
                        val closeStarted = SystemClock.elapsedRealtime()
                        initialized.engine.close()
                        closeMs = SystemClock.elapsedRealtime() - closeStarted
                    }
                    val result = requireNotNull(decoded)
                    GroundedAnswerCompositionResult(
                        answer = result.answer,
                        trace = GroundedAnswerCompositionTrace(
                            usedGemma = true,
                            backend = initialized.backend,
                            modelTier = status.tier,
                            modelRevision = status.packVersion,
                            generationCalls = result.generationCalls,
                            repaired = result.generationCalls > 1,
                            evidenceCount = packet.evidence.size,
                            engineLoadMs = initialized.loadMs,
                            generationMs = generationMs,
                            engineCloseMs = closeMs,
                            elapsedMs = SystemClock.elapsedRealtime() - started,
                        ),
                    )
                }
            }
        } catch (load: GemmaModelLoadFailure) {
            modelPacks.rollbackAfterLoadFailure(path)
            fallback(baseline, started, "The on-device answer model could not be loaded")
        } catch (_: Throwable) {
            fallback(baseline, started, "Grounded answer validation failed; the deterministic answer was retained")
        }
    }

    private fun prompt(packet: GroundedEvidencePacket): String = """
        Word a concise personal-gallery answer using only the supplied local evidence packet.
        Return exactly one JSON object and no markdown.
        Required shape: {"headline":"Short answer","detail":"Evidence-only detail","claims":[{"text":"Supported claim","evidenceIds":["EV1"],"confidence":0.95}]}
        Every factual claim must cite one or more evidenceIds copied exactly from the packet.
        Do not invent media, IDs, facts, people, places, numbers, dates, paths, URIs, or unsupported interpretations.
        Preserve deterministic numbers, dates, exactness, and coverage. If a paraphrase might alter a fact, copy the baseline wording.
        Evidence packet: ${packet.toPromptJson()}
    """.trimIndent()

    private fun generate(engine: Engine, prompt: String): String {
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 29),
            extraContext = mapOf("enable_thinking" to false),
        )
        return engine.createConversation(config).use { conversation ->
            val message = conversation.sendMessage(prompt, extraContext = mapOf("enable_thinking" to false))
            message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        }
    }

    private fun createEngine(path: String): InitializedAnswerEngine {
        val started = SystemClock.elapsedRealtime()
        val gpu = runCatching {
            initializeEngine(EngineConfig(modelPath = path, backend = Backend.GPU(), maxNumTokens = 4096))
                .let { InitializedAnswerEngine(it, PlannerInferenceBackend.GPU, SystemClock.elapsedRealtime() - started) }
        }
        return gpu.getOrElse { gpuFailure ->
            runCatching {
                initializeEngine(EngineConfig(modelPath = path, backend = Backend.CPU(), maxNumTokens = 4096))
                    .let { InitializedAnswerEngine(it, PlannerInferenceBackend.CPU, SystemClock.elapsedRealtime() - started) }
            }.getOrElse { cpuFailure ->
                throw GemmaModelLoadFailure("Gemma answer composition failed on GPU and CPU", cpuFailure.also { it.addSuppressed(gpuFailure) })
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

    private fun fallback(baseline: SearchAnswer, started: Long, reason: String): GroundedAnswerCompositionResult =
        GroundedAnswerCompositionResult(
            answer = baseline.copy(warnings = (baseline.warnings + reason).distinct()),
            trace = GroundedAnswerCompositionTrace(
                usedGemma = false,
                backend = PlannerInferenceBackend.DETERMINISTIC,
                elapsedMs = SystemClock.elapsedRealtime() - started,
                fallbackReason = reason,
            ),
        )

    private data class InitializedAnswerEngine(
        val engine: Engine,
        val backend: PlannerInferenceBackend,
        val loadMs: Long,
    )
}
