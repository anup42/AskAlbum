package com.askphotos.android

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddingBatchPolicyTest {
    @Test
    fun scalesConservativelyWithHeapAndPhysicalRam() {
        assertEquals(4, EmbeddingBatchPolicy.forDevice(memoryClassMb = 128, totalRamMb = 8_192))
        assertEquals(4, EmbeddingBatchPolicy.forDevice(memoryClassMb = 256, totalRamMb = 3_999))
        assertEquals(12, EmbeddingBatchPolicy.forDevice(memoryClassMb = 256, totalRamMb = 7_168))
        assertEquals(12, EmbeddingBatchPolicy.forDevice(memoryClassMb = 512, totalRamMb = 4_096))
        assertEquals(24, EmbeddingBatchPolicy.forDevice(memoryClassMb = 384, totalRamMb = 7_168))
    }
}
