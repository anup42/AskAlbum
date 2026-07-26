package com.samsung.agenticgallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

/** Applies encoded EXIF orientation only at display-decode boundaries. */
internal object ExifBitmapOrientation {
    fun read(opener: () -> InputStream?): Int = runCatching {
        opener()?.use { it.readTransform() } ?: ExifTransform()
    }.getOrDefault(ExifTransform()).packed

    fun decodeSampled(
        opener: () -> InputStream?,
        requestedEdgePx: Int,
        config: Bitmap.Config = Bitmap.Config.RGB_565,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        opener()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val bitmap = opener()?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight, requestedEdgePx)
                    inPreferredConfig = config
                },
            )
        } ?: return null
        return apply(bitmap, read(opener))
    }

    fun apply(bitmap: Bitmap, packedTransform: Int): Bitmap {
        val transform = ExifTransform.unpack(packedTransform)
        if (transform.rotationDegrees == 0 && !transform.flipHorizontal) return bitmap
        val matrix = Matrix().apply {
            setRotate(transform.rotationDegrees.toFloat())
            if (transform.flipHorizontal) postScale(-1f, 1f)
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    private fun InputStream.readTransform(): ExifTransform {
        val orientation = ExifInterface(this).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        return when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransform(flipHorizontal = true)
            ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransform(rotationDegrees = 180)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransform(180, true)
            ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransform(90, true)
            ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransform(rotationDegrees = 90)
            ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransform(270, true)
            ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransform(rotationDegrees = 270)
            else -> ExifTransform()
        }
    }

    private data class ExifTransform(
        val rotationDegrees: Int = 0,
        val flipHorizontal: Boolean = false,
    ) {
        val packed: Int get() = rotationDegrees * 2 + if (flipHorizontal) 1 else 0

        companion object {
            fun unpack(value: Int) = ExifTransform(value / 2, value % 2 == 1)
        }
    }
}
