package io.github.anup42.askalbum

/** Converts natural-language negation to one unambiguous predicate plus Kotlin-owned polarity. */
internal object SemanticPolarityNormalizer {
    private val leadingNegation = Regex(
        """^\s*(?:do\s+not\s+show|don't\s+show|without|exclude(?:d|ing)?|not|no)\s+""",
        setOf(RegexOption.IGNORE_CASE),
    )

    fun normalize(clause: SemanticClause): SemanticClause {
        val explicitlyNegative = leadingNegation.containsMatchIn(clause.text)
        val predicate = clause.text.replaceFirst(leadingNegation, "").trim().ifBlank { clause.text.trim() }
        return clause.copy(
            text = predicate,
            canonicalText = clause.canonicalText
                ?.replaceFirst(leadingNegation, "")
                ?.trim()
                ?.takeIf(String::isNotBlank),
            polarity = if (explicitlyNegative) Polarity.NEGATIVE else clause.polarity,
        )
    }

    fun conditionMatched(spec: VerificationConditionSpec, predicateVisible: Boolean): Boolean =
        if (spec.polarity == Polarity.NEGATIVE) !predicateVisible else predicateVisible

    fun conditionMatched(spec: VerificationConditionSpec, evaluation: VerificationConditionEvaluation): Boolean = when {
        spec.polarity == Polarity.POSITIVE -> evaluation.verdict == PersonVisualVerdict.VERIFIED_TRUE
        else -> evaluation.verdict == PersonVisualVerdict.VERIFIED_FALSE
    }
}
