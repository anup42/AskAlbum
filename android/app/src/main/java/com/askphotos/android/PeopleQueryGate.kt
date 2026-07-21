package com.askphotos.android

internal object PeopleQueryGate {
    fun unavailableReason(plan: GalleryQueryPlan, status: PeopleIndexStatus): String? {
        if (plan.peopleClauses.isEmpty()) return null
        if (!status.enabled) {
            return "People search is off. Enable it explicitly in Privacy before creating local face records."
        }
        if (status.identityReadyFaceCount == 0) {
            return "People search needs a compatible local identity-embedding pack and a reviewed cluster. Face detection alone does not prove identity."
        }
        return null
    }
}
