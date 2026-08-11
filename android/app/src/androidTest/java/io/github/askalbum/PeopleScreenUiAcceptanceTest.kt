package io.github.anup42.askalbum

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test

class PeopleScreenUiAcceptanceTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val seededUris = (0 until 10).map { "content://media/external/images/media/${990_000 + it}" }.toSet()

    @After
    fun removeFixturePeopleAndMedia() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AskAlbumApplication
        app.services.galleryDatabase.resetPeopleIndex()
        app.services.galleryDatabase.applyReconciliation(
            MediaReconciliationPlan(
                seenUris = emptySet(),
                inaccessibleUris = emptySet(),
                deletedUris = seededUris,
            ),
        )
    }

    @Test
    fun tagOpenHideAndUnhideUpdateThePeopleScreen() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AskAlbumApplication
        val database = app.services.galleryDatabase
        waitForText("Photos")
        rule.onNodeWithText("Menu").performClick()
        if (!database.peopleIndexStatus().enabled) {
            rule.onNodeWithText("Privacy").performClick()
            rule.onNodeWithTag("enable-people-index").performScrollTo().performClick()
            rule.onNodeWithTag("confirm-enable-people").performClick()
            rule.waitUntil(timeoutMillis = 15_000) { database.peopleIndexStatus().enabled }
            rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitForText("People")
        }

        seedClusters(database)
        rule.onNodeWithText("People").performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            runCatching { rule.onNodeWithTag("person-cluster-$REVIEW_CLUSTER").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithText("Named people").assertIsDisplayed()
        rule.onNodeWithText("To review").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("person-edit-$REVIEW_CLUSTER").performScrollTo().performClick()
        rule.onNodeWithTag("person-name-input").performTextInput("New UI Person")
        rule.onNodeWithTag("person-save").performClick()

        rule.waitUntil(timeoutMillis = 15_000) {
            database.resolveReviewedPersonIds("New UI Person").contains(REVIEW_CLUSTER) &&
                runCatching { rule.onNodeWithText("New UI Person").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithTag("person-cluster-$REVIEW_CLUSTER").performScrollTo().performClick()
        rule.onNodeWithTag("person-cluster-grid").assertIsDisplayed()
        rule.onNodeWithText("5 photos in this local cluster").assertIsDisplayed()

        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(timeoutMillis = 10_000) {
            runCatching { rule.onNodeWithTag("person-visibility-$REVIEW_CLUSTER").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithTag("person-visibility-$REVIEW_CLUSTER").performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                rule.onNodeWithTag("person-visibility-$REVIEW_CLUSTER").assertTextEquals("Unhide")
            }.isSuccess
        }
        rule.onNodeWithTag("person-visibility-$REVIEW_CLUSTER").performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 15_000) {
            database.resolveReviewedPersonIds("New UI Person").contains(REVIEW_CLUSTER) &&
                runCatching {
                    rule.onNodeWithTag("person-visibility-$REVIEW_CLUSTER").assertTextEquals("Hide")
                }.isSuccess
        }
    }

    private fun seedClusters(database: GalleryDatabase) {
        database.ensureStageRows()
        val imported = seededUris.mapIndexed { index, uri ->
            ImportedMedia(
                stableId = "people-ui-media-$index",
                uri = uri,
                displayName = "people-ui-$index.jpg",
                mimeType = "image/jpeg",
                source = MediaSource.MEDIA_STORE,
                capturedAt = 1_800_000_000_000L + index,
                modifiedAt = 1_800_000_000_000L + index,
                durationMs = null,
                width = 1200,
                height = 900,
                sizeBytes = 1_000L,
            )
        }
        database.upsertImported(imported)
        database.ensureAutomaticPersonCluster(NAMED_CLUSTER)
        database.ensureAutomaticPersonCluster(REVIEW_CLUSTER)
        imported.forEachIndexed { index, item ->
            val clusterId = if (index < 5) NAMED_CLUSTER else REVIEW_CLUSTER
            database.completeEmbeddedFaces(
                item.stableId,
                listOf(face(if (index < 5) 0 else 1)),
                listOf(clusterId),
                "people-ui-face-v1",
            )
        }
        database.saveReviewedPersonCluster(
            NAMED_CLUSTER,
            "Existing UI Person",
            "friend",
            listOf("existing-ui-person"),
        )
    }

    private fun face(axis: Int) = FaceInstance(
        bounds = listOf(.1f, .1f, .4f, .5f),
        embedding = FloatArray(FaceModelCatalog.sface.embeddingDimension).also { it[axis] = 1f },
        quality = .9f,
    )

    private fun waitForText(text: String) {
        rule.waitUntil(timeoutMillis = 15_000) {
            runCatching { rule.onNodeWithText(text).fetchSemanticsNode() }.isSuccess
        }
    }

    private companion object {
        const val NAMED_CLUSTER = "people_ui_named"
        const val REVIEW_CLUSTER = "people_ui_review"
    }
}
