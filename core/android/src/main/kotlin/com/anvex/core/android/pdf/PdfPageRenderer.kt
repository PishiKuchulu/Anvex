package com.anvex.core.android.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Renders the first pages of a PDF to JPEG (Phase 3.1). Vision-capable providers read the
 * pages as images, so PDF content reaches the model without a text-extraction library.
 * Each page is rendered and recycled one at a time to bound memory use.
 */
object PdfPageRenderer {

    fun render(context: Context, pdfBytes: ByteArray, maxPages: Int, maxWidthPx: Int): List<ByteArray> {
        val dir = File(context.cacheDir, "pdf-render").apply { mkdirs() }
        val file = File.createTempFile("page-", ".pdf", dir)
        return try {
            file.writeBytes(pdfBytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val count = minOf(renderer.pageCount, maxPages)
                    (0 until count).map { index -> renderPage(renderer, index, maxWidthPx) }
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun renderPage(renderer: PdfRenderer, index: Int, maxWidthPx: Int): ByteArray =
        renderer.openPage(index).use { page ->
            val scale = minOf(1f, maxWidthPx.toFloat() / page.width)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    out.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
}
