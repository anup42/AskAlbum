package io.github.anup42.askalbum

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensitiveDataAtRestDeviceTest {
    @Test
    fun keystoreEnvelopeRoundTripsWithoutStoringPlaintext() {
        val box = SensitiveDataAtRest()
        val plain = "fixture-wifi-secret-123"
        val protected = box.protect(plain)

        assertNotEquals(plain, protected)
        assertEquals(plain, box.reveal(protected))
    }
}
