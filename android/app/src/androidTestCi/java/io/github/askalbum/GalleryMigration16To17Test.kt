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
class GalleryMigration16To17Test {
    private val databaseName = "gallery-migration-16-17"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GalleryRoomDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationPreservesEvidenceAndReclassifiesLegacyGroupClaims() {
        helper.createDatabase(databaseName, 16).apply {
            execSQL(
                "INSERT INTO media_item(id,filename,title,location,album,tags,description,license,source_url," +
                    "source_kind,media_kind,mime_type,width,height,size_bytes,ocr_text,face_count,index_state," +
                    "index_version,access_state) VALUES('m1','a.jpg','a','','','','','','','MEDIASTORE'," +
                    "'IMAGE','image/jpeg',1,1,1,'',0,'READY','v','ACCESSIBLE')",
            )
            execSQL(
                "INSERT INTO person_cluster(id,label,relationship,aliases,reviewed,hidden,created_at,updated_at) " +
                    "VALUES('person_me','Me','Me','[]',1,0,1,1)",
            )
            execSQL(
                "INSERT INTO semantic_caption(id,scope,subject_id,text,confidence,evidence_media_id,applicability," +
                    "model_version,prompt_version,updated_at) VALUES('c1','VISUAL_GROUP','g1','group text',0.9," +
                    "'m1','SAFE_FOR_EXACT_DUPLICATES','fixture','v2',1)",
            )
            execSQL(
                "INSERT INTO semantic_fact(id,scope,subject_id,predicate,value,confidence,evidence_media_id,region," +
                    "applicability,model_version,prompt_version,updated_at) VALUES('f1','MEDIA','m1','scene','home'," +
                    "0.9,'m1',NULL,'EXACT_DUPLICATE_SHARED','fixture','v1',1)",
            )
            close()
        }

        helper.runMigrationsAndValidate(databaseName, 17, true, GalleryRoomDatabase.MIGRATION_16_17).use { db ->
            db.query(
                "SELECT include_in_personal_memory FROM person_cluster WHERE id='person_me'",
            ).use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
            db.query(
                "SELECT applicability,source_type,representative_media_id FROM semantic_caption WHERE id='c1'",
            ).use {
                it.moveToFirst()
                assertEquals("GROUP_CONTEXT_ONLY", it.getString(0))
                assertEquals("LEGACY_VISUAL_GROUP_REPRESENTATIVE", it.getString(1))
                assertEquals("m1", it.getString(2))
            }
            db.query("SELECT scope,applicability FROM semantic_fact WHERE id='f1'").use {
                it.moveToFirst()
                assertEquals("VISUAL_GROUP", it.getString(0))
                assertEquals("LEGACY_GROUP_CONTEXT_ONLY", it.getString(1))
            }
        }
    }
}
