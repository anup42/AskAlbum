package io.github.anup42.askalbum

internal object EventChannelCoveragePolicy {
    fun status(required: Boolean, coverage: IndexStageCoverage): ChannelStatus = when {
        !required -> ChannelStatus.NOT_REQUIRED
        coverage.isComplete -> ChannelStatus.SUCCESS
        coverage.coveredCount > 0 -> ChannelStatus.PARTIAL
        else -> ChannelStatus.UNAVAILABLE
    }
}
