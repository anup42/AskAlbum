package io.github.anup42.askalbum

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GemmaSessionManagerTest {
    @Test
    fun oneSessionReusesOneEngineForPlannerVerificationAndAnswerCalls() = runBlocking {
        val created = mutableListOf<FakeEngine>()
        val manager = GemmaSessionManager(
            resources = PassthroughResources(),
            factory = SharedGemmaEngineFactory { _, _ -> FakeEngine().also(created::add) },
            idleTimeoutMs = 60_000,
        )

        var first: SharedGemmaEngine? = null
        var second: SharedGemmaEngine? = null
        var third: SharedGemmaEngine? = null
        manager.withEngine("pack-e2b", multimodal = true) { lease ->
            first = lease.engine
            lease.engine.generateText("plan", seed = 17)
        }
        manager.withEngine("pack-e2b", multimodal = true) { lease ->
            second = lease.engine
            lease.engine.generateVision(byteArrayOf(1, 2), "verify", seed = 23)
        }
        manager.withEngine("pack-e2b", multimodal = true) { lease ->
            third = lease.engine
            lease.engine.generateText("answer", seed = 29)
        }

        assertEquals(1, manager.initializationCount)
        assertEquals(1, created.size)
        assertSame(first, second)
        assertSame(second, third)

        manager.evictNow()
        assertEquals(1, created.single().closeCount)
    }

    @Test
    fun changingModelGenerationOrModalityCreatesASeparateEngine() = runBlocking {
        val created = mutableListOf<FakeEngine>()
        val manager = GemmaSessionManager(
            resources = PassthroughResources(),
            factory = SharedGemmaEngineFactory { _, _ -> FakeEngine().also(created::add) },
            idleTimeoutMs = 60_000,
        )

        manager.withEngine("pack-e2b", multimodal = true) { }
        manager.withEngine("pack-e2b", multimodal = false) { }
        manager.withEngine("pack-e4b", multimodal = true) { }

        assertEquals(3, manager.initializationCount)
        assertEquals(3, created.size)
        assertEquals(2, created.count { it.closeCount == 1 })
        assertEquals(1, created.count { it.closeCount == 0 })
    }

    private class PassthroughResources : InferenceResourceManager {
        override suspend fun <T> withModel(capability: ModelCapability, block: suspend () -> T): T = block()
    }

    private class FakeEngine : SharedGemmaEngine {
        override val backend = PlannerInferenceBackend.GPU
        var closeCount = 0

        override suspend fun generateText(prompt: String, seed: Int): String = "text"

        override suspend fun generateVision(imageBytes: ByteArray, prompt: String, seed: Int): String = "vision"

        override fun close() {
            closeCount++
        }
    }
}
