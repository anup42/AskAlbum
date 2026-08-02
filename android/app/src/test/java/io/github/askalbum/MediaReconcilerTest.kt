package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaReconcilerTest {
    @Test
    fun fullCoverageDeletesOnlyMissingCoveredKinds() {
        val image = item("content://media/image/1", MediaKind.IMAGE)
        val video = item("content://media/video/2", MediaKind.VIDEO)
        val snapshot = MediaScanSnapshot(emptyList(), setOf(MediaKind.IMAGE))

        val plan = MediaReconciler.plan(listOf(image, video), snapshot)

        assertEquals(setOf(image.contentUri), plan.deletedUris)
        assertEquals(setOf(video.contentUri), plan.inaccessibleUris)
    }

    @Test
    fun partialAccessNeverTurnsAnUnseenItemIntoDeletion() {
        val image = item("content://media/image/1", MediaKind.IMAGE)
        val snapshot = MediaScanSnapshot(emptyList(), emptySet())

        val plan = MediaReconciler.plan(listOf(image), snapshot)

        assertEquals(emptySet<String>(), plan.deletedUris)
        assertEquals(setOf(image.contentUri), plan.inaccessibleUris)
    }

    @Test
    fun visibleItemsRemainSeenUnderPartialAccess() {
        val image = item("content://media/image/1", MediaKind.IMAGE)
        val snapshot = MediaScanSnapshot(
            listOf(ImportedMedia("id", requireNotNull(image.contentUri), "one.jpg", "image/jpeg", MediaSource.MEDIA_STORE, null, null, null, 1, 1, 1)),
            emptySet(),
        )

        val plan = MediaReconciler.plan(listOf(image), snapshot)

        assertEquals(setOf(image.contentUri), plan.seenUris)
        assertEquals(emptySet<String>(), plan.inaccessibleUris)
        assertEquals(emptySet<String>(), plan.deletedUris)
    }

    private fun item(uri: String, kind: MediaKind) = GalleryItem(
        id = uri.substringAfterLast('/'), filename = "fixture", title = "fixture", creator = null,
        location = "", latitude = null, longitude = null, tags = emptyList(), description = "",
        license = "Personal", sourceUrl = "", assetPath = null, contentUri = uri,
        source = MediaSource.MEDIA_STORE, kind = kind,
    )
}
