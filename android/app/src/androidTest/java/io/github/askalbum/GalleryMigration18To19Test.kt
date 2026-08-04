package io.github.anup42.askalbum

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryMigration18To19Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GalleryRoomDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationRepairsEventAndLeavesAmbiguousCopiesUncertain() {
        helper.createDatabase(TEST_DATABASE, 18).apply {
            execSQL(
                "INSERT INTO media_item(id,filename,title,index_version) VALUES(?,?,?,?)",
                arrayOf("media-1", "one.jpg", "One", "test"),
            )
            execSQL(
                "INSERT INTO gallery_event(id,start_time,end_time,title,event_type,member_count,confidence,search_text,producer_version,user_corrected) VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf(7L, 1L, 2L, "Birthday", "MEMORY", 1, 0.9, "birthday", "test", 0),
            )
            execSQL(
                "INSERT INTO semantic_fact(id,scope,subject_id,predicate,value,confidence,evidence_media_id,region,applicability,model_version,prompt_version,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf("event-fact", "MEDIA", "7", "occasion", "birthday", 0.8, "media-1", null, "EXACT_DUPLICATE_SHARED", "test", "v1", 1L),
            )
            execSQL(
                "INSERT INTO semantic_fact(id,scope,subject_id,predicate,value,confidence,evidence_media_id,region,applicability,model_version,prompt_version,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf("ambiguous-fact", "MEDIA", "other-media", "scene", "room", 0.7, "media-1", null, "EXACT_DUPLICATE_SHARED", "test", "v1", 1L),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            19,
            true,
            GalleryRoomDatabase.MIGRATION_18_19,
        ).use { database ->
            database.query("SELECT scope,applicability FROM semantic_fact WHERE id='event-fact'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("EVENT", cursor.getString(0))
                assertEquals("LEGACY_EVENT_CONTEXT_ONLY", cursor.getString(1))
            }
            database.query("SELECT scope,applicability FROM semantic_fact WHERE id='ambiguous-fact'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("MEDIA", cursor.getString(0))
                assertEquals("LEGACY_SCOPE_UNCERTAIN", cursor.getString(1))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "gallery-migration-18-19.db"
    }
}
