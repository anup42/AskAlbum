package io.github.anup42.askalbum

internal object OcrChannelCoveragePolicy {
    fun status(required: Boolean, coverage: IndexStageCoverage, modelAvailable: Boolean): ChannelStatus = when {
        !required || coverage.eligibleCount == 0 -> ChannelStatus.NOT_REQUIRED
        coverage.isComplete -> ChannelStatus.SUCCESS
        coverage.coveredCount > 0 -> ChannelStatus.PARTIAL
        !modelAvailable -> ChannelStatus.UNAVAILABLE
        else -> ChannelStatus.PARTIAL
    }

    fun errorCode(status: ChannelStatus): String? = when (status) {
        ChannelStatus.PARTIAL -> "OCR_COVERAGE_PARTIAL"
        ChannelStatus.UNAVAILABLE -> "OCR_MODEL_UNAVAILABLE"
        ChannelStatus.FAILED -> "OCR_SEARCH_FAILED"
        else -> null
    }
}
