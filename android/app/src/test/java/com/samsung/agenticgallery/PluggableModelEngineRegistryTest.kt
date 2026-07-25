package com.samsung.agenticgallery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluggableModelEngineRegistryTest {
    @Test
    fun selectsFirstAvailableProviderAndClosesLease() = runBlocking {
        val first = FakeProvider("first", available = false)
        val fallback = FakeProvider("fallback", available = true)
        val registry = PluggableModelEngineRegistry(listOf(first, fallback))

        val lease = registry.acquire()

        assertEquals("fallback", lease.descriptor.id)
        assertFalse(lease.engine.closed)
        lease.close()
        assertTrue(lease.engine.closed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateProviderIds() {
        PluggableModelEngineRegistry(listOf(FakeProvider("same", true), FakeProvider("same", true)))
    }

    private class FakeEngine : AutoCloseable {
        var closed = false
        override fun close() { closed = true }
    }

    private class FakeProvider(
        id: String,
        private val available: Boolean,
    ) : ModelEngineProvider<FakeEngine> {
        override val descriptor = ModelEngineDescriptor(id, id, "$id-v1", "test", setOf(ModelCapability.OCR))
        override fun isAvailable(): Boolean = available
        override suspend fun create(): FakeEngine = FakeEngine()
    }
}
