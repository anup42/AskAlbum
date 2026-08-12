package io.github.anup42.askalbum

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScopedIndexCoverageDatabaseTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var database: GalleryDatabase? = null

    @Before
    fun prepare() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @After
    fun cleanup() {
        database?.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun aggregatesOnlyRequestedUrisAcrossIndexAndStageStates() {
        val store = GalleryDatabase(context, TEST_DATABASE).also { database = it }
        val first = imported("one", "content://media/external_primary/file/1")
        val second = imported("two", "content://media/external_primary/file/2")
        assertEquals(2, store.upsertImported(listOf(first, second)))
        store.markIndexing(first.stableId, owner = "scoped-coverage-test")

        val firstOnly = store.indexCoverageForContentUris(listOf(first.uri))
        assertEquals(1, firstOnly.mediaCount)
        assertEquals(1, firstOnly.indexStates.getValue(IndexState.INDEXING))
        assertEquals(0, firstOnly.indexStates.getValue(IndexState.PENDING))
        assertEquals(1, firstOnly.stageStatuses.getValue(IndexStage.DISCOVERY).getValue(StageStatus.COMPLETE))
        assertEquals(1, firstOnly.stageStatuses.getValue(IndexStage.THUMBNAIL).getValue(StageStatus.RUNNING))
        assertEquals(1, firstOnly.stageStatuses.getValue(IndexStage.EMBEDDING).getValue(StageStatus.PENDING))
        assertEquals(1, firstOnly.stageStatuses.getValue(IndexStage.FACES).getValue(StageStatus.SKIPPED))

        val both = store.indexCoverageForContentUris(listOf(first.uri, second.uri, second.uri))
        assertEquals(2, both.mediaCount)
        assertEquals(1, both.indexStates.getValue(IndexState.INDEXING))
        assertEquals(1, both.indexStates.getValue(IndexState.PENDING))
        IndexStage.entries.forEach { stage ->
            assertEquals(2, both.stageStatuses.getValue(stage).values.sum())
        }

        store.completeIndex(
            id = first.stableId,
            labels = emptyList(),
            description = "",
            ocrText = "",
            faceCount = 0,
            previewPath = null,
            blocks = emptyList(),
            entities = emptyList(),
            ocrAttempted = false,
            ocrProducerVersion = null,
            visualFeatures = VisualFeatures(1L, 1f, 1f, 1f),
            keyframes = emptyList(),
        )
        val summary = store.summary()
        assertEquals(2, summary.discovered)
        assertEquals(2, summary.metadataReady)
        assertEquals(1, summary.ocrReady)
        assertEquals("A completed media-analysis stage is ready even when it produced no labels", 1, summary.visualLabelsReady)
    }

    @Test
    fun scopedPendingQueriesCannotBeStarvedByNewerUnrelatedRows() {
        val store = GalleryDatabase(context, TEST_DATABASE).also { database = it }
        val target = imported("target", "content://media/external_primary/file/1")
        val unrelated = (2..102).map { index ->
            imported("unrelated-$index", "content://media/external_primary/file/$index").copy(
                modifiedAt = requireNotNull(target.modifiedAt) + index,
            )
        }
        assertEquals(102, store.upsertImported(listOf(target) + unrelated))

        assertEquals(listOf(target.stableId), store.pendingItemsForIds(setOf(target.stableId), 1).map { it.id })
        assertEquals(
            listOf(target.stableId),
            store.embeddingPendingItemsForIds("siglip-test", setOf(target.stableId), 1).map { it.id },
        )
    }

    @Test
    fun missingPersistedVectorRequeuesOnlyItsCompletedScopedEmbedding() {
        val store = GalleryDatabase(context, TEST_DATABASE).also { database = it }
        val target = imported("target", "content://media/external_primary/file/1")
        val unrelated = imported("unrelated", "content://media/external_primary/file/2")
        val producer = "siglip-test"
        assertEquals(2, store.upsertImported(listOf(target, unrelated)))
        store.completeEmbedding(target.stableId, producer)
        store.completeEmbedding(unrelated.stableId, producer)

        assertEquals(
            setOf(target.stableId),
            store.completeAccessibleEmbeddingVectorIds(producer, setOf(target.stableId)),
        )
        assertEquals(1, store.requeueMissingEmbeddingVectors(setOf(target.stableId), producer))
        assertEquals(
            listOf(target.stableId),
            store.embeddingPendingItemsForIds(producer, setOf(target.stableId), 1).map(GalleryItem::id),
        )
        assertEquals(
            emptyList<String>(),
            store.embeddingPendingItemsForIds(producer, setOf(unrelated.stableId), 1).map(GalleryItem::id),
        )
    }

    private fun imported(id: String, uri: String) = ImportedMedia(
        stableId = id,
        uri = uri,
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        source = MediaSource.MEDIA_STORE,
        capturedAt = 1_700_000_000_000L,
        modifiedAt = 1_700_000_000_000L,
        durationMs = null,
        width = 640,
        height = 480,
        sizeBytes = 1_024,
    )

    private companion object {
        const val TEST_DATABASE = "scoped-index-coverage-test.db"
    }
}
