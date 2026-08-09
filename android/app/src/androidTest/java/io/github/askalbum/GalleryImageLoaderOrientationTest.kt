package io.github.anup42.askalbum

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryImageLoaderOrientationTest {
    @Test
    fun verificationDecodeAppliesExifRotation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "orientation-${System.nanoTime()}.jpg")
        try {
            val source = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
            FileOutputStream(file).use { output ->
                check(source.compress(Bitmap.CompressFormat.JPEG, 100, output))
            }
            source.recycle()
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }

            val item = GalleryItem(
                id = "orientation-fixture",
                filename = file.name,
                title = "Orientation fixture",
                creator = null,
                location = "",
                latitude = null,
                longitude = null,
                tags = emptyList(),
                description = "",
                license = "",
                sourceUrl = "",
                assetPath = null,
                previewPath = file.absolutePath,
                source = MediaSource.MEDIA_STORE,
            )
            val bytes = GalleryImageLoader(context).loadJpeg(item)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            checkNotNull(decoded)
            assertEquals(1, decoded.width)
            assertEquals(2, decoded.height)
            decoded.recycle()
        } finally {
            file.delete()
        }
    }
}
