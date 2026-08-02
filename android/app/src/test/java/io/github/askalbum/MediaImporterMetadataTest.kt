package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaImporterMetadataTest {
    @Test
    fun protectedLocationDoesNotDiscardValidExifDate() {
        val capturedAt = 1_710_310_520_000L

        val metadata = embeddedMetadata(capturedAt) {
            throw SecurityException("location is redacted")
        }

        assertEquals(capturedAt, metadata?.capturedAt)
        assertNull(metadata?.latitude)
        assertNull(metadata?.longitude)
    }

    @Test
    fun emptyEmbeddedMetadataIsIgnored() {
        assertNull(embeddedMetadata(null) { null })
    }
}
