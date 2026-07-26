package com.samsung.agenticgallery

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class GalleryMigration14To15Test {
    private val databaseName = "gallery-migration-14-15"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GalleryRoomDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    @Throws(IOException::class)
    fun migrationAddsReliabilityColumnsWithoutDroppingRows() {
        helper.createDatabase(databaseName, 14).apply {
            execSQL(
                "INSERT INTO media_item(id,filename,title,location,album,tags,description,license,source_url," +
                    "source_kind,media_kind,mime_type,width,height,size_bytes,ocr_text,face_count,index_state," +
                    "index_version,access_state) VALUES('m1','a.jpg','a','','','','','','','MEDIASTORE'," +
                    "'IMAGE','image/jpeg',1,1,1,'',0,'READY','v','ACCESSIBLE')",
            )
            execSQL(
                "INSERT INTO media_index_stage(media_id,stage,status,producer_version,attempt_count,updated_at,error) " +
                    "VALUES('m1','EMBEDDING','COMPLETE','siglip',1,1,NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            databaseName,
            15,
            true,
            GalleryRoomDatabase.MIGRATION_14_15,
        ).use { db ->
            val count = db.query("SELECT COUNT(*) FROM media_item WHERE id='m1'").use {
                it.moveToFirst()
                it.getInt(0)
            }
            val status = db.query(
                "SELECT status FROM media_index_stage WHERE media_id='m1' AND stage='EMBEDDING'",
            ).use {
                it.moveToFirst()
                it.getString(0)
            }
            assertEquals(1, count)
            assertEquals("COMPLETE", status)
        }
    }
}
