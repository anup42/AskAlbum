package com.askphotos.android

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventCompilerTest {
    @Test
    fun combinesContinuousSingaporeMediaAndSplitsTheLaterGoaTrip() {
        val singapore = listOf(
            item("sg-1", "singapore_marina_bay_01_v1.jpg", "2024-03-13T02:00:00Z", "Singapore 2024", 1.2834, 103.8607),
            item("sg-2", "singapore_marina_bay_01_v2.jpg", "2024-03-13T04:00:00Z", "Singapore 2024", 1.2840, 103.8610),
        )
        val goa = item("goa-1", "goa_beach_01.jpg", "2025-01-05T08:00:00Z", "Goa 2025", 15.2993, 74.1240)

        val events = EventCompiler.compile(singapore + goa)

        assertEquals(2, events.size)
        val singaporeEvent = events.single { it.members.any { member -> member.id == "sg-1" } }
        assertEquals(setOf("sg-1", "sg-2"), singaporeEvent.members.map { it.id }.toSet())
        assertTrue(singaporeEvent.title.contains("Singapore", ignoreCase = true))
        assertTrue(singaporeEvent.searchText.contains("marina bay"))
        assertEquals("TRIP", singaporeEvent.eventType)
    }

    @Test
    fun stableIdDoesNotDependOnInputOrder() {
        val first = item("one", "singapore_one.jpg", "2024-03-13T02:00:00Z", "Singapore")
        val second = item("two", "singapore_two.jpg", "2024-03-13T03:00:00Z", "Singapore")

        assertEquals(EventCompiler.compile(listOf(first, second)).single().id, EventCompiler.compile(listOf(second, first)).single().id)
    }

    @Test
    fun localCorrectionsOverrideGroupingAndLabels() {
        val first = item("one", "singapore_one.jpg", "2024-03-13T02:00:00Z", "Singapore")
        val second = item("two", "goa_two.jpg", "2025-01-05T03:00:00Z", "Goa")
        val inferred = EventCompiler.compile(listOf(first, second))
        assertEquals(2, inferred.size)
        assertNotEquals(inferred[0].id, inferred[1].id)

        val corrected = EventCompiler.compile(listOf(first, second), listOf(
            EventCorrectionRecord(operation = EventCorrectionOperation.MERGE, mediaIds = setOf("one", "two"), createdAt = 1),
            EventCorrectionRecord(operation = EventCorrectionOperation.RENAME, mediaIds = setOf("one", "two"), title = "Family holiday", createdAt = 2),
            EventCorrectionRecord(operation = EventCorrectionOperation.LOCATION, mediaIds = setOf("one", "two"), locationName = "User place", createdAt = 3),
        )).single()

        assertEquals("Family holiday", corrected.title)
        assertEquals("User place", corrected.locationName)
        assertTrue(corrected.userCorrected)
        assertEquals(1f, corrected.confidence)
    }

    private fun item(
        id: String,
        filename: String,
        capturedAt: String,
        album: String,
        latitude: Double? = null,
        longitude: Double? = null,
    ) = GalleryItem(
        id = id,
        filename = filename,
        title = filename.substringBeforeLast('.'),
        creator = null,
        location = "",
        album = album,
        latitude = latitude,
        longitude = longitude,
        tags = emptyList(),
        description = "",
        license = "fixture",
        sourceUrl = "",
        assetPath = null,
        capturedAt = Instant.parse(capturedAt).toEpochMilli(),
    )
}
