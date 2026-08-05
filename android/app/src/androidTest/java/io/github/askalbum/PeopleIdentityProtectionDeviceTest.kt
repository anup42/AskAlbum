package io.github.anup42.askalbum

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeopleIdentityProtectionDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var database: GalleryDatabase? = null

    @Before
    fun prepare() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @After
    fun cleanup() {
        database?.close()
        database = null
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun peopleIdentityAndPersonFactsAreEncryptedAndLegacyRowsAreUpgraded() {
        val store = GalleryDatabase(context, TEST_DATABASE).also { database = it }
        store.seedDemoIfEmpty()
        val mediaId = store.allItems().first().id
        store.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
        store.ensureAutomaticPersonCluster("person_fixture")
        store.saveReviewedPersonCluster("person_fixture", "Alice Example", "partner", listOf("Wife", "Alicia"))
        store.saveVerifiedPersonAttributeFact(
            mediaId = mediaId,
            clusterId = "person_fixture",
            predicate = "wearing",
            value = "red dress",
            confidence = .9f,
            region = listOf(.1f, .1f, .9f, .9f),
            modelVersion = "fixture",
        )

        store.close()
        database = null
        rawIdentity().use { database ->
            database.query("person_cluster", arrayOf("label", "relationship", "aliases"), "id=?", arrayOf("person_fixture"), null, null, null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertFalse(cursor.getString(0).contains("Alice Example"))
                assertFalse(cursor.getString(1).contains("partner"))
                assertFalse(cursor.getString(2).contains("Alicia"))
            }
            database.query("person_attribute_fact", arrayOf("value"), "cluster_id=?", arrayOf("person_fixture"), null, null, null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertFalse(cursor.getString(0).contains("red dress"))
            }
            database.update("person_cluster", ContentValues().apply {
                put("label", "Legacy Alice")
                put("relationship", "sister")
                put("aliases", "[\"LegacyAlias\"]")
            }, "id=?", arrayOf("person_fixture"))
            database.update("sensitive_data_migration", ContentValues().apply { put("version", 3) }, "id=1", null)
        }

        database?.close()
        database = GalleryDatabase(context, TEST_DATABASE)
        val summary = database!!.personClusterSummaries(includeHidden = true).single { it.id == "person_fixture" }
        assertEquals("Legacy Alice", summary.label)
        assertEquals("sister", summary.relationship)
        assertEquals(listOf("LegacyAlias"), summary.aliases)

        rawIdentity().use { raw ->
            raw.query("person_cluster", arrayOf("label", "relationship", "aliases"), "id=?", arrayOf("person_fixture"), null, null, null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertFalse(cursor.getString(0).contains("Legacy Alice"))
                assertFalse(cursor.getString(1).contains("sister"))
                assertFalse(cursor.getString(2).contains("LegacyAlias"))
            }
        }
    }

    @Test
    fun negativeVisualVerificationIsNotStoredAsPositiveEvidence() {
        val store = GalleryDatabase(context, TEST_DATABASE).also { database = it }
        store.seedDemoIfEmpty()
        val mediaId = store.allItems().first().id
        store.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
        store.ensureAutomaticPersonCluster("person_fixture")
        store.saveReviewedPersonCluster("person_fixture", "Alice Example", "partner", emptyList())

        store.saveVerifiedPersonAttributeFact(
            mediaId = mediaId,
            clusterId = "person_fixture",
            predicate = "wearing green hat",
            value = PersonVisualVerdict.VERIFIED_FALSE.name,
            confidence = .9f,
            region = listOf(.1f, .1f, .9f, .9f),
            modelVersion = "fixture",
            verdict = PersonVisualVerdict.VERIFIED_FALSE,
        )

        val fact = store.personVisualFactsForMedia(mediaId).single()
        assertEquals(PersonVisualVerdict.VERIFIED_FALSE, fact.verdict)
        assertFalse(fact.verdict == PersonVisualVerdict.VERIFIED_TRUE)
    }

    private fun rawIdentity(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(TEST_DATABASE).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )

    private companion object {
        const val TEST_DATABASE = "people-identity-protection-test.db"
    }
}
