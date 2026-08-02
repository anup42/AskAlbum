package io.github.anup42.askalbum

import android.content.Context

enum class IndexingJob {
    MEDIA_ANALYSIS,
    EMBEDDINGS,
    PEOPLE,
    SEMANTIC_MEMORY,
}

data class IndexingJobControls(
    val mediaAnalysisEnabled: Boolean = true,
    val embeddingsEnabled: Boolean = true,
    val peopleEnabled: Boolean = true,
    val semanticMemoryEnabled: Boolean = true,
) {
    fun isEnabled(job: IndexingJob): Boolean = when (job) {
        IndexingJob.MEDIA_ANALYSIS -> mediaAnalysisEnabled
        IndexingJob.EMBEDDINGS -> embeddingsEnabled
        IndexingJob.PEOPLE -> peopleEnabled
        IndexingJob.SEMANTIC_MEMORY -> semanticMemoryEnabled
    }

    fun withJob(job: IndexingJob, enabled: Boolean): IndexingJobControls = when (job) {
        IndexingJob.MEDIA_ANALYSIS -> copy(mediaAnalysisEnabled = enabled)
        IndexingJob.EMBEDDINGS -> copy(embeddingsEnabled = enabled)
        IndexingJob.PEOPLE -> copy(peopleEnabled = enabled)
        IndexingJob.SEMANTIC_MEMORY -> copy(semanticMemoryEnabled = enabled)
    }
}

class IndexingJobControlsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): IndexingJobControls = IndexingJobControls(
        mediaAnalysisEnabled = preferences.getBoolean(IndexingJob.MEDIA_ANALYSIS.name, true),
        embeddingsEnabled = preferences.getBoolean(IndexingJob.EMBEDDINGS.name, true),
        peopleEnabled = preferences.getBoolean(IndexingJob.PEOPLE.name, true),
        semanticMemoryEnabled = preferences.getBoolean(IndexingJob.SEMANTIC_MEMORY.name, true),
    )

    fun setEnabled(job: IndexingJob, enabled: Boolean): IndexingJobControls {
        check(preferences.edit().putBoolean(job.name, enabled).commit()) {
            "Could not save ${job.name.lowercase()} indexing state"
        }
        return load()
    }

    private companion object {
        const val PREFERENCES = "indexing-job-controls-v1"
    }
}
