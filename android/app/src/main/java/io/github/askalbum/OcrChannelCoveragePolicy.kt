package io.github.anup42.askalbum

data class OcrStoredDataIntegrity(
    val checkedMediaCount: Int = 0,
    val checkedValueCount: Int = 0,
    val corruptMediaCount: Int = 0,
    val corruptValueCount: Int = 0,
)

internal object OcrChannelCoveragePolicy {
    const val PROTECTED_DATA_CORRUPT = "OCR_PROTECTED_DATA_CORRUPT"
    const val PROTECTED_DATA_PARTIAL = "OCR_PROTECTED_DATA_PARTIAL"

    fun status(
        required: Boolean,
        coverage: IndexStageCoverage,
        modelAvailable: Boolean,
        integrity: OcrStoredDataIntegrity = OcrStoredDataIntegrity(),
        requireCompleteIntegrity: Boolean = false,
    ): ChannelStatus = when {
        !required || coverage.eligibleCount == 0 -> ChannelStatus.NOT_REQUIRED
        integrity.corruptValueCount > 0 && (
            requireCompleteIntegrity || integrity.corruptMediaCount >= integrity.checkedMediaCount
            ) -> ChannelStatus.FAILED
        integrity.corruptValueCount > 0 -> ChannelStatus.PARTIAL
        coverage.isComplete -> ChannelStatus.SUCCESS
        coverage.coveredCount > 0 -> ChannelStatus.PARTIAL
        !modelAvailable -> ChannelStatus.UNAVAILABLE
        else -> ChannelStatus.PARTIAL
    }

    fun errorCode(
        status: ChannelStatus,
        integrity: OcrStoredDataIntegrity = OcrStoredDataIntegrity(),
    ): String? = when {
        integrity.corruptValueCount > 0 && status == ChannelStatus.FAILED -> PROTECTED_DATA_CORRUPT
        integrity.corruptValueCount > 0 && status == ChannelStatus.PARTIAL -> PROTECTED_DATA_PARTIAL
        else -> when (status) {
        ChannelStatus.PARTIAL -> "OCR_COVERAGE_PARTIAL"
        ChannelStatus.UNAVAILABLE -> "OCR_MODEL_UNAVAILABLE"
        ChannelStatus.FAILED -> "OCR_SEARCH_FAILED"
        else -> null
        }
    }
}
