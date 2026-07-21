package com.askphotos.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryRoomMigrationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun removeTestDatabase() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun legacyVersionThreeMigratesWithoutDataLoss() {
        LegacyVersionThreeHelper(context).use { helper ->
            helper.writableDatabase.insertOrThrow("media_item", null, ContentValues().apply {
                put("id", "legacy-media")
                put("filename", "legacy.jpg")
                put("title", "Legacy item")
                put("location", "Legacy trip")
                put("index_version", "legacy-v3")
            })
        }

        val room = GalleryRoomDatabase.open(context, TEST_DATABASE)
        try {
            val database = room.openHelper.writableDatabase
            database.query("SELECT id, title FROM media_item WHERE id='legacy-media'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy-media", cursor.getString(0))
                assertEquals("Legacy item", cursor.getString(1))
            }
            database.query("PRAGMA table_info(media_item)").use { cursor ->
                var idNotNull = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "id") {
                        idNotNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1
                    }
                }
                assertTrue("Room primary key must be explicitly NOT NULL", idNotNull)
            }
            database.query("SELECT COUNT(*) FROM media_index_stage").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            database.query("PRAGMA table_info(media_item)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.containsAll(setOf("perceptual_hash", "blur_score", "exposure_score", "quality_score", "album")))
            }
            database.query("SELECT album FROM media_item WHERE id='legacy-media'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Legacy trip", cursor.getString(0))
            }
            database.query("PRAGMA table_info(ocr_block)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.containsAll(setOf("normalized_text", "language", "page_index", "timestamp_ms")))
            }
            database.query("SELECT COUNT(*) FROM ocr_entity").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            database.query("SELECT enabled, consent_version FROM people_settings WHERE singleton_id=1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
            }
            listOf("face_instance", "person_cluster").forEach { table ->
                database.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
            listOf("query_session", "result_set", "result_set_media").forEach { table ->
                database.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
        } finally {
            room.close()
        }
    }

    private class LegacyVersionThreeHelper(context: Context) :
        SQLiteOpenHelper(context, TEST_DATABASE, null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE media_item (id TEXT PRIMARY KEY, filename TEXT NOT NULL, title TEXT NOT NULL, creator TEXT, location TEXT NOT NULL DEFAULT '', latitude REAL, longitude REAL, tags TEXT NOT NULL DEFAULT '', description TEXT NOT NULL DEFAULT '', license TEXT NOT NULL DEFAULT '', source_url TEXT NOT NULL DEFAULT '', asset_path TEXT, content_uri TEXT, preview_path TEXT, source_kind TEXT NOT NULL DEFAULT 'DEMO_ASSET', media_kind TEXT NOT NULL DEFAULT 'IMAGE', mime_type TEXT NOT NULL DEFAULT 'image/jpeg', captured_at INTEGER, modified_at INTEGER, duration_ms INTEGER, width INTEGER NOT NULL DEFAULT 0, height INTEGER NOT NULL DEFAULT 0, size_bytes INTEGER NOT NULL DEFAULT 0, ocr_text TEXT NOT NULL DEFAULT '', face_count INTEGER NOT NULL DEFAULT 0, index_state TEXT NOT NULL DEFAULT 'READY', index_error TEXT, indexed_at INTEGER, index_version TEXT NOT NULL)")
            db.execSQL("CREATE INDEX media_item_state_idx ON media_item(index_state)")
            db.execSQL("CREATE INDEX media_item_capture_idx ON media_item(captured_at)")
            db.execSQL("CREATE VIRTUAL TABLE media_fts USING fts4(media_id,title,location,tags,description,ocr_text)")
            db.execSQL("CREATE TABLE media_tombstone (stable_id TEXT PRIMARY KEY, content_uri TEXT NOT NULL, deleted_at INTEGER NOT NULL, reason TEXT NOT NULL)")
            db.execSQL("CREATE INDEX media_tombstone_uri_idx ON media_tombstone(content_uri)")
            db.execSQL("CREATE TABLE ocr_block (id INTEGER PRIMARY KEY AUTOINCREMENT, media_id TEXT NOT NULL REFERENCES media_item(id) ON DELETE CASCADE, text TEXT NOT NULL, confidence REAL NOT NULL, left_pos REAL NOT NULL, top_pos REAL NOT NULL, right_pos REAL NOT NULL, bottom_pos REAL NOT NULL)")
            db.execSQL("CREATE INDEX ocr_block_media_idx ON ocr_block(media_id)")
            db.execSQL("CREATE TABLE gallery_event (id INTEGER PRIMARY KEY AUTOINCREMENT, day_start INTEGER NOT NULL UNIQUE, title TEXT NOT NULL, member_count INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE event_media (event_id INTEGER NOT NULL REFERENCES gallery_event(id) ON DELETE CASCADE, media_id TEXT NOT NULL REFERENCES media_item(id) ON DELETE CASCADE, PRIMARY KEY(event_id, media_id))")
            db.execSQL("CREATE TABLE query_turn (id INTEGER PRIMARY KEY AUTOINCREMENT, query TEXT NOT NULL, plan_summary TEXT NOT NULL, result_count INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL, created_at INTEGER NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val TEST_DATABASE = "gallery-room-migration-test.db"
    }
}
