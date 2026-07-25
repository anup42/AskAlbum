package com.samsung.agenticgallery

/** Prevents a semantic follow-up from retaining every weak vector hit in its parent result set. */
internal object FollowUpRefinementPolicy {
    fun isContextualFollowUp(query: String, state: ConversationSearchState?): Boolean =
        state?.activeResultSetId != null &&
            state.activeResultIds.isNotEmpty() &&
            FollowUpLanguage.isFollowUp(query, activeResultAvailable = true)

    fun corroboratedSemanticIds(
        scoped: Boolean,
        semanticIds: Collection<String>,
        lexicalIds: Collection<String>,
        eventIds: Collection<String>,
    ): Set<String>? {
        if (!scoped || semanticIds.isEmpty()) return null
        val corroboratingIds = lexicalIds.toSet() + eventIds
        if (corroboratingIds.isEmpty()) return null
        return semanticIds.filterTo(linkedSetOf()) { it in corroboratingIds }.ifEmpty { null }
    }
}
