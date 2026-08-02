package io.github.anup42.askalbum

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SeededVideoKeyframeAcceptanceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun videoIsIndexedSearchedWithTimestampEvidenceAndPlayedAtTheMatch() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = InstrumentationRegistry.getArguments().getString("galleryRunId")
        assumeTrue("galleryRunId was not supplied", !runId.isNullOrBlank())
        val context = instrumentation.targetContext
        val repository = (context.applicationContext as AskAlbumApplication).repository
        val deadline = System.currentTimeMillis() + INDEX_TIMEOUT_MS
        var video = repository.allItems().firstOrNull { it.filename == VIDEO_FILENAME }
        while ((video == null || video.indexState != IndexState.READY) && System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
            video = repository.allItems().firstOrNull { it.filename == VIDEO_FILENAME }
        }
        val indexedVideo = requireNotNull(video) { "Seeded video was not imported" }
        assertEquals(IndexState.READY, indexedVideo.indexState)
        assertEquals(MediaKind.VIDEO, indexedVideo.kind)

        val frames = repository.videoKeyframes(indexedVideo.id)
        assertTrue("Expected distinct video scenes", frames.size in 3..VideoKeyframePolicy.MAX_KEYFRAMES)
        assertEquals(frames.map { it.timestampMs }.sorted(), frames.map { it.timestampMs })
        assertTrue(frames.all { frame ->
            val preview = File(frame.previewPath).canonicalFile
            preview.isFile && preview.toPath().startsWith(File(context.filesDir, "video-keyframes").canonicalFile.toPath())
        })
        assertEquals(
            StageStatus.COMPLETE,
            repository.stageRecords(indexedVideo.id).single { it.stage == IndexStage.VIDEO_KEYFRAMES }.status,
        )

        val outcome = repository.search("Find the yellow bicycle in my video")
        assertEquals(MediaScope.VIDEOS, outcome.plan.mediaScope)
        val hit = requireNotNull(outcome.hits.firstOrNull { it.item.id == indexedVideo.id }) {
            "Video keyframe query did not return the seeded video"
        }
        val evidence = requireNotNull(hit.evidence.firstOrNull { it.sourceField == "video_keyframe" }) {
            "Video result had no keyframe evidence"
        }
        assertTrue(requireNotNull(evidence.timestampMs) in 5_000L..13_000L)
        assertTrue(evidence.id in outcome.answer.evidenceIds)

        compose.setContent { MaterialTheme { EvidenceDialog(hit, onDismiss = {}) } }
        compose.onNodeWithTag("play-video-at-match").assertExists().performClick()
        compose.onNodeWithTag("video-playback").assertExists()
        Unit
    }

    private companion object {
        const val VIDEO_FILENAME = "synthetic_video_screen_timeline.mp4"
        const val INDEX_TIMEOUT_MS = 360_000L
    }
}
