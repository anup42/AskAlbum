package io.github.anup42.askalbum

/** Merges planner, deterministic, and reviewed-database People references without polarity loss. */
internal object PeopleClauseMergePolicy {
    fun merge(
        plannerClauses: List<PersonClause>,
        detectedClauses: List<PersonClause>,
        reviewedGroups: List<ReviewedPersonMatchGroup>,
        resolveReviewedIds: (String) -> Set<String>,
    ): List<PersonClause> {
        val firstPersonDetected = detectedClauses.any { clause ->
            clause.mustBePresent && PeopleQueryReferenceDetector.canonicalReference(clause.personId) == "me"
        }
        val canonicalPlanner = plannerClauses.flatMap { clause ->
            val resolvedIds = resolveReviewedIds(clause.personId).ifEmpty {
                if (firstPersonDetected && isGenericFirstPersonPlaceholder(clause.personId)) {
                    resolveReviewedIds("me")
                } else {
                    emptySet()
                }
            }
            canonicalize(
                clause = clause,
                resolvedIds = resolvedIds,
                reviewedGroups = reviewedGroups,
                retainUnresolved = clause.hardness == ConstraintStrength.HARD,
            )
        }
        val canonicalDetected = detectedClauses.flatMap { clause ->
            canonicalize(
                clause = clause,
                resolvedIds = resolveReviewedIds(clause.personId),
                reviewedGroups = reviewedGroups,
                retainUnresolved = true,
            )
        }
        val explicit = canonicalPlanner + canonicalDetected
        val explicitIds = explicit.mapTo(linkedSetOf(), PersonClause::personId)
        val combined = explicit + reviewedGroups.flatMap { group ->
            if (group.personIds.any(explicitIds::contains)) {
                emptyList()
            } else {
                group.personIds.map { personId ->
                    PersonClause(personId = personId, alternativeGroup = group.alternativeGroup)
                }
            }
        }
        val strongest = linkedMapOf<Triple<String, Boolean, String?>, PersonClause>()
        combined.forEach { clause ->
            val key = Triple(clause.personId, clause.mustBePresent, clause.alternativeGroup)
            val current = strongest[key]
            if (current == null || current.hardness == ConstraintStrength.SOFT && clause.hardness == ConstraintStrength.HARD) {
                strongest[key] = clause
            }
        }
        return strongest.values.toList()
    }

    private fun isGenericFirstPersonPlaceholder(value: String): Boolean =
        PersonIdentityNormalization.normalize(value) in setOf("user", "self", "myself", "me", "i")

    private fun canonicalize(
        clause: PersonClause,
        resolvedIds: Set<String>,
        reviewedGroups: List<ReviewedPersonMatchGroup>,
        retainUnresolved: Boolean,
    ): List<PersonClause> {
        if (resolvedIds.isEmpty()) return if (retainUnresolved) listOf(clause) else emptyList()
        return resolvedIds.sorted().map { personId ->
            val reviewedGroup = reviewedGroups.firstOrNull { personId in it.personIds }
            clause.copy(
                personId = personId,
                alternativeGroup = clause.alternativeGroup ?: reviewedGroup?.alternativeGroup,
            )
        }
    }
}
