package io.github.wzehirx.bpdf.core.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import java.io.IOException
import kotlin.math.min

enum class BlockStyle(val size: Float, val bold: Boolean) {
    TITLE(20f, true),
    H1(17f, true),
    H2(14f, true),
    H3(12.5f, true),
    BODY(11f, false),
}

/** One piece of a document laid out by [TextLayout]. */
sealed interface LayoutBlock

/**
 * A paragraph or heading. [bold] draws body text bold (the bundled font has one weight, so bold is
 * drawn with a thin outline). [spaceAfter] overrides the gap below the block, in points.
 */
data class TextBlock(
    val text: String,
    val style: BlockStyle = BlockStyle.BODY,
    val bold: Boolean = false,
    val spaceAfter: Float? = null,
) : LayoutBlock

/** A picture of [widthPt] x [heightPt] points (scaled down to fit the page); [bytes] is read when it is drawn. */
class PictureBlock(val widthPt: Float, val heightPt: Float, val bytes: () -> ByteArray?) : LayoutBlock

/**
 * Lays text and pictures out on pages of [pageSize] with [margin], word wrapping and page breaks, in the
 * embedded Unicode [font] (so Turkish characters work). Call [add] for each block, then [finish].
 */
class TextLayout internal constructor(
    private val doc: PDDocument,
    private val font: PDType0Font,
    private val pageSize: PDRectangle = PDRectangle.A4,
    private val margin: Float = 56f,
) {
    private var stream: PDPageContentStream? = null
    private var y = 0f
    private val bottom get() = margin
    private val top get() = pageSize.height - margin
    private val width get() = pageSize.width - 2 * margin

    /** Pictures placed so far. */
    var pictures = 0
        private set

    private fun newPage() {
        stream?.close()
        val page = PDPage(pageSize)
        doc.addPage(page)
        stream = PDPageContentStream(doc, page)
        y = top
    }

    private fun ensureRoom(height: Float) {
        if (stream == null || y - height < bottom) newPage()
    }

    fun add(block: LayoutBlock) {
        try {
            when (block) {
                is TextBlock -> text(block)
                is PictureBlock -> picture(block)
            }
        } catch (e: IOException) {
            throw PdfError.Io(e)
        }
    }

    private fun text(block: TextBlock) {
        val size = block.style.size
        val lineHeight = size * 1.35f
        val lines = wrapText(font, block.text, size, width)
        if (lines.isEmpty()) {
            ensureRoom(lineHeight)
            y -= lineHeight * 0.8f
            return
        }
        // A heading is never the last thing on a page: keep it with its first two lines.
        val keep = if (block.style == BlockStyle.BODY) lineHeight else lineHeight * (min(lines.size, 1) + 2)
        ensureRoom(keep)
        val bold = block.bold || block.style.bold
        for (line in lines) {
            ensureRoom(lineHeight)
            val cs = stream!!
            if (bold) {
                cs.setStrokingColor(0f)
                cs.setLineWidth(size * 0.025f)
                cs.setRenderingMode(RenderingMode.FILL_STROKE)
            }
            if (line.isNotEmpty()) {
                cs.beginText()
                cs.setFont(font, size)
                cs.newLineAtOffset(margin, y - size)
                cs.showText(line)
                cs.endText()
            }
            if (bold) cs.setRenderingMode(RenderingMode.FILL)
            y -= lineHeight
        }
        y -= block.spaceAfter ?: when (block.style) {
            BlockStyle.BODY -> 5f
            else -> 4f
        }
    }

    private fun picture(block: PictureBlock) {
        val data = block.bytes() ?: return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        val sample = generateSequence(1) { it * 2 }.first { maxOf(bounds.outWidth, bounds.outHeight) / it <= ImageEncoder.MAX_SIDE }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        try {
            val availH = top - bottom
            val f = min(1f, min(width / block.widthPt, availH / block.heightPt))
            val w = block.widthPt * f
            val h = block.heightPt * f
            ensureRoom(h)
            val image = if (bitmap.hasAlpha()) {
                LosslessFactory.createFromImage(doc, bitmap)
            } else {
                JPEGFactory.createFromImage(doc, bitmap, 0.85f)
            }
            stream!!.drawImage(image, margin, y - h, w, h)
            y -= h + 8f
            pictures++
        } finally {
            bitmap.recycle()
        }
    }

    /** Closes the last page; an empty document gets one blank page. */
    fun finish() {
        if (stream == null) newPage()
        stream?.close()
        stream = null
    }

    fun abort() {
        runCatching { stream?.close() }
        stream = null
    }

    companion object {
        /** A new document that keeps its data in temp files, with title, language and "show title" set. */
        internal fun newDocument(title: String, language: String): PDDocument {
            val doc = PDDocument(MemoryUsageSetting.setupTempFileOnly())
            doc.documentInformation.title = title
            doc.documentCatalog.language = language
            val prefs = COSDictionary()
            prefs.setBoolean(COSName.getPDFName("DisplayDocTitle"), true)
            doc.documentCatalog.cosObject.setItem(COSName.getPDFName("ViewerPreferences"), prefs)
            return doc
        }

        /**
         * Breaks [text] into lines no wider than [maxWidth] at font [size]. Explicit line breaks are kept, a
         * blank line stays a blank line, leading spaces are kept as indentation, tabs become four spaces,
         * characters the font has no glyph for are dropped and a word wider than the line is cut.
         */
        internal fun wrapText(font: PDType0Font, text: String, size: Float, maxWidth: Float): List<String> {
            val out = ArrayList<String>()
            fun width(s: String) = font.getStringWidth(s) * size / 1000f
            for (raw in text.replace("\t", "    ").split('\n')) {
                val line = font.drawable(raw.trimEnd('\r'))
                if (line.isBlank()) {
                    out += ""
                    continue
                }
                val indent = line.takeWhile { it == ' ' }.take(20)
                var current = StringBuilder(indent)
                var hasWord = false
                for (word in line.trim().split(' ').filter { it.isNotEmpty() }) {
                    var w = word
                    while (true) {
                        val candidate = if (hasWord) "$current $w" else "$current$w"
                        if (width(candidate) <= maxWidth) {
                            current = StringBuilder(candidate)
                            hasWord = true
                            break
                        }
                        if (hasWord) {
                            out += current.toString()
                            current = StringBuilder()
                            hasWord = false
                            continue
                        }
                        // A single word wider than the line: cut it.
                        var cut = 0
                        var i = 0
                        while (i < w.length) {
                            val next = i + Character.charCount(w.codePointAt(i))
                            if (width(current.toString() + w.substring(0, next)) > maxWidth) break
                            cut = next
                            i = next
                        }
                        if (cut == 0) cut = Character.charCount(w.codePointAt(0))
                        out += current.toString() + w.substring(0, cut)
                        current = StringBuilder()
                        w = w.substring(cut)
                        if (w.isEmpty()) break
                    }
                }
                if (hasWord || current.isNotEmpty()) out += current.toString()
            }
            // A text that is only a blank line gives no lines at all.
            return if (out.all { it.isEmpty() }) emptyList() else out
        }
    }
}
