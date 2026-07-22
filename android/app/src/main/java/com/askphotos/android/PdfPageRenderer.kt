package com.askphotos.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal data class RenderedPdfPage(
    val pageIndex: Int,
    val bitmap: Bitmap,
    val previewPath: String,
)

/** Renders a bounded number of PDF pages into app-private files for OCR and evidence display. */
internal class PdfPageRenderer(context: Context) {
    private val appContext = context.applicationContext

    suspend fun render(item: GalleryItem): List<RenderedPdfPage> {
        require(item.kind == MediaKind.PDF) { "Only PDF items can be rendered" }
        val root = File(appContext.filesDir, "pdf-pages").canonicalFile.apply { mkdirs() }
        val directoryName = MessageDigest.getInstance("SHA-256").digest(item.id.toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
        val itemDir = File(root, directoryName).canonicalFile
        require(itemDir.toPath().startsWith(root.toPath())) { "Invalid PDF preview directory" }
        itemDir.mkdirs()
        itemDir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }

        val uri = Uri.parse(requireNotNull(item.contentUri))
        val descriptor = requireNotNull(appContext.contentResolver.openFileDescriptor(uri, "r"))
        return descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                require(renderer.pageCount > 0) { "PDF has no pages" }
                val rendered = mutableListOf<RenderedPdfPage>()
                try {
                    repeat(minOf(renderer.pageCount, MAX_PAGES)) { pageIndex ->
                        coroutineContext.ensureActive()
                        renderer.openPage(pageIndex).use { page ->
                            val scale = minOf(MAX_SCALE, MAX_SIDE / maxOf(page.width, page.height).toFloat())
                            val bitmap = Bitmap.createBitmap(
                                maxOf(1, (page.width * scale).toInt()),
                                maxOf(1, (page.height * scale).toInt()),
                                Bitmap.Config.ARGB_8888,
                            )
                            bitmap.eraseColor(Color.WHITE)
                            try {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val preview = File(itemDir, "page-%03d.jpg".format(pageIndex))
                                preview.outputStream().use { output ->
                                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                                }
                                rendered += RenderedPdfPage(pageIndex, bitmap, preview.absolutePath)
                            } catch (error: Throwable) {
                                bitmap.recycle()
                                throw error
                            }
                        }
                    }
                    rendered
                } catch (error: Throwable) {
                    rendered.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                    throw error
                }
            }
        }
    }

    companion object {
        private const val MAX_PAGES = 64
        private const val MAX_SIDE = 1400f
        private const val MAX_SCALE = 2f
    }
}
