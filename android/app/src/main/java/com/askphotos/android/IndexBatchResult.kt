package com.askphotos.android

internal data class IndexBatchResult(
    val processed: Int,
    val hasMore: Boolean,
    val retryableFailures: Int = 0,
    val permanentFailures: Int = 0,
    val stopped: Boolean = false,
)
