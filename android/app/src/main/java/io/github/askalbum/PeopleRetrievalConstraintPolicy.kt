package io.github.anup42.askalbum

/** Removes identity-only retrieval noise after reviewed People have become deterministic hard filters. */
internal object PeopleRetrievalConstraintPolicy {
    private val tokenPattern = Regex("[\\p{L}\\p{M}\\p{N}]+")
    private val glueTokens = setOf(
        "a", "an", "and", "aur", "with", "my", "our", "the", "where",
        "photo", "photos", "picture", "pictures", "image", "images",
        "mera", "mere", "meri", "saath", "wali", "wala", "ke", "ki", "ka",
        "और", "मेरा", "मेरे", "मेरी", "साथ", "वाली", "वाला", "के", "की", "का",
        "फोटो", "फोटोस", "तस्वीर", "तस्वीरें",
    ).map(PersonIdentityNormalization::normalize).toSet()

    fun apply(
        plan: GalleryQueryPlan,
        resolveReviewedIds: (String) -> Set<String>,
    ): GalleryQueryPlan {
        if (plan.peopleClauses.isEmpty()) return plan
        return plan.copy(
            terms = plan.terms.filterNot { resolveReviewedIds(it).isNotEmpty() },
            semanticClauses = plan.semanticClauses.filterNot { clause ->
                clause.subject == SemanticSubject.WHOLE_MEDIA && identityOnly(clause, resolveReviewedIds)
            },
        )
    }

    private fun identityOnly(
        clause: SemanticClause,
        resolveReviewedIds: (String) -> Set<String>,
    ): Boolean {
        val variants = listOfNotNull(clause.text, clause.canonicalText)
            .map(String::trim)
            .filter(String::isNotBlank)
        return variants.isNotEmpty() && variants.all { text ->
            val meaningful = tokenPattern.findAll(PersonIdentityNormalization.normalize(text))
                .map(MatchResult::value)
                .filterNot(glueTokens::contains)
                .toList()
            meaningful.isNotEmpty() && meaningful.all { token -> resolveReviewedIds(token).isNotEmpty() }
        }
    }
}
