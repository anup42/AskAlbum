package io.github.anup42.askalbum

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryMigration25To26Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GalleryRoomDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun cleanup() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migrationAddsBodyRegionProvenanceAndPreservesPersonFact() {
        helper.createDatabase(TEST_DATABASE, 25).apply {
            insert(
                "person_attribute_fact",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", "person-fact")
                    put("media_id", "media-1")
                    put("cluster_id", "cluster-1")
                    put("predicate", "wearing")
                    put("value", "red dress")
                    put("confidence", 0.9f)
                    put("region", "[0.1,0.1,0.8,0.9]")
                    put("model_version", "model-1")
                    put("updated_at", 123L)
                    put("person_ref", "P1")
                    put("relation", "WEARING")
                    put("category", "CLOTHING")
                    putNull("item_type")
                    put("attributes", "{}")
                    put("body_region", "FULL_BODY")
                    putNull("face_region")
                    put("association_status", "CONFIDENT")
                    put("verdict", "VERIFIED_TRUE")
                    putNull("target_cluster_id")
                    put("prompt_version", "prompt-1")
                    put("generation_id", "generation-1")
                },
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            26,
            true,
            GalleryRoomDatabase.MIGRATION_25_26,
        ).use { database ->
            database.query(
                "SELECT value,generation_id,body_region_version FROM person_attribute_fact WHERE id='person-fact'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("red dress", cursor.getString(0))
                assertEquals("generation-1", cursor.getString(1))
                assertEquals(PersonalSemanticMemoryPolicy.BODY_REGION_VERSION, cursor.getString(2))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "gallery-migration-25-26.db"
    }
}
