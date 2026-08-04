package io.github.anup42.askalbum

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonVerificationImageComposerTest {
    @Test
    fun compositeContainsFullUpperLowerAndFeetRows() {
        val source = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val input = ByteArrayOutputStream().use { output ->
            source.compress(Bitmap.CompressFormat.JPEG, 90, output)
            output.toByteArray()
        }
        source.recycle()

        val output = PersonVerificationImageComposer.compose(
            input,
            listOf(
                PersonVerificationBinding(
                    faceId = "face-1",
                    clusterId = "me-cluster",
                    stableLabel = "P1",
                    identityTerms = setOf("me"),
                    left = 0.25f,
                    top = 0.1f,
                    right = 0.4f,
                    bottom = 0.3f,
                ),
            ),
        )
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(output, 0, output.size))

        assertEquals(600 + 420 * 4, decoded.height)
        decoded.recycle()
    }
}
