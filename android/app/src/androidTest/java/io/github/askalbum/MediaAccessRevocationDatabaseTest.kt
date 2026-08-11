package io.github.anup42.askalbum

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaAccessRevocationDatabaseTest {
    @Test
    fun revocationHidesOnlyMediaStoreRowsAndPreservesDerivedIndexState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "media-access-revocation-${UUID.randomUUID()}.db"
        val database = GalleryDatabase(context, databaseName)
        try {
            database.seedDemoIfEmpty()
            database.upsertImported(
                listOf(
                    imported("revoked-image", "content://media/external/images/media/91001", "image/jpeg", MediaSource.MEDIA_STORE),
                    imported("revoked-video", "content://media/external/video/media/91002", "video/mp4", MediaSource.MEDIA_STORE),
                    imported("picker-image", "content://picker/selected/91003", "image/jpeg", MediaSource.PHOTO_PICKER),
                ),
            )
            database.ensureStageRows()
            val imageStages = database.stageRecords("revoked-image")
            val videoStages = database.stageRecords("revoked-video")

            assertEquals(2, database.markMediaStoreInaccessible())
            assertEquals(0, database.markMediaStoreInaccessible())

            val retained = database.mediaStoreItemsIncludingInaccessible().associateBy(GalleryItem::id)
            assertEquals(MediaAccessState.INACCESSIBLE, retained.getValue("revoked-image").accessState)
            assertEquals(MediaAccessState.INACCESSIBLE, retained.getValue("revoked-video").accessState)
            assertTrue(database.allItems().any { it.id == "picker-image" && it.accessState == MediaAccessState.ACCESSIBLE })
            assertTrue(database.allItems().any { it.source == MediaSource.DEMO_ASSET })
            assertEquals(imageStages, database.stageRecords("revoked-image"))
            assertEquals(videoStages, database.stageRecords("revoked-video"))
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun imported(
        id: String,
        uri: String,
        mimeType: String,
        source: MediaSource,
    ) = ImportedMedia(
        stableId = id,
        uri = uri,
        displayName = "$id.${if (mimeType.startsWith("video/")) "mp4" else "jpg"}",
        mimeType = mimeType,
        source = source,
        capturedAt = 1_800_000_000_000L,
        modifiedAt = 1_800_000_000_000L,
        durationMs = if (mimeType.startsWith("video/")) 1_000L else null,
        width = 1920,
        height = 1080,
        sizeBytes = 1_024L,
    )
}
