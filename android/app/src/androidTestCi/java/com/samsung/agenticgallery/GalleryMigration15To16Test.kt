package com.samsung.agenticgallery

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryMigration15To16Test {
    private val databaseName = "gallery-migration-15-16"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GalleryRoomDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationPreservesFactsAndAddsCaptionStorage() {
        helper.createDatabase(databaseName, 15).apply {
            execSQL(
                "INSERT INTO media_item(id,filename,title,location,album,tags,description,license,source_url," +
                    "source_kind,media_kind,mime_type,width,height,size_bytes,ocr_text,face_count,index_state," +
                    "index_version,access_state) VALUES('m1','a.jpg','a','','','','','','','MEDIASTORE'," +
                    "'IMAGE','image/jpeg',1,1,1,'',0,'READY','v','ACCESSIBLE')",
            )
            execSQL(
                "INSERT INTO semantic_fact(id,scope,subject_id,predicate,value,confidence,evidence_media_id,region," +
                    "applicability,model_version,prompt_version,updated_at) VALUES('f1','MEDIA','m1','scene','home'," +
                    "0.9,'m1',NULL,'EVIDENCE_MEDIA_ONLY','fixture','v1',1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(databaseName, 16, true, GalleryRoomDatabase.MIGRATION_15_16).use { db ->
            val facts = db.query("SELECT COUNT(*) FROM semantic_fact WHERE id='f1'").use {
                it.moveToFirst()
                it.getInt(0)
            }
            val captions = db.query("SELECT COUNT(*) FROM semantic_caption").use {
                it.moveToFirst()
                it.getInt(0)
            }
            assertEquals(1, facts)
            assertEquals(0, captions)
        }
    }
}
