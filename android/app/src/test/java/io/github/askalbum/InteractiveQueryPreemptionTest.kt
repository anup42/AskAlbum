package io.github.anup42.askalbum

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveQueryPreemptionTest {
    @Test
    fun backgroundPermitWaitsUntilInteractiveQueryEnds() = runBlocking {
        val backgroundStarted = AtomicBoolean(false)
        IndexingResourceCoordinator.beginInteractiveQuery()
        try {
            val background = async {
                IndexingResourceCoordinator.withBackgroundPermit {
                    backgroundStarted.set(true)
                }
            }
            delay(250)
            assertFalse(backgroundStarted.get())

            IndexingResourceCoordinator.endInteractiveQuery()
            withTimeout(2_000L) { background.await() }
            assertTrue(backgroundStarted.get())
        } finally {
            IndexingResourceCoordinator.endInteractiveQuery()
        }
    }
}
