package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexingJobControlsTest {
    @Test
    fun captionVectorsCanBeStoppedWithoutStoppingImageVectors() {
        val controls = IndexingJobControls().withJob(IndexingJob.CAPTION_EMBEDDINGS, false)

        assertFalse(controls.isEnabled(IndexingJob.CAPTION_EMBEDDINGS))
        assertTrue(controls.isEnabled(IndexingJob.EMBEDDINGS))
    }

    @Test
    fun peoplePipelineDefaultsOffUntilExplicitConsentEnablesIt() {
        val controls = IndexingJobControls()

        assertFalse(controls.isEnabled(IndexingJob.PEOPLE))
        assertTrue(controls.withJob(IndexingJob.PEOPLE, true).isEnabled(IndexingJob.PEOPLE))
    }
}
