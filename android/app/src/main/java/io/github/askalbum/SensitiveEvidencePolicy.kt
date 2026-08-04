package io.github.anup42.askalbum

object SensitiveContentClassifier {
    const val REDACTED_MARKER = "[REDACTED]"

    private val keywordPatterns = listOf(
        Regex("(?i)\\b(password|passcode|pin|cvv|account number|medical record|diagnosis)\\b"),
        Regex("(?i)\\b(aadhaar|passport|social security|ssn)\\b"),
    )
    private val paymentCardCandidate = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")

    fun isSensitive(text: String): Boolean =
        text.isNotBlank() && (
            text.contains(REDACTED_MARKER) ||
            keywordPatterns.any { it.containsMatchIn(text) } ||
                paymentCardCandidate.findAll(text).any { looksLikePaymentCard(it.value) }
            )

    private fun looksLikePaymentCard(candidate: String): Boolean {
        val digits = candidate.filter(Char::isDigit)
        if (digits.length !in 13..19) return false
        if (digits.length == 13) {
            val value = digits.toLongOrNull()
            if (value != null && value in MIN_REASONABLE_EPOCH_MS..MAX_REASONABLE_EPOCH_MS) return false
        }
        var sum = 0
        var doubleDigit = false
        for (index in digits.indices.reversed()) {
            var digit = digits[index].digitToInt()
            if (doubleDigit) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            doubleDigit = !doubleDigit
        }
        return sum % 10 == 0
    }

    private const val MIN_REASONABLE_EPOCH_MS = 946_684_800_000L
    private const val MAX_REASONABLE_EPOCH_MS = 4_102_444_800_000L
}

/** Keeps high-risk OCR out of generative prompts and rendered answer cards until device authentication. */
object SensitiveEvidencePolicy {
    const val LOCKED_HEADLINE = "Sensitive result found"
    const val LOCKED_DETAIL = "Authenticate on this device to view the matching private OCR evidence."
    const val LOCKED_WARNING = "Sensitive OCR was withheld from Gemma and the answer card."

    fun requiresAuthentication(hit: SearchHit): Boolean =
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
