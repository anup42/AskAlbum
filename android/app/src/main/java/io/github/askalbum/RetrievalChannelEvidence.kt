package io.github.anup42.askalbum

/** Keeps channel reports grounded in evidence produced by that channel only. */
internal object RetrievalChannelEvidence {
    fun project(hit: SearchHit, channel: RetrievalChannel): SearchHit? {
        val evidence = hit.evidence.filter { evidenceBelongsTo(it.sourceField, channel) }
        return if (evidence.isEmpty()) null else hit.copy(evidence = evidence)
    }

    private fun evidenceBelongsTo(sourceField: String, channel: RetrievalChannel): Boolean = when (channel) {
        RetrievalChannel.SEMANTIC -> sourceField == "image_text_embedding"
        RetrievalChannel.CAPTION -> sourceField == "semantic_caption" ||
            sourceField == "semantic_caption_candidate_expansion"
        RetrievalChannel.CAPTION_EMBEDDING -> sourceField == "semantic_caption_embedding" ||
            sourceField == "semantic_caption_embedding_context"
        RetrievalChannel.EVENT -> sourceField == "event"
        else -> true
    }
}
