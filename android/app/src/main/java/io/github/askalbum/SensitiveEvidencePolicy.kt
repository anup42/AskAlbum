package io.github.anup42.askalbum

object SensitiveContentClassifier {
    private val keywordPatterns = listOf(
        Regex("(?i)\\b(password|passcode|pin|cvv|account number|medical record|diagnosis)\\b"),
        Regex("(?i)\\b(aadhaar|passport|social security|ssn)\\b"),
    )
    private val paymentCardCandidate = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")
    private val emailCandidate = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val labeledContactCandidate = Regex("(?i)\\b(email|e-mail|phone|mobile|tel)\\b\\s*[:=]?\\s*[^\\r\\n,;]+")
    private val credentialValueCandidate = Regex(
        "(?i)\\b(password|passcode|pin|cvv|account number|aadhaar|passport|social security|ssn)\\b" +
            "\\s*[:=]?\\s*[^\\r\\n,;]*?(?=(?:[,;]|[.!?](?:\\s|$)|$))",
    )
    private val currencyAmountCandidate = Regex(
        "(?i)(?:₹|rs\\.?|inr|usd|eur|gbp|\\$|€|£)\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?|" +
            "[0-9][0-9,]*(?:\\.[0-9]{1,2})?\\s*(?:₹|rs\\.?|inr|usd|eur|gbp|\\$|€|£)",
    )
    private val labeledAmountCandidate = Regex(
        "(?i)\\b(grand total|amount paid|net payable|balance due|receipt total|total)\\b" +
            "\\s*[:=]?\\s*(?:₹|rs\\.?|inr|usd|eur|gbp|\\$|€|£)?\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?",
    )

    fun isSensitive(text: String): Boolean =
        text.isNotBlank() && (
            keywordPatterns.any { it.containsMatchIn(text) } ||
                paymentCardCandidate.findAll(text).any { looksLikePaymentCard(it.value) } ||
                emailCandidate.containsMatchIn(text) ||
                labeledContactCandidate.containsMatchIn(text) ||
                currencyAmountCandidate.containsMatchIn(text) ||
                labeledAmountCandidate.containsMatchIn(text)
            )

    /** Keeps searchable labels while ensuring raw credentials/contact values never enter FTS. */
    fun redactForSearch(text: String): String {
        if (text.isBlank()) return text
        var changed = false
        var redacted = text.replace(credentialValueCandidate) { match ->
            changed = true
            "${match.groupValues[1]}: [REDACTED]"
        }
        redacted = redacted.replace(labeledContactCandidate) { match ->
            changed = true
            "${match.groupValues[1]}: [REDACTED]"
        }
        redacted = redacted.replace(emailCandidate) {
            changed = true
            "[REDACTED_EMAIL]"
        }
        redacted = redacted.replace(labeledAmountCandidate) { match ->
            changed = true
            "${match.groupValues[1]}: [REDACTED_AMOUNT]"
        }
        redacted = redacted.replace(currencyAmountCandidate) {
            changed = true
            "[REDACTED_AMOUNT]"
        }
        redacted = redacted.replace(paymentCardCandidate) { match ->
            if (looksLikePaymentCard(match.value)) {
                changed = true
                "[REDACTED_CARD]"
            } else {
                match.value
            }
        }
        return if (!changed && isSensitive(text)) "[REDACTED_SENSITIVE_OCR]" else redacted
    }

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

    fun requiresAuthentication(evidence: EvidenceRecord): Boolean =
        OcrFactAllowlist.fromSource(evidence.sourceField)?.sensitive == true ||
            evidence.sourceField == "document_password" ||
            SensitiveContentClassifier.isSensitive(evidence.text)
    fun requiresAuthentication(hit: SearchHit): Boolean =
        hit.evidence.any { evidence -> requiresAuthentication(evidence) }    fun lock(answer: SearchAnswer): SearchAnswer = answer.copy(
        headline = LOCKED_HEADLINE,
        detail = LOCKED_DETAIL,
        evidenceIds = emptyList(),
        claims = emptyList(),
        warnings = (answer.warnings + LOCKED_WARNING).distinct(),
        requiresAuthentication = true,
    )
}
