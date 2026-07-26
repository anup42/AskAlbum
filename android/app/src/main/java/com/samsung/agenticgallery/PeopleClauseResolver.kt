package com.samsung.agenticgallery

internal data class PeopleMediaScope(
    val requiredIds: Set<String>?,
    val excludedIds: Set<String>,
)

/** Converts reviewed-person clauses into deterministic media-set operations. */
internal object PeopleClauseResolver {
    fun requiredGroups(clauses: List<PersonClause>): List<List<PersonClause>> =
        clauses.filter(PersonClause::mustBePresent)
            .groupBy { it.alternativeGroup ?: "person_${it.personId}" }
            .values
            .toList()

    fun resolve(
        clauses: List<PersonClause>,
        mediaIdsForPerson: (String) -> Set<String>,
    ): PeopleMediaScope {
        val absent = clauses.filterNot(PersonClause::mustBePresent).map(PersonClause::personId).distinct()
        val required = requiredGroups(clauses)
            .map { alternatives ->
                alternatives.flatMap { mediaIdsForPerson(it.personId) }.toSet()
            }
            .reduceOrNull { current, next -> current intersect next }
        val excluded = absent.flatMap { mediaIdsForPerson(it) }.toSet()
        return PeopleMediaScope(required, excluded)
    }
}
