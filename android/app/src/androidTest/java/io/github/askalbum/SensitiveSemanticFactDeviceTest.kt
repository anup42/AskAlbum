package io.github.anup42.askalbum

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensitiveSemanticFactDeviceTest {
    @Test
    fun semanticFactWriteAndMigrationKeepPlaintextOutOfSqlite() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "semantic-fact-at-rest-${UUID.randomUUID()}.db"
        var database: GalleryDatabase? = null
        try {
            database = GalleryDatabase(context, name)
            database.seedDemoIfEmpty()
            val mediaId = database.allItems().first().id
            val job = SemanticEnrichmentJobRecord(
                id = UUID.randomUUID().toString(),
                scope = SemanticFactScope.MEDIA,
                subjectId = mediaId,
                representativeMediaId = mediaId,
                reason = "semantic-fact-at-rest",
                status = SemanticEnrichmentStatus.PENDING,
                attemptCount = 0,
                userRequested = false,
            )
            database.replaceSemanticEnrichmentPlan(SemanticEnrichmentPlan(emptyList(), emptyList(), listOf(job)))
            val fact = SemanticFactRecord(
                scope = SemanticFactScope.MEDIA,
                subjectId = mediaId,
                predicate = "scene",
                value = "private semantic scene",
                confidence = .9f,
                evidenceMediaId = mediaId,
                modelVersion = "fixture",
                promptVersion = "fixture-v1",
            )
            database.completeSemanticEnrichment(
                requireNotNull(database.claimSemanticEnrichmentJob(owner = "at-rest-test")),
                listOf(fact),
            )
            database.close()
            database = null

            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { raw ->
                raw.execSQL("UPDATE sensitive_data_migration SET version=6 WHERE id=1")
                raw.execSQL("UPDATE semantic_fact SET value=?", arrayOf("legacy semantic plaintext"))
            }

            database = GalleryDatabase(context, name)
            assertEquals("legacy semantic plaintext", database.allSemanticFacts().single().value)
            database.close()
            database = null
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { raw ->
                raw.rawQuery("SELECT value FROM semantic_fact LIMIT 1", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(cursor.getString(0).startsWith("askalbum:v1:"))
                }
            }
        } finally {
            database?.close()
            context.deleteDatabase(name)
        }
    }
}
