package io.github.anup42.askalbum

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryMigration19To20Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GalleryRoomDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationAddsSensitiveDataMarkerWithoutChangingMedia() {
        helper.createDatabase(TEST_DATABASE, 19).apply {
            execSQL(
                "INSERT INTO media_item(id,filename,title,index_version) VALUES(?,?,?,?)",
                arrayOf("media-1", "family.jpg", "Family", "analysis-v1"),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            20,
            true,
            GalleryRoomDatabase.MIGRATION_19_20,
        ).use { database ->
            database.query("SELECT COUNT(*) FROM media_item").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            database.query("PRAGMA table_info(sensitive_data_migration)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.containsAll(setOf("id", "version", "completed_at")))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "gallery-migration-19-20.db"
    }
}
