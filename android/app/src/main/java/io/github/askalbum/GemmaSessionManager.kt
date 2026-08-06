package io.github.anup42.askalbum

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface SharedGemmaEngine : AutoCloseable {
    val backend: PlannerInferenceBackend
    val mtpSupported: Boolean
        get() = false
    val mtpEnabled: Boolean
        get() = false

    suspend fun generateText(prompt: String, seed: Int): String =
        generateText(prompt, GemmaGenerationOptions(seed = seed))

    suspend fun generateText(prompt: String, options: GemmaGenerationOptions): String =
        generateText(prompt, options.seed)

    suspend fun generateVision(imageBytes: ByteArray, prompt: String, seed: Int): String =
        generateVision(imageBytes, prompt, GemmaGenerationOptions(seed = seed))

    suspend fun generateVision(
        imageBytes: ByteArray,
        prompt: String,
        options: GemmaGenerationOptions,
    ): String = generateVision(imageBytes, prompt, options.seed)
}

internal data class GemmaGenerationOptions(
    val seed: Int,
    val maximumOutputTokens: Int = GemmaOutputBudget.DEFAULT,
    val temperature: Float = 0f,
    val structuredOutput: Boolean = true,
) {
    init {
        require(maximumOutputTokens in 32..GemmaOutputBudget.ENGINE_MAX) {
            "maximumOutputTokens must be between 32 and ${GemmaOutputBudget.ENGINE_MAX}"
        }
        require(temperature.isFinite() && temperature in 0f..2f) {
            "temperature must be finite and between 0 and 2"
        }
    }
}

internal object GemmaOutputBudget {
    const val ENGINE_MAX = 4096
    const val DEFAULT = 768
    const val PLANNER = 768
    const val VISUAL_VERIFIER = 768
    const val GROUNDED_ANSWER = 768
    const val CAPTION = 2048
}

internal fun interface SharedGemmaEngineFactory {
    fun create(modelPath: String, multimodal: Boolean): SharedGemmaEngine
}

internal data class SharedGemmaLease(
    val engine: SharedGemmaEngine,
    val loadMs: Long,
)

/**
 * Owns one verified Gemma generation across planning, visual verification, and answer composition.
 * Heavy calls are serialized; a different model generation replaces and closes the prior engine.
 */
class GemmaSessionManager internal constructor(
    private val resources: InferenceResourceManager,
    private val factory: SharedGemmaEngineFactory,
    private val idleTimeoutMs: Long,
) {
    constructor(resources: InferenceResourceManager) : this(
        resources = resources,
        factory = LiteRtSharedGemmaEngineFactory,
        idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS,
    )

    private val sessionLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initializationCounter = AtomicInteger()
    private var active: ActiveEngine? = null
    private var idleEviction: Job? = null

    val initializationCount: Int
        get() = initializationCounter.get()

    internal suspend fun <T> withEngine(
        modelPath: String,
        multimodal: Boolean,
        priority: InferencePriority = InferencePriority.BACKGROUND,
        block: suspend (SharedGemmaLease) -> T,
    ): T {
        return resources.withModel(ModelCapability.GENERATIVE, priority) {
            sessionLock.withLock {
                idleEviction?.cancel()
                try {
                    val current = active
                    val reused = current != null &&
                        current.modelPath == modelPath &&
                        current.multimodal == multimodal
                    val selected = if (reused) {
                        requireNotNull(current)
                    } else {
                        current?.engine?.closeSafely()
                        val started = System.nanoTime()
                        val engine = factory.create(modelPath, multimodal)
                        initializationCounter.incrementAndGet()
                        ActiveEngine(
                            modelPath = modelPath,
                            multimodal = multimodal,
                            engine = engine,
                            loadMs = (System.nanoTime() - started) / 1_000_000L,
                        ).also { active = it }
                    }
                    block(SharedGemmaLease(selected.engine, if (reused) 0L else selected.loadMs))
                } finally {
                    scheduleIdleEvictionLocked()
                }
            }
        }
    }

    fun evictForMemoryPressure() {
        idleEviction?.cancel()
        scope.launch { evictNow() }
    }

    internal suspend fun evictNow() {
        sessionLock.withLock {
            idleEviction?.cancel()
            idleEviction = null
            active?.engine?.closeSafely()
            active = null
        }
    }

    /** Must be called while [sessionLock] is held so queued calls cannot race the schedule. */
    private fun scheduleIdleEvictionLocked() {
        idleEviction?.cancel()
        idleEviction = scope.launch {
            delay(idleTimeoutMs)
            evictNow()
        }
    }

    private data class ActiveEngine(
        val modelPath: String,
        val multimodal: Boolean,
        val engine: SharedGemmaEngine,
        val loadMs: Long,
    )

    private companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 90_000L
    }
}

private object LiteRtSharedGemmaEngineFactory : SharedGemmaEngineFactory {
    private const val TAG = "AskAlbumGemma"

    override fun create(modelPath: String, multimodal: Boolean): SharedGemmaEngine {
        val gpu = runCatching { create(modelPath, multimodal, gpu = true) }
        return gpu.getOrElse { gpuFailure ->
            Log.w(TAG, "GPU initialization failed; retrying Gemma on CPU", gpuFailure)
            runCatching { create(modelPath, multimodal, gpu = false) }.getOrElse { cpuFailure ->
                throw GemmaModelLoadFailure(
                    "Gemma failed on GPU and CPU",
                    cpuFailure.also { it.addSuppressed(gpuFailure) },
                )
            }
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun create(modelPath: String, multimodal: Boolean, gpu: Boolean): SharedGemmaEngine {
        val backend = if (gpu) Backend.GPU() else Backend.CPU()
        val mtpSupported = if (gpu) {
            runCatching {
                Capabilities(modelPath).use(Capabilities::hasSpeculativeDecodingSupport)
            }.onFailure { error ->
                Log.w(TAG, "Could not read Gemma MTP capability; speculative decoding stays disabled", error)
            }.getOrDefault(false)
        } else {
            false
        }
        val mtpEnabled = gpu && mtpSupported
        val config = if (multimodal) {
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                visionBackend = backend,
                maxNumTokens = 4096,
                maxNumImages = 1,
            )
        } else {
            EngineConfig(modelPath = modelPath, backend = backend, maxNumTokens = 4096)
        }
        ExperimentalFlags.enableSpeculativeDecoding = mtpEnabled
        val engine = Engine(config)
        try {
            engine.initialize()
        } catch (error: Throwable) {
            runCatching { engine.close() }
            throw error
        } finally {
            // This process-global flag is consumed while constructing the engine.
            ExperimentalFlags.enableSpeculativeDecoding = false
        }
        val selectedBackend = if (gpu) PlannerInferenceBackend.GPU else PlannerInferenceBackend.CPU
        Log.i(
            TAG,
            "LiteRT-LM initialized backend=$selectedBackend mtpSupported=$mtpSupported mtpEnabled=$mtpEnabled multimodal=$multimodal",
        )
        return LiteRtSharedGemmaEngine(engine, selectedBackend, mtpSupported, mtpEnabled)
    }
}

private class LiteRtSharedGemmaEngine(
    private val engine: Engine,
    override val backend: PlannerInferenceBackend,
    override val mtpSupported: Boolean,
    override val mtpEnabled: Boolean,
) : SharedGemmaEngine {
    override suspend fun generateText(prompt: String, options: GemmaGenerationOptions): String =
        engine.generateTextCancellable(conversation(options), prompt, DISABLE_THINKING)

    override suspend fun generateVision(
        imageBytes: ByteArray,
        prompt: String,
        options: GemmaGenerationOptions,
    ): String =
        engine.generateTextCancellable(
            conversation(options),
            Contents.of(Content.ImageBytes(imageBytes), Content.Text(prompt)),
            DISABLE_THINKING,
        )

    override fun close() = engine.close()

    private fun conversation(options: GemmaGenerationOptions) = ConversationConfig(
        samplerConfig = SamplerConfig(
            topK = 1,
            topP = 1.0,
            temperature = options.temperature.toDouble(),
            seed = options.seed,
        ),
        extraContext = DISABLE_THINKING,
        maxOutputToken = options.maximumOutputTokens,
        enableResponseFormat = options.structuredOutput,
    )

    private companion object {
        val DISABLE_THINKING = mapOf("enable_thinking" to false)
    }
}

private fun SharedGemmaEngine.closeSafely() {
    runCatching { close() }
}
