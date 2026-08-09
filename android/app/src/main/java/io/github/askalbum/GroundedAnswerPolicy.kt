package io.github.anup42.askalbum

internal object GroundedAnswerPolicy {
    private val answerIntents = setOf(
        QueryIntent.ANSWER_FACT,
        QueryIntent.COMPARE,
        QueryIntent.DOCUMENT_QA,
        QueryIntent.TIMELINE,
        QueryIntent.EVENT_SUMMARY,
        QueryIntent.SUM,
        QueryIntent.MIN_MAX,
    )

    fun shouldCompose(
        plan: GalleryQueryPlan,
        hasHits: Boolean,
        modelInstalled: Boolean,
        verificationApplied: Boolean,
    ): Boolean {
        if (!hasHits || !modelInstalled || plan.answerMode == AnswerMode.RESULTS_ONLY) return false
        if (verificationApplied || plan.intent in answerIntents) return true
        return plan.intent == QueryIntent.FIND_MEDIA && (
            plan.terms.isNotEmpty() ||
                plan.semanticClauses.any { it.polarity == Polarity.POSITIVE && it.text.isNotBlank() }
            )
    }
}
