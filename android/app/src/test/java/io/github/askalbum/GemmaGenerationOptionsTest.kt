package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GemmaGenerationOptionsTest {
    @Test
    fun acceptsCaptionBudgetAndSamplerSettings() {
        val options = GemmaGenerationOptions(31, 3072, 0.2f, true)
        assertEquals(3072, options.maximumOutputTokens)
        assertEquals(0.2f, options.temperature)
    }

    @Test
    fun rejectsUnsafeOutputBudgets() {
        assertThrows(IllegalArgumentException::class.java) {
            GemmaGenerationOptions(1, 32, 0f, true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GemmaGenerationOptions(1, 4097, 0f, true)
        }
    }
}
