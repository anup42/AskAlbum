package io.github.anup42.askalbum

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GemmaGenerationOptionsTest {
    @Test
    fun optionsAreForwardedThroughTheSharedEngineWithoutAnotherInitialization() = runBlocking {
        val seen = mutableListOf<GemmaGenerationOptions>()
        val engine = CapturingEngine(seen)
        val manager = GemmaSessionManager(
            resources = object : InferenceResourceManager {
                override suspend fun <T> withModel(capability: ModelCapability, block: suspend () -> T): T = block()
            },
            factory = SharedGemmaEngineFactory { _, _ -> engine },
            idleTimeoutMs = 60_000L,
        )

        manager.withEngine("fixture.gemma", multimodal = true) { lease ->
            lease.engine.generateText(
                "plan",
                GemmaGenerationOptions(17, maximumOutputTokens = GemmaOutputBudget.PLANNER, structuredOutput = true),
            )
            lease.engine.generateVision(
                byteArrayOf(1),
                "caption",
                GemmaGenerationOptions(31, maximumOutputTokens = GemmaOutputBudget.CAPTION, structuredOutput = true),
            )
        }

        assertEquals(1, manager.initializationCount)
        assertEquals(listOf(17, 31), seen.map(GemmaGenerationOptions::seed))
        assertEquals(listOf(GemmaOutputBudget.PLANNER, GemmaOutputBudget.CAPTION), seen.map(GemmaGenerationOptions::maximumOutputTokens))
    }

    @Test
    fun invalidOutputBudgetIsRejectedBeforeInference() {
        assertThrows(IllegalArgumentException::class.java) {
            GemmaGenerationOptions(seed = 1, maximumOutputTokens = GemmaOutputBudget.ENGINE_MAX + 1)
        }
    }

    private class CapturingEngine(
        private val seen: MutableList<GemmaGenerationOptions>,
    ) : SharedGemmaEngine {
        override val backend = PlannerInferenceBackend.CPU

        override suspend fun generateText(prompt: String, options: GemmaGenerationOptions): String {
            seen += options
            return "{}"
        }

        override suspend fun generateVision(
            imageBytes: ByteArray,
            prompt: String,
            options: GemmaGenerationOptions,
        ): String {
            seen += options
            return "{}"
        }

        override fun close() = Unit
    }
}
