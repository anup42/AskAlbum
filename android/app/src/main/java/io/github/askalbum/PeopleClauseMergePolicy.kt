package io.github.anup42.askalbum

/** Merges planner, deterministic, and reviewed-database People references without polarity loss. */
internal object PeopleClauseMergePolicy {
    fun merge(
        plannerClauses: List<PersonClause>,
        detectedClauses: List<PersonClause>,
        reviewedGroups: List<ReviewedPersonMatchGroup>,
        resolveReviewedIds: (String) -> Set<String>,
    ): List<PersonClause> {
        val explicit = plannerClauses + detectedClauses
        val explicitIds = explicit.flatMapTo(linkedSetOf()) { clause ->
            resolveReviewedIds(clause.personId) + clause.personId
        }
        return (explicit + reviewedGroups.flatMap { group ->
            if (group.personIds.any(explicitIds::contains)) {
                emptyList()
            } else {
                group.personIds.map { personId ->
                    PersonClause(personId = personId, alternativeGroup = group.alternativeGroup)
                }
            }
        }).distinctBy { Triple(it.personId, it.mustBePresent, it.alternativeGroup) }
    }
}
