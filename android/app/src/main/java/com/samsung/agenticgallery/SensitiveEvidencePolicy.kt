package com.samsung.agenticgallery

object SensitiveContentClassifier {
    private val patterns = listOf(
        Regex("(?i)\\b(password|passcode|pin|cvv|account number|medical record|diagnosis)\\b"),
        Regex("\\b(?:\\d[ -]*?){13,19}\\b"),
        Regex("(?i)\\b(aadhaar|passport|social security|ssn)\\b"),
    )

    fun isSensitive(text: String): Boolean = text.isNotBlank() && patterns.any { it.containsMatchIn(text) }
}

/** Keeps high-risk OCR out of generative prompts and rendered answer cards until device authentication. */
object SensitiveEvidencePolicy {
    const val LOCKED_HEADLINE = "Sensitive result found"
    const val LOCKED_DETAIL = "Authenticate on this device to view the matching private OCR evidence."
    const val LOCKED_WARNING = "Sensitive OCR was withheld from Gemma and the answer card."

    fun requiresAuthentication(hit: SearchHit): Boolean =
        SensitiveContentClassifier.isSensitive(hit.item.ocrText) ||
            hit.evidence.any { it.sourceField == "document_password" } ||
            hit.evidence.any { SensitiveContentClassifier.isSensitive(it.text) }

    fun lock(answer: SearchAnswer): SearchAnswer = answer.copy(
        headline = LOCKED_HEADLINE,
        detail = LOCKED_DETAIL,
        evidenceIds = emptyList(),
        claims = emptyList(),
        warnings = (answer.warnings + LOCKED_WARNING).distinct(),
        requiresAuthentication = true,
    )
}
