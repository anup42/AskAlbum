package io.github.anup42.askalbum

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrIntegrityProtectionDeviceTest {
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
    fun tamperedEligibleOcrEntityIsReportedWithoutExposingItsValue() {
        val store = GalleryDatabase(context, TEST_DATABASE).also { database = it }
        store.seedDemoIfEmpty()
        val mediaId = store.allItems().first().id
        store.close()
        database = null

        val sensitiveData = SensitiveDataAtRest()
        val protectedRaw = sensitiveData.protect("fixture-secret")
        val protectedNormalized = sensitiveData.protect("fixture-secret")
        rawDatabase().use { raw ->
            raw.insertOrThrow("ocr_entity", null, ContentValues().apply {
                put("media_id", mediaId)
                put("entity_type", OcrEntityType.PASSWORD.name)
                put("raw_text", protectedRaw)
                put("normalized_value", protectedNormalized)
                putNull("label")
                put("confidence", .95f)
                put("left_pos", .1f)
                put("top_pos", .1f)
                put("right_pos", .9f)
                put("bottom_pos", .2f)
                put("producer_version", "fixture")
            })
        }

        val validStore = GalleryDatabase(context, TEST_DATABASE).also { database = it }
        val valid = validStore.ocrStoredDataIntegrity(setOf(mediaId), setOf(OcrEntityType.PASSWORD))
        assertEquals(1, valid.checkedMediaCount)
        assertEquals(2, valid.checkedValueCount)
        assertEquals(0, valid.corruptMediaCount)
        assertEquals(0, valid.corruptValueCount)
        validStore.close()
        database = null

        val tamperedRaw = protectedRaw.dropLast(1) + if (protectedRaw.last() == 'A') 'B' else 'A'
        assertTrue(sensitiveData.revealChecked(tamperedRaw).isFailure)
        rawDatabase().use { raw ->
            raw.update(
                "ocr_entity",
                ContentValues().apply { put("raw_text", tamperedRaw) },
                "media_id=? AND entity_type=?",
                arrayOf(mediaId, OcrEntityType.PASSWORD.name),
            )
        }

        val reopened = GalleryDatabase(context, TEST_DATABASE).also { database = it }
        val corrupt = reopened.ocrStoredDataIntegrity(setOf(mediaId), setOf(OcrEntityType.PASSWORD))
        assertEquals(1, corrupt.checkedMediaCount)
        assertEquals(2, corrupt.checkedValueCount)
        assertEquals(1, corrupt.corruptMediaCount)
        assertEquals(1, corrupt.corruptValueCount)
    }

    private fun rawDatabase(): SQLiteDatabase = SQLiteDatabase.openDatabase(
        context.getDatabasePath(TEST_DATABASE).path,
        null,
        SQLiteDatabase.OPEN_READWRITE,
    )

    private companion object {
        const val TEST_DATABASE = "ocr-integrity-protection-test.db"
    }
}
