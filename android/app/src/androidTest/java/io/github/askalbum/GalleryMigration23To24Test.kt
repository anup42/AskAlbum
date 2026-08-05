package io.github.anup42.askalbum

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
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
class GalleryMigration23To24Test {
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
    fun renamesOnlyLegacyResultSetExactness() {
        helper.createDatabase(TEST_DATABASE, 23).apply {
            insert(
                "result_set",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", "legacy-result")
                    put("session_id", "session")
                    putNull("parent_result_set_id")
                    put("query", "protected-query")
                    put("intent", "COUNT")
                    put("exactness", "COMPLETE_MODEL_SCAN")
                    put("created_at", 1L)
                },
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            24,
            true,
            GalleryRoomDatabase.MIGRATION_23_24,
        ).use { database ->
            database.query("SELECT exactness FROM result_set WHERE id='legacy-result'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("COMPLETE_PREDICATE_SCAN", cursor.getString(0))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "gallery-migration-23-24.db"
    }
}
