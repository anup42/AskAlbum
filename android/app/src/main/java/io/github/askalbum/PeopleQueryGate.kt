package io.github.anup42.askalbum

internal object PeopleQueryGate {
    fun unavailableReason(
        plan: GalleryQueryPlan,
        status: PeopleIndexStatus,
        identityReadyFor: (String) -> Boolean = { status.identityReadyFaceCount > 0 },
    ): String? {
        if (plan.peopleClauses.isEmpty()) return null
        if (!status.enabled) {
            return "People search is off. Enable it explicitly in Privacy before creating local face records."
        }
        if (status.reviewedClusterCount == 0) {
            return "People search needs a visible reviewed cluster. Hidden or ignored people cannot unlock identity search."
        }
        if (status.identityReadyFaceCount == 0) {
            return "People search needs a compatible local identity-embedding pack and a reviewed cluster. Face detection alone does not prove identity."
        }
        val requiredGroups = PeopleClauseResolver.requiredGroups(plan.peopleClauses)
        if (requiredGroups.any { group -> group.none { identityReadyFor(it.personId) } }) {
            return "People search is unavailable for a requested identity because its reviewed cluster has no usable local identity embedding. Complete face indexing for that person first."
        }
        if (plan.peopleClauses.any { !it.mustBePresent && !identityReadyFor(it.personId) }) {
            return "People search cannot safely exclude a requested identity until its reviewed cluster has a usable local identity embedding."
        }
        return null
    }
}

internal object PeopleUnavailableCoveragePolicy {
    fun report(eligibleMediaCount: Int): RetrievalChannelReport<SearchHit> {
        return RetrievalChannelReport(
            channel = RetrievalChannel.PEOPLE,
            status = ChannelStatus.UNAVAILABLE,
            eligibleCount = eligibleMediaCount.coerceAtLeast(0),
            indexedCount = 0,
            searchedCount = 0,
            hits = emptyList(),
            errorCode = "REVIEWED_IDENTITY_UNAVAILABLE",
        )
    }
}
