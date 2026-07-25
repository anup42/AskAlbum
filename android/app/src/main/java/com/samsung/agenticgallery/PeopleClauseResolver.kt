package com.samsung.agenticgallery

internal data class PeopleMediaScope(
    val requiredIds: Set<String>?,
    val excludedIds: Set<String>,
)

/** Converts reviewed-person clauses into deterministic media-set operations. */
internal object PeopleClauseResolver {
    fun resolve(
        clauses: List<PersonClause>,
        mediaIdsForPerson: (String) -> Set<String>,
    ): PeopleMediaScope {
        val present = clauses.filter(PersonClause::mustBePresent).map(PersonClause::personId).distinct()
        val absent = clauses.filterNot(PersonClause::mustBePresent).map(PersonClause::personId).distinct()
        val required = present
            .map(mediaIdsForPerson)
            .reduceOrNull { current, next -> current intersect next }
        val excluded = absent.flatMap { mediaIdsForPerson(it) }.toSet()
        return PeopleMediaScope(required, excluded)
    }
}
