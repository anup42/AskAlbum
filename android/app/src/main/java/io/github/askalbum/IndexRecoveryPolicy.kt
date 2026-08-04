package io.github.anup42.askalbum

enum class IndexRecoveryPipeline {
    MEDIA_ANALYSIS,
    EMBEDDING,
    PEOPLE,
    SEMANTIC_MEMORY,
    CAPTION_EMBEDDING,
    ;

    companion object {
        val ALL: Set<IndexRecoveryPipeline> = values().toSet()
    }
}

internal object IndexRecoveryPolicy {
    fun mediaStages(pipelines: Set<IndexRecoveryPipeline>): Set<IndexStage> = buildSet {
        if (IndexRecoveryPipeline.MEDIA_ANALYSIS in pipelines) {
            addAll(
                setOf(
                    IndexStage.THUMBNAIL,
                    IndexStage.VIDEO_KEYFRAMES,
                    IndexStage.OCR,
                    IndexStage.ENRICHMENT,
                ),
            )
        }
        if (IndexRecoveryPipeline.EMBEDDING in pipelines) add(IndexStage.EMBEDDING)
        if (IndexRecoveryPipeline.PEOPLE in pipelines) add(IndexStage.FACES)
    }
}
