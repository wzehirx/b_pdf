package io.github.wzehirx.bpdf.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.util.Matrix

/** Shared plumbing of the tools that draw text on top of existing pages (watermark, page numbers). */
internal object PageOverlay {
    /** Page size as the reader sees it (after the page's own rotation). */
    class Visual(val width: Float, val height: Float)

    fun visualSize(page: PDPage): Visual {
        val box = page.cropBox
        return if (page.rotation % 180 == 0) Visual(box.width, box.height) else Visual(box.height, box.width)
    }

    /**
     * Appends to [page] a content stream whose coordinates are those of the page as displayed: origin at the
     * bottom-left of the visible area, upright whatever the page's /Rotate is. [draw] receives the stream
     * and the visual size.
     */
    inline fun draw(doc: PDDocument, page: PDPage, draw: (PDPageContentStream, Visual) -> Unit) {
        val box = page.cropBox
        val visual = visualSize(page)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.transform(Matrix.getTranslateInstance(box.lowerLeftX, box.lowerLeftY))
            when (Math.floorMod(page.rotation, 360)) {
                90 -> cs.transform(Matrix(0f, 1f, -1f, 0f, box.width, 0f))
                180 -> cs.transform(Matrix(-1f, 0f, 0f, -1f, box.width, box.height))
                270 -> cs.transform(Matrix(0f, -1f, 1f, 0f, 0f, box.height))
            }
            draw(cs, visual)
        }
    }
}
