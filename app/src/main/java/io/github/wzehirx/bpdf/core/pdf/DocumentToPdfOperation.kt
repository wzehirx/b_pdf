package io.github.wzehirx.bpdf.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

enum class DocumentKind(val extensions: List<String>) {
    DOCX(listOf("docx")),
    XLSX(listOf("xlsx")),
    TEXT(listOf("txt", "text", "log", "md", "csv"));

    companion object {
        /** Old binary formats that cannot be read. */
        private val UNSUPPORTED = setOf("doc", "xls", "odt", "ods", "rtf", "ppt", "pptx")

        /**
         * The kind of [file] from the extension of [displayName]; without a known extension the content
         * decides (a zip with word/document.xml is a .docx, with xl/workbook.xml an .xlsx, anything else text).
         * Throws [DocumentUnreadable] for formats this tool cannot read (.doc, .xls...).
         */
        fun detect(file: File, displayName: String): DocumentKind {
            val ext = displayName.substringAfterLast('.', "").lowercase()
            entries.firstOrNull { ext in it.extensions }?.let { return it }
            if (ext in UNSUPPORTED) throw DocumentUnreadable(displayName)
            try {
                ZipFile(file).use { zip ->
                    if (zip.getEntry("word/document.xml") != null) return DOCX
                    if (zip.getEntry("xl/workbook.xml") != null) return XLSX
                    throw DocumentUnreadable(displayName)
                }
            } catch (_: IOException) {
                return TEXT // not a zip
            }
        }
    }
}

/** What a conversion to PDF produced. */
data class ToPdfStats(val pages: Int, val pictures: Int = 0, val sheets: Int = 0, val truncated: Boolean = false)

/** Decides how the bytes of a .txt are read: UTF-8 (also with a BOM), UTF-16 with a BOM, else windows-1254 (Turkish). */
internal object TextDecoder {
    private val WINDOWS_1254: Charset = Charset.forName("windows-1254")

    /** The charset of [file] and the number of leading BOM bytes to skip. */
    fun charsetOf(file: File): Pair<Charset, Int> {
        val head = file.inputStream().use { s ->
            val b = ByteArray(3)
            b.copyOf(maxOf(0, s.read(b)))
        }
        if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) return Charsets.UTF_8 to 3
        if (head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) return Charsets.UTF_16LE to 2
        if (head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) return Charsets.UTF_16BE to 2
        return (if (isUtf8(file)) Charsets.UTF_8 else WINDOWS_1254) to 0
    }

    /** True when the whole file is well-formed UTF-8 (checked in chunks). */
    private fun isUtf8(file: File): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val inBuf = ByteBuffer.allocate(64 * 1024)
        val outBuf = CharBuffer.allocate(64 * 1024)
        file.inputStream().use { s ->
            var eof = false
            while (!eof) {
                val n = s.read(inBuf.array(), inBuf.position(), inBuf.remaining())
                if (n < 0) eof = true else inBuf.position(inBuf.position() + n)
                inBuf.flip()
                if (decoder.decode(inBuf, outBuf, eof).isError) return false
                outBuf.clear()
                inBuf.compact()
            }
        }
        return true
    }

    /** The lines of [file], read lazily. */
    fun lines(file: File): Sequence<String> = sequence {
        val (charset, skip) = charsetOf(file)
        file.inputStream().buffered().use { raw ->
            raw.skip(skip.toLong())
            raw.reader(charset).buffered().useLines { yieldAll(it) }
        }
    }
}

/**
 * Word (.docx), Excel (.xlsx) and text (.txt) to PDF, offline. Text is laid out with the embedded Unicode
 * font (Turkish letters work) on A4 with margins, word wrapping and page breaks; Excel sheets become grid
 * tables on landscape A4. Pictures in a .docx (inline pictures) are kept; shapes that are not pictures give
 * a short placeholder from their alternative text. Fonts, colours, columns, headers, footnotes, text boxes
 * positions and pictures in a .xlsx are not carried over. The PDF is untagged, but its text is real text.
 */
object DocumentToPdfOperation {
    fun run(
        input: File,
        displayName: String,
        out: File,
        title: String,
        language: String,
        fontLoader: () -> InputStream,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ToPdfStats {
        if (isCancelled()) throw CancellationException("Convert to PDF cancelled")
        val kind = DocumentKind.detect(input, displayName)
        val doc = TextLayout.newDocument(title, language)
        var success = false
        try {
            val font = Fonts.unicode(doc, fontLoader)
            val stats = when (kind) {
                DocumentKind.DOCX -> docx(doc, font, input, displayName, progress, isCancelled)
                DocumentKind.XLSX -> xlsx(doc, font, input, displayName, progress, isCancelled)
                DocumentKind.TEXT -> text(doc, font, input, progress, isCancelled)
            }
            try {
                doc.save(out)
            } catch (e: IOException) {
                throw PdfError.Io(e)
            }
            if (isCancelled()) throw CancellationException("Convert to PDF cancelled")
            progress(1f)
            success = true
            return stats
        } finally {
            runCatching { doc.close() }
            if (!success) out.delete()
        }
    }

    private fun docx(doc: PDDocument, font: PDType0Font, input: File, name: String, progress: (Float) -> Unit, isCancelled: () -> Boolean): ToPdfStats {
        DocxReader(input, name).use { reader ->
            val blocks = reader.blocks(isCancelled)
            val layout = TextLayout(doc, font)
            try {
                blocks.forEachIndexed { i, block ->
                    if (isCancelled()) throw CancellationException("Word to PDF cancelled")
                    layout.add(block)
                    if (i % 20 == 0) progress(0.9f * i / blocks.size)
                }
                layout.finish()
            } catch (t: Throwable) {
                layout.abort()
                throw t
            }
            return ToPdfStats(doc.numberOfPages, pictures = layout.pictures)
        }
    }

    private fun text(doc: PDDocument, font: PDType0Font, input: File, progress: (Float) -> Unit, isCancelled: () -> Boolean): ToPdfStats {
        val layout = TextLayout(doc, font)
        val total = max(1L, input.length())
        var lineNo = 0
        try {
            for (line in TextDecoder.lines(input)) {
                if (lineNo++ % 200 == 0 && isCancelled()) throw CancellationException("Text to PDF cancelled")
                layout.add(TextBlock(line, BlockStyle.BODY, spaceAfter = 0f))
                if (lineNo % 500 == 0) progress(min(0.9f, 0.9f * lineNo * 40f / total))
            }
            layout.finish()
        } catch (t: Throwable) {
            layout.abort()
            throw t
        }
        return ToPdfStats(doc.numberOfPages)
    }

    private fun xlsx(doc: PDDocument, font: PDType0Font, input: File, name: String, progress: (Float) -> Unit, isCancelled: () -> Boolean): ToPdfStats {
        XlsxReader(input, name).use { reader ->
            val sheets = reader.sheets(isCancelled)
            val grid = GridLayout(doc, font)
            try {
                sheets.forEachIndexed { i, sheet ->
                    if (isCancelled()) throw CancellationException("Excel to PDF cancelled")
                    grid.addSheet(sheet.name, sheet.rows, isCancelled)
                    progress(0.9f * (i + 1) / sheets.size)
                }
                grid.finish()
            } catch (t: Throwable) {
                grid.abort()
                throw t
            }
            return ToPdfStats(doc.numberOfPages, sheets = sheets.size, truncated = sheets.any { it.truncated })
        }
    }
}

/**
 * Draws sheets as grid tables on landscape A4: a heading per sheet, a row-number column, columns sized to
 * their content (at most [MAX_COL_WIDTH] wide, text wrapped to at most [MAX_CELL_LINES] lines and cut with
 * an ellipsis), and columns that do not fit the page width continued as another band below.
 */
internal class GridLayout(private val doc: PDDocument, private val font: PDType0Font) {
    companion object {
        const val MAX_COL_WIDTH = 170f
        const val MIN_COL_WIDTH = 30f
        const val MAX_CELL_LINES = 4
        const val SIZE = 9f
        const val PAD = 3f
        const val MARGIN = 36f
    }

    private val pageSize = PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)
    private var stream: PDPageContentStream? = null
    private var y = 0f
    private val lineHeight = SIZE * 1.3f
    private val availW = pageSize.width - 2 * MARGIN

    private fun width(s: String) = font.getStringWidth(s) * SIZE / 1000f

    private fun newPage() {
        stream?.close()
        val page = PDPage(pageSize)
        doc.addPage(page)
        stream = PDPageContentStream(doc, page)
        y = pageSize.height - MARGIN
    }

    private fun heading(text: String) {
        newPage()
        drawHeading(text)
    }

    private fun drawHeading(text: String) {
        val cs = stream!!
        val size = 15f
        cs.setStrokingColor(0f)
        cs.setLineWidth(size * 0.025f)
        cs.setRenderingMode(com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.FILL_STROKE)
        cs.beginText()
        cs.setFont(font, size)
        cs.newLineAtOffset(MARGIN, y - size)
        cs.showText(font.drawable(text).ifEmpty { " " })
        cs.endText()
        cs.setRenderingMode(com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.FILL)
        y -= size * 1.6f + 6f
    }

    fun addSheet(name: String, rows: List<List<String>>, isCancelled: () -> Boolean) {
        heading(name)
        if (rows.isEmpty()) return
        val cols = rows.maxOf { it.size }.coerceAtLeast(1)
        val numberWidth = width(rows.size.toString()) + 2 * PAD
        val widths = FloatArray(cols) { c ->
            val longest = rows.maxOf { r -> r.getOrNull(c)?.let { cell -> longestLine(cell) } ?: 0f }
            (longest + 2 * PAD).coerceIn(MIN_COL_WIDTH, MAX_COL_WIDTH)
        }
        // Bands of consecutive columns that fit the page width together with the row-number column.
        val bands = ArrayList<IntRange>()
        var start = 0
        var used = numberWidth
        for (c in 0 until cols) {
            if (c > start && used + widths[c] > availW) {
                bands += start until c
                start = c
                used = numberWidth
            }
            used += widths[c]
        }
        bands += start until cols
        bands.forEachIndexed { b, band ->
            if (b > 0) {
                newPage()
                drawHeading("$name (${columnLabel(band.first)}-${columnLabel(band.last)})")
            }
            rows.forEachIndexed { r, row ->
                if (r % 50 == 0 && isCancelled()) throw CancellationException("Excel to PDF cancelled")
                val cells = band.map { c -> cellLines(row.getOrNull(c).orEmpty(), widths[c] - 2 * PAD) }
                val lines = max(1, cells.maxOf { it.size })
                val h = lines * lineHeight + 2 * PAD
                if (y - h < MARGIN) {
                    newPage()
                    y = pageSize.height - MARGIN
                }
                drawRow(r + 1, numberWidth, band, widths, cells, h)
                y -= h
            }
        }
    }

    private fun columnLabel(index: Int) = PdfToExcelOperation.columnName(index)

    private fun longestLine(text: String): Float = text.lines().maxOfOrNull { width(font.drawable(it)) } ?: 0f

    private fun cellLines(text: String, maxWidth: Float): List<String> {
        val lines = TextLayout.wrapText(font, text.replace('\r', ' '), SIZE, maxWidth)
        if (lines.size <= MAX_CELL_LINES) return lines
        val kept = lines.take(MAX_CELL_LINES).toMutableList()
        var last = kept.last()
        while (last.isNotEmpty() && width("$last…") > maxWidth) last = last.dropLast(1)
        kept[kept.size - 1] = "$last…"
        return kept
    }

    private fun drawRow(rowNumber: Int, numberWidth: Float, band: IntRange, widths: FloatArray, cells: List<List<String>>, h: Float) {
        val cs = stream!!
        cs.setStrokingColor(0.6f)
        cs.setLineWidth(0.5f)
        var x = MARGIN
        fun cell(width: Float, lines: List<String>, gray: Boolean) {
            cs.addRect(x, y - h, width, h)
            cs.stroke()
            cs.setNonStrokingColor(if (gray) 0.35f else 0f)
            lines.forEachIndexed { i, line ->
                if (line.isEmpty()) return@forEachIndexed
                cs.beginText()
                cs.setFont(font, SIZE)
                cs.newLineAtOffset(x + PAD, y - PAD - SIZE - i * lineHeight)
                cs.showText(line)
                cs.endText()
            }
            x += width
        }
        cell(numberWidth, listOf(rowNumber.toString()), gray = true)
        band.forEachIndexed { i, c -> cell(widths[c], cells[i], gray = false) }
        cs.setNonStrokingColor(0f)
    }

    fun finish() {
        if (stream == null) newPage()
        stream?.close()
        stream = null
    }

    fun abort() {
        runCatching { stream?.close() }
        stream = null
    }
}
