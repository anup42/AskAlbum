package io.github.anup42.askalbum

/** Converts natural-language negation to one unambiguous predicate plus Kotlin-owned polarity. */
internal object SemanticPolarityNormalizer {
    private val leadingNegation = Regex(
        """^\s*(?:do\s+not\s+show|don't\s+show|without|exclude(?:d|ing)?|not|no)\s+""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val verbNegation = Regex(
        """\b(am|is|are|was|were|be|being|do|does|did)\s+(?:not|never)\s+""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val embeddedNegation = Regex(
        """\b(?:without|excluding|except)\s+""",
        setOf(RegexOption.IGNORE_CASE),
    )

    fun normalize(clause: SemanticClause): SemanticClause {
        val textResult = stripNegation(clause.text)
        val canonicalResult = clause.canonicalText?.let(::stripNegation)
        val explicitlyNegative = clause.polarity == Polarity.NEGATIVE || textResult.second || canonicalResult?.second == true
        return clause.copy(
            text = textResult.first,
            canonicalText = canonicalResult?.first?.takeIf(String::isNotBlank),
            polarity = if (explicitlyNegative) Polarity.NEGATIVE else clause.polarity,
        )
    }

    private fun stripNegation(raw: String): Pair<String, Boolean> {
        var value = raw.trim()
        var negative = false
        leadingNegation.find(value)?.let { match ->
            value = value.removeRange(match.range)
            negative = true
        }
        verbNegation.find(value)?.let { match ->
            value = value.replaceRange(match.range, "${match.groupValues[1]} ")
            negative = true
        }
        embeddedNegation.find(value)?.let { match ->
            value = value.removeRange(match.range)
            negative = true
        }
        return (value.trim().ifBlank { raw.trim() }) to negative
    }

    fun conditionMatched(spec: VerificationConditionSpec, predicateVisible: Boolean): Boolean =
        if (spec.polarity == Polarity.NEGATIVE) !predicateVisible else predicateVisible

    fun conditionMatched(spec: VerificationConditionSpec, evaluation: VerificationConditionEvaluation): Boolean = when {
        spec.polarity == Polarity.POSITIVE -> evaluation.verdict == PersonVisualVerdict.VERIFIED_TRUE
        else -> evaluation.verdict == PersonVisualVerdict.VERIFIED_FALSE
    }
}
