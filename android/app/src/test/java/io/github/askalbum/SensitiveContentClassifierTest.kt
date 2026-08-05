package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveContentClassifierTest {
    @Test
    fun protectsSensitiveValuesWithoutTreatingEventEpochsAsCards() {
        assertTrue(SensitiveContentClassifier.isSensitive("Wi-Fi password: mango-tree"))
        assertTrue(SensitiveContentClassifier.isSensitive("Card 4111 1111 1111 1111"))
        assertTrue(SensitiveContentClassifier.isSensitive("Receipt total Rs 1,248"))
        assertFalse(
            SensitiveContentClassifier.isSensitive(
                "Gallery memory (1752225783000..1752238975000)",
            ),
        )
    }

    @Test
    fun redactsFinancialAmountsWhileKeepingTheLabelSearchable() {
        val projected = SensitiveContentClassifier.redactForSearch("Grand total: ₹1,248.50")

        assertTrue(projected.contains("grand total", ignoreCase = true))
        assertTrue(projected.contains("[REDACTED_AMOUNT]"))
        assertFalse(projected.contains("1,248.50"))
    }
    @Test
    fun allowlistedSensitiveEvidenceRequiresAuthenticationEvenWhenTextLooksBenign() {
        val password = EvidenceRecord("password", "media-1", "document_password", "private-value", .9f)
        val metadata = EvidenceRecord("metadata", "media-1", "metadata", "holiday photo", .9f)
        assertTrue(SensitiveEvidencePolicy.requiresAuthentication(password))
        assertFalse(SensitiveEvidencePolicy.requiresAuthentication(metadata))
    }}
