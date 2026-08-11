package io.github.anup42.askalbum

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    fun failedReplacementKeepsThePreviousEngineUsable() = runBlocking {
        val first = FakeEngine()
        var createCalls = 0
        val manager = GemmaSessionManager(
            resources = PassthroughResources(),
            factory = SharedGemmaEngineFactory { _, _ ->
                createCalls++
                if (createCalls == 2) error("replacement load failed")
                first
            },
            idleTimeoutMs = 60_000,
        )

        manager.withEngine("pack-e2b", multimodal = true) { }
        var failed = false
        try {
            manager.withEngine("pack-e4b", multimodal = true) { }
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        manager.withEngine("pack-e2b", multimodal = true) { lease -> assertSame(first, lease.engine) }
        assertEquals(1, manager.initializationCount)
        assertEquals(0, first.closeCount)
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

    @Test
    fun idleTimeoutEvictsAndRecreatesTheEngine() = runBlocking {
        val created = mutableListOf<FakeEngine>()
        val manager = GemmaSessionManager(
            resources = PassthroughResources(),
            factory = SharedGemmaEngineFactory { _, _ -> FakeEngine().also(created::add) },
            idleTimeoutMs = 25,
        )

        manager.withEngine("pack-e2b", multimodal = true) { }
        withTimeout(2_000) {
            while (created.single().closeCount == 0) delay(5)
        }
        manager.withEngine("pack-e2b", multimodal = true) { }

        assertEquals(2, manager.initializationCount)
        assertEquals(2, created.size)
        assertEquals(1, created.first().closeCount)
        manager.evictNow()
    }

    @Test
    fun memoryPressureWaitsForTheActiveCallThenEvictsBeforeReuse() = runBlocking {
        val created = mutableListOf<FakeEngine>()
        val manager = GemmaSessionManager(
            resources = PassthroughResources(),
            factory = SharedGemmaEngineFactory { _, _ -> FakeEngine().also(created::add) },
            idleTimeoutMs = 60_000,
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val activeCall = async {
            manager.withEngine("pack-e2b", multimodal = true) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        manager.evictForMemoryPressure()
        delay(25)
        assertEquals(0, created.single().closeCount)
        release.complete(Unit)
        activeCall.await()
        withTimeout(2_000) {
            while (created.single().closeCount == 0) delay(5)
        }

        manager.withEngine("pack-e2b", multimodal = true) { }
        assertEquals(2, manager.initializationCount)
        assertEquals(2, created.size)
        manager.evictNow()
    }

    @Test
    fun cancellingAHeavyCallReleasesTheSessionWithoutDiscardingTheEngine() = runBlocking {
        val created = mutableListOf<FakeEngine>()
        val manager = GemmaSessionManager(
            resources = PassthroughResources(),
            factory = SharedGemmaEngineFactory { _, _ -> FakeEngine().also(created::add) },
            idleTimeoutMs = 60_000,
        )
        val entered = CompletableDeferred<Unit>()
        val cancelledCall = async {
            manager.withEngine("pack-e2b", multimodal = true) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        cancelledCall.cancelAndJoin()

        manager.withEngine("pack-e2b", multimodal = true) { lease ->
            assertSame(created.single(), lease.engine)
        }
        assertEquals(1, manager.initializationCount)
        manager.evictNow()
    }

    @Test
    fun interactiveCallPreemptsAndRestartsBackgroundWithoutReloadingGemma() = runBlocking {
        val created = mutableListOf<FakeEngine>()
        val manager = GemmaSessionManager(
            resources = SerializedInferenceResourceManager(),
            factory = SharedGemmaEngineFactory { _, _ -> FakeEngine().also(created::add) },
            idleTimeoutMs = 60_000,
        )
        val backgroundStarted = CompletableDeferred<Unit>()
        var backgroundAttempts = 0
        val background = async {
            manager.withEngine("pack-e2b", multimodal = true, priority = InferencePriority.BACKGROUND) {
                backgroundAttempts += 1
                if (backgroundAttempts == 1) {
                    backgroundStarted.complete(Unit)
                    awaitCancellation()
                }
                "background-complete"
            }
        }
        backgroundStarted.await()

        val interactive = async {
            manager.withEngine("pack-e2b", multimodal = true, priority = InferencePriority.INTERACTIVE) {
                "interactive-complete"
            }
        }

        assertEquals("interactive-complete", withTimeout(2_000) { interactive.await() })
        assertEquals("background-complete", withTimeout(2_000) { background.await() })
        assertEquals(2, backgroundAttempts)
        assertEquals(1, manager.initializationCount)
        assertEquals(1, created.size)
        manager.evictNow()
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
