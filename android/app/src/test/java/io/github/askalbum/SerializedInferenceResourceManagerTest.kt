package io.github.anup42.askalbum

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializedInferenceResourceManagerTest {
    @Test
    fun interactiveLeaseRunsBeforeEarlierQueuedBackgroundLease() = runBlocking {
        val manager = SerializedInferenceResourceManager()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val first = async {
            manager.withModel(ModelCapability.GENERATIVE, InferencePriority.BACKGROUND) {
                firstStarted.complete(Unit)
                releaseFirst.await()
                order += "first"
            }
        }
        firstStarted.await()
        val background = async {
            manager.withModel(ModelCapability.IMAGE_EMBEDDING, InferencePriority.BACKGROUND) {
                order += "background"
            }
        }
        val interactive = async {
            manager.withModel(ModelCapability.GENERATIVE, InferencePriority.INTERACTIVE) {
                order += "interactive"
            }
        }
        delay(25)
        releaseFirst.complete(Unit)
        first.await()
        background.await()
        interactive.await()

        assertEquals(listOf("first", "interactive", "background"), order)
    }

    @Test
    fun cancelledQueuedLeaseDoesNotStarveTheNextRequest() = runBlocking {
        val manager = SerializedInferenceResourceManager()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = async {
            manager.withModel(ModelCapability.GENERATIVE, InferencePriority.BACKGROUND) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()
        val cancelled = async {
            manager.withModel(ModelCapability.IMAGE_EMBEDDING, InferencePriority.BACKGROUND) {
                error("cancelled lease ran")
            }
        }
        delay(10)
        cancelled.cancel()
        val next = async {
            manager.withModel(ModelCapability.TEXT_EMBEDDING, InferencePriority.INTERACTIVE) { "next" }
        }
        releaseFirst.complete(Unit)
        first.await()
        assertEquals("next", next.await())
        assertTrue(cancelled.isCancelled)
    }
}
