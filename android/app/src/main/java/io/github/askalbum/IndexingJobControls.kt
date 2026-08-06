package io.github.anup42.askalbum

import android.content.Context

enum class IndexingJob {
    MEDIA_ANALYSIS,
    EMBEDDINGS,
    CAPTION_EMBEDDINGS,
    PEOPLE,
    SEMANTIC_MEMORY,
}

data class IndexingJobControls(
    val mediaAnalysisEnabled: Boolean = true,
    val embeddingsEnabled: Boolean = true,
    val captionEmbeddingsEnabled: Boolean = true,
    val peopleEnabled: Boolean = false,
    val semanticMemoryEnabled: Boolean = true,
    val foregroundPaused: Boolean = false,
) {
    fun isEnabled(job: IndexingJob): Boolean = when (job) {
        IndexingJob.MEDIA_ANALYSIS -> mediaAnalysisEnabled
        IndexingJob.EMBEDDINGS -> embeddingsEnabled
        IndexingJob.CAPTION_EMBEDDINGS -> captionEmbeddingsEnabled
        IndexingJob.PEOPLE -> peopleEnabled
        IndexingJob.SEMANTIC_MEMORY -> semanticMemoryEnabled
    }

    fun withJob(job: IndexingJob, enabled: Boolean): IndexingJobControls = when (job) {
        IndexingJob.MEDIA_ANALYSIS -> copy(mediaAnalysisEnabled = enabled)
        IndexingJob.EMBEDDINGS -> copy(embeddingsEnabled = enabled)
        IndexingJob.CAPTION_EMBEDDINGS -> copy(captionEmbeddingsEnabled = enabled)
        IndexingJob.PEOPLE -> copy(peopleEnabled = enabled)
        IndexingJob.SEMANTIC_MEMORY -> copy(semanticMemoryEnabled = enabled)
    }
}

class IndexingJobControlsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): IndexingJobControls = IndexingJobControls(
        mediaAnalysisEnabled = preferences.getBoolean(IndexingJob.MEDIA_ANALYSIS.name, true),
        embeddingsEnabled = preferences.getBoolean(IndexingJob.EMBEDDINGS.name, true),
        captionEmbeddingsEnabled = preferences.getBoolean(IndexingJob.CAPTION_EMBEDDINGS.name, true),
        peopleEnabled = preferences.getBoolean(IndexingJob.PEOPLE.name, false),
        semanticMemoryEnabled = preferences.getBoolean(IndexingJob.SEMANTIC_MEMORY.name, true),
        foregroundPaused = preferences.getBoolean(FOREGROUND_PAUSED, false),
    )

    fun setEnabled(job: IndexingJob, enabled: Boolean): IndexingJobControls {
        check(preferences.edit().putBoolean(job.name, enabled).commit()) {
            "Could not save ${job.name.lowercase()} indexing state"
        }
        return load()
    }

    fun setForegroundPaused(paused: Boolean): IndexingJobControls {
        check(preferences.edit().putBoolean(FOREGROUND_PAUSED, paused).commit()) {
            "Could not save foreground indexing pause state"
        }
        return load()
    }

    private companion object {
        const val PREFERENCES = "indexing-job-controls-v1"
        const val FOREGROUND_PAUSED = "foreground_paused"
    }
}
