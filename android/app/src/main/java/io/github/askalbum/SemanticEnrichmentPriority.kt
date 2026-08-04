package io.github.anup42.askalbum

/** Stable ordering for background semantic work. Lower values run first. */
object SemanticEnrichmentPriority {
    private const val PERSONAL_REASON_PREFIX = "personal_media:"
    const val INTERACTIVE_QUERY = 0
    const val PERSONAL_REQUESTED = 1
    const val PERSONAL_BACKLOG = 2
    const val FREQUENTLY_RETRIEVED = 3
    const val EVENT_REPRESENTATIVE = 4
    const val VISUAL_GROUP_REPRESENTATIVE = 5
    const val OTHER_BACKGROUND = 6

    fun rank(reason: String, userRequested: Boolean): Int = when {
        reason == "query_verification" || reason == "interactive_query_verification" ||
            reason.startsWith("query_verification:") -> INTERACTIVE_QUERY
        reason.startsWith(PERSONAL_REASON_PREFIX) && userRequested -> PERSONAL_REQUESTED
        reason.startsWith(PERSONAL_REASON_PREFIX) -> PERSONAL_BACKLOG
        reason == "frequently_retrieved" || reason.startsWith("frequently_retrieved:") -> FREQUENTLY_RETRIEVED
        reason == "diverse_event_representative" || reason == "event_representative" -> EVENT_REPRESENTATIVE
        reason == "diverse_group_representative" || reason == "group_representative" -> VISUAL_GROUP_REPRESENTATIVE
        else -> OTHER_BACKGROUND
    }

    /** SQL is assembled only from constants; no model-generated SQL reaches this boundary. */
    fun sqlOrderBy(): String =
        "CASE " +
            "WHEN reason IN ('query_verification','interactive_query_verification') " +
            "OR reason LIKE 'query_verification:%' THEN $INTERACTIVE_QUERY " +
            "WHEN reason LIKE '${PERSONAL_REASON_PREFIX}%' AND user_requested=1 THEN $PERSONAL_REQUESTED " +
            "WHEN reason LIKE '${PERSONAL_REASON_PREFIX}%' THEN $PERSONAL_BACKLOG " +
            "WHEN reason='frequently_retrieved' OR reason LIKE 'frequently_retrieved:%' THEN $FREQUENTLY_RETRIEVED " +
            "WHEN reason IN ('diverse_event_representative','event_representative') THEN $EVENT_REPRESENTATIVE " +
            "WHEN reason IN ('diverse_group_representative','group_representative') THEN $VISUAL_GROUP_REPRESENTATIVE " +
            "ELSE $OTHER_BACKGROUND END, next_attempt_at, updated_at, id"
}
