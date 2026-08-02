package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveContentClassifierTest {
    @Test
    fun protectsSensitiveValuesWithoutTreatingEventEpochsAsCards() {
        assertTrue(SensitiveContentClassifier.isSensitive("Wi-Fi password: mango-tree"))
        assertTrue(SensitiveContentClassifier.isSensitive("Card 4111 1111 1111 1111"))
        assertFalse(
            SensitiveContentClassifier.isSensitive(
                "Gallery memory (1752225783000..1752238975000)",
            ),
        )
    }
}
