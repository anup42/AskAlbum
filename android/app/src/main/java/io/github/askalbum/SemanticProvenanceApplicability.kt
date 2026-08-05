package io.github.anup42.askalbum

/** Applicability values that must not be treated as direct, deterministic media truth. */
internal object SemanticProvenanceApplicability {
    const val GROUP_CONTEXT_ONLY = "GROUP_CONTEXT_ONLY"
    const val LEGACY_GROUP_CONTEXT_ONLY = "LEGACY_GROUP_CONTEXT_ONLY"
    const val LEGACY_UNCORRELATED = "LEGACY_UNCORRELATED"
    const val LEGACY_SCOPE_UNCERTAIN = "LEGACY_SCOPE_UNCERTAIN"
    const val STALE_PERSON_BINDING = "STALE_PERSON_BINDING"
    const val POSSIBLE_INFERENCE = "POSSIBLE_INFERENCE"
    const val SAFE_FOR_EXACT_DUPLICATES = "SAFE_FOR_EXACT_DUPLICATES"
    const val EXACT_DUPLICATE_SHARED = "EXACT_DUPLICATE_SHARED"

    val NON_CONFIRMING = setOf(
        GROUP_CONTEXT_ONLY,
        LEGACY_GROUP_CONTEXT_ONLY,
        LEGACY_SCOPE_UNCERTAIN,
        STALE_PERSON_BINDING,
        POSSIBLE_INFERENCE,
    )

    val CONTEXTUAL = NON_CONFIRMING + LEGACY_UNCORRELATED

    fun isContextual(value: String?): Boolean = value in CONTEXTUAL

    /** Only facts explicitly authored for pixel-equivalent reuse may be propagated. */
    fun isSafeForExactDuplicateSharing(value: String?): Boolean =
        value == SAFE_FOR_EXACT_DUPLICATES

    fun isDirect(
        scope: Any?,
        applicability: String?,
        mediaId: String,
        evidenceMediaId: String,
    ): Boolean {
        if (isContextual(applicability)) return false
        val scopeName = scope?.toString()
        if (scopeName == "EVENT" || scopeName == "VISUAL_GROUP") return false
        if (scopeName == "EXACT_DUPLICATE_GROUP") {
            return applicability == SAFE_FOR_EXACT_DUPLICATES || applicability == EXACT_DUPLICATE_SHARED
        }
        return mediaId == evidenceMediaId
    }
}
