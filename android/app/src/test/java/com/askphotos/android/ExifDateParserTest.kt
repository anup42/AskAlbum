package com.askphotos.android

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExifDateParserTest {
    @Test
    fun explicitOffsetProducesTheCorrectInstant() {
        assertEquals(
            Instant.parse("2024-03-13T02:42:00Z").toEpochMilli(),
            ExifDateParser.parse("2024:03:13 10:42:00", "+08:00", ZoneOffset.UTC),
        )
    }

    @Test
    fun missingOffsetUsesTheSuppliedFallbackZone() {
        assertEquals(
            Instant.parse("2024-03-13T10:42:00Z").toEpochMilli(),
            ExifDateParser.parse("2024:03:13 10:42:00", null, ZoneOffset.UTC),
        )
    }

    @Test
    fun malformedOrImpossibleDatesFailClosed() {
        assertNull(ExifDateParser.parse("2024:02:31 10:42:00", "+00:00"))
        assertNull(ExifDateParser.parse("not-a-date", "+00:00"))
        assertNull(ExifDateParser.parse(null, "+00:00"))
    }
}
