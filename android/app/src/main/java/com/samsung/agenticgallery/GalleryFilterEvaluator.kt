package com.samsung.agenticgallery

/** Exact typed-filter executor. Unknown dates never satisfy a hard time range. */
object GalleryFilterEvaluator {
    fun matches(item: GalleryItem, filter: FilterExpression): Boolean = when (filter) {
        FilterExpression.True -> true
        is FilterExpression.And -> filter.clauses.all { matches(item, it) }
        is FilterExpression.TimeRange -> item.capturedAt != null &&
            (filter.startEpochMs == null || item.capturedAt >= filter.startEpochMs) &&
            (filter.endEpochMs == null || item.capturedAt <= filter.endEpochMs)
        is FilterExpression.MediaKindIs -> item.kind == filter.kind
        is FilterExpression.AlbumIs -> item.album.equals(filter.album.trim(), ignoreCase = true)
    }
}
