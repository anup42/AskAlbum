package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class GemmaAutoProvisionPolicyTest {
    @Test
    fun e4bClassDeviceTriesE2bBeforeOptionalE4bFallback() {
        val status = ModelPackStatus(
            installed = false,
            deviceAssessment = assessment(GemmaModelTier.E4B),
        )

        assertEquals(listOf(GemmaModelTier.E2B, GemmaModelTier.E4B), automaticGemmaCandidates(status))
    }

    @Test
    fun e2bClassDeviceOnlyTriesE2b() {
        val status = ModelPackStatus(
            installed = false,
            deviceAssessment = assessment(GemmaModelTier.E2B),
        )

        assertEquals(listOf(GemmaModelTier.E2B), automaticGemmaCandidates(status))
    }

    @Test
    fun activeModelPreventsAnotherDownload() {
        val status = ModelPackStatus(installed = true, tier = GemmaModelTier.E2B)

        assertEquals(emptyList<GemmaModelTier>(), automaticGemmaCandidates(status))
    }

    private fun assessment(recommended: GemmaModelTier) = GemmaDeviceAssessment(
        supported = true,
        recommendedTier = recommended,
        totalRamBytes = 12L * 1024 * 1024 * 1024,
        memoryClassMb = 512,
        reason = "fixture",
    )
}
