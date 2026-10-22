package io.github.wzehirx.bpdf.core.pdf

import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PDF to Excel (.xlsx), basic and experimental. One worksheet per page ("Page N"): a worksheet keeps the
 * columns of one page together, and a screen reader can move between pages with the sheet tabs. Every text
 * line is a row; a line is split into cells where there is a tab or a run of two or more spaces. Texts are
 * written as inline strings (no shared strings part). Only text is converted: no pictures, no real table
 * detection, so PDF tables whose columns are separated by a single space land in one cell.
 */
object PdfToExcelOperation {
    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    private const val MAX_CELL_CHARS = 32_000
    private const val MAX_COLUMNS = 16_384
    private val CELL_SPLIT = Regex("""\s*\t\s*| {2,}""")

    fun run(
        input: File,
        out: File,
        title: String,
        pageHeader: (Int) -> String,
        password: String? = null,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ConversionStats {
        if (isCancelled()) throw CancellationException("PDF to Excel cancelled")
        var success = false
        try {
            PdfContentReader(input, password).use { reader ->
                var blank = 0
                try {
                    ZipOutputStream(out.outputStream().buffered()).use { zip ->
                        val count = reader.pageCount.coerceAtLeast(1)
                        zip.text("[Content_Types].xml", contentTypes(count))
                        zip.text("_rels/.rels", rootRels())
                        zip.text("xl/workbook.xml", workbook(count, pageHeader))
                        zip.text("xl/_rels/workbook.xml.rels", workbookRels(count))
                        zip.text("xl/styles.xml", STYLES)
                        zip.text("docProps/core.xml", core(title))
                        if (reader.pageCount == 0) zip.text("xl/worksheets/sheet1.xml", sheet(emptyList()))
                        for (page in 0 until reader.pageCount) {
                            if (isCancelled()) throw CancellationException("PDF to Excel cancelled")
                            val rows = reader.text(page, columns = true).lines()
                                .map { it.trim('\r', '\n').trimEnd() }
                                .filter { it.isNotBlank() }
                                .map { line -> line.trimStart().split(CELL_SPLIT).map { it.trim() }.filter { it.isNotEmpty() } }
                            if (rows.isEmpty()) blank++
                            zip.text("xl/worksheets/sheet${page + 1}.xml", sheet(rows))
                            progress((page + 1).toFloat() / reader.pageCount)
                        }
                    }
                } catch (e: IOException) {
                    throw PdfError.Io(e)
                }
                if (isCancelled()) throw CancellationException("PDF to Excel cancelled")
                success = true
                return ConversionStats(reader.pageCount, blank)
            }
        } finally {
            if (!success) out.delete()
        }
    }

    /** Column letters of the 0-based [index]: A..Z, AA..ZZ, AAA... */
    internal fun columnName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        do {
            sb.append('A' + n % 26)
            n = n / 26 - 1
        } while (n >= 0)
        return sb.reverse().toString()
    }

    private fun sheet(rows: List<List<String>>): String {
        val sb = StringBuilder(XML)
        sb.append("""<worksheet xmlns="$MAIN_NS"><sheetData>""")
        rows.forEachIndexed { r, cells ->
            sb.append("""<row r="${r + 1}">""")
            cells.take(MAX_COLUMNS).forEachIndexed { c, text ->
                val value = XmlText.escape(text.take(MAX_CELL_CHARS))
                sb.append("""<c r="${columnName(c)}${r + 1}" t="inlineStr"><is><t xml:space="preserve">$value</t></is></c>""")
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun ZipOutputStream.text(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes(sheets: Int): String = XML +
        """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
        """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
        """<Default Extension="xml" ContentType="application/xml"/>""" +
        """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
        (1..sheets).joinToString("") {
            """<Override PartName="/xl/worksheets/sheet$it.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
        } +
        """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
        """<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""" +
        """</Types>"""

    private fun rootRels(): String = XML +
        """<Relationships xmlns="$REL_NS">""" +
        """<Relationship Id="rId1" Type="$OFFICE_REL/officeDocument" Target="xl/workbook.xml"/>""" +
        """<Relationship Id="rId2" Type="$REL_NS/metadata/core-properties" Target="docProps/core.xml"/>""" +
        """</Relationships>"""

    private fun workbook(sheets: Int, pageHeader: (Int) -> String): String = XML +
        """<workbook xmlns="$MAIN_NS" xmlns:r="$OFFICE_REL"><sheets>""" +
        (1..sheets).joinToString("") {
            // Sheet names: at most 31 characters, none of : \ / ? * [ ]
            val name = pageHeader(it).replace(Regex("""[:\\/?*\[\]]"""), " ").trim().take(31).ifEmpty { "Sheet$it" }
            """<sheet name="${XmlText.escape(name)}" sheetId="$it" r:id="rId$it"/>"""
        } +
        """</sheets></workbook>"""

    private fun workbookRels(sheets: Int): String = XML +
        """<Relationships xmlns="$REL_NS">""" +
        (1..sheets).joinToString("") {
            """<Relationship Id="rId$it" Type="$OFFICE_REL/worksheet" Target="worksheets/sheet$it.xml"/>"""
        } +
        """<Relationship Id="rId${sheets + 1}" Type="$OFFICE_REL/styles" Target="styles.xml"/>""" +
        """</Relationships>"""

    private const val STYLES = XML +
        """<styleSheet xmlns="$MAIN_NS">""" +
        """<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>""" +
        """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
        """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
        """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
        """<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>""" +
        """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
        """</styleSheet>"""

    private fun core(title: String): String {
        val now = java.time.Instant.now().toString().substringBefore('.').removeSuffix("Z") + "Z"
        return XML +
            """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" """ +
            """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""" +
            """<dc:title>${XmlText.escape(title)}</dc:title>""" +
            """<dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created></cp:coreProperties>"""
    }
}
