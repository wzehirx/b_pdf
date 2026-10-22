package io.github.wzehirx.bpdf.core.pdf

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.MathContext
import java.util.zip.ZipFile

/** One worksheet: [rows] hold the cell texts (rows without cells are empty lists); [truncated] when limits cut it. */
internal class Sheet(val name: String, val rows: List<List<String>>, val truncated: Boolean)

/**
 * Reads the text of an .xlsx with XmlPullParser from the zip: the sheet names, shared strings, inline
 * strings, numbers, booleans and formula results as stored. Dates stay serial numbers (the cell format
 * is not applied). At most [MAX_ROWS] rows and [MAX_COLS] columns of a sheet are read.
 */
internal class XlsxReader(file: File, private val displayName: String) : Closeable {
    companion object {
        const val MAX_ROWS = 5000
        const val MAX_COLS = 40
    }

    private val zip: ZipFile = try {
        ZipFile(file)
    } catch (e: IOException) {
        throw DocumentUnreadable(displayName, e)
    }

    private fun parser(entry: String): XmlPullParser? {
        val e = zip.getEntry(entry) ?: return null
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(OoxmlLimits.open(zip, e, displayName, OoxmlLimits.MAX_PART_BYTES, xml = true), "UTF-8")
        return p
    }

    private fun attr(p: XmlPullParser, local: String): String? {
        for (i in 0 until p.attributeCount) if (p.getAttributeName(i) == local) return p.getAttributeValue(i)
        return null
    }

    private fun sharedStrings(): List<String> {
        val p = parser("xl/sharedStrings.xml") ?: return emptyList()
        val out = ArrayList<String>()
        var current: StringBuilder? = null
        var inT = false
        var phonetic = 0
        var total = 0L
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> current = StringBuilder()
                    "rPh" -> phonetic++
                    "t" -> inT = phonetic == 0
                }
                XmlPullParser.TEXT -> if (inT && total < OoxmlLimits.MAX_TEXT_CHARS) {
                    val chunk = p.text
                    total += chunk.length
                    current?.append(chunk)
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "rPh" -> phonetic--
                    "si" -> { out += current.toString(); current = null }
                }
            }
        }
        return out
    }

    /** (name, entry) of every worksheet in workbook order. */
    private fun sheetEntries(): List<Pair<String, String>> {
        val rels = HashMap<String, String>()
        parser("xl/_rels/workbook.xml.rels")?.let { p ->
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = attr(p, "Id") ?: continue
                    val target = attr(p, "Target") ?: continue
                    rels[id] = if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target
                }
            }
        }
        val out = ArrayList<Pair<String, String>>()
        val p = parser("xl/workbook.xml") ?: throw DocumentUnreadable(displayName)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                val name = attr(p, "name") ?: "Sheet ${out.size + 1}"
                val id = attr(p, "id") ?: continue // r:id
                val entry = rels[id] ?: continue
                out += name to entry
            }
        }
        return out
    }

    /** Column index (0-based) of a cell reference like "BC12". */
    private fun columnOf(ref: String): Int {
        var n = 0
        for (c in ref) {
            if (c !in 'A'..'Z' && c !in 'a'..'z') break
            n = n * 26 + (c.uppercaseChar() - 'A' + 1)
        }
        return n - 1
    }

    private fun number(raw: String): String {
        val t = raw.trim()
        return try {
            val d = BigDecimal(t).round(MathContext(15))
            val s = d.stripTrailingZeros().toPlainString()
            if (s == "-0") "0" else s
        } catch (_: NumberFormatException) {
            t
        }
    }

    /** All worksheets, in workbook order. Throws [DocumentUnreadable] when this is not an .xlsx. */
    fun sheets(isCancelled: () -> Boolean = { false }): List<Sheet> {
        if (zip.getEntry("xl/workbook.xml") == null) throw DocumentUnreadable(displayName)
        try {
            val shared = sharedStrings()
            return sheetEntries().map { (name, entry) ->
                if (isCancelled()) throw java.util.concurrent.CancellationException("Excel to PDF cancelled")
                readSheet(name, entry, shared)
            }
        } catch (e: XmlPullParserException) {
            throw DocumentUnreadable(displayName, e)
        } catch (e: IOException) {
            throw DocumentUnreadable(displayName, e)
        }
    }

    private fun readSheet(name: String, entry: String, shared: List<String>): Sheet {
        val p = parser(entry) ?: return Sheet(name, emptyList(), false)
        val rows = ArrayList<List<String>>()
        var truncated = false
        var row: MutableList<String>? = null
        var type = ""
        var col = 0
        var value: StringBuilder? = null
        var inline: StringBuilder? = null
        var inV = false
        var inT = false
        var phonetic = 0
        var inCell = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        if (rows.size >= MAX_ROWS) { truncated = true; break }
                        // Rows are written without gaps so the table keeps its shape.
                        val r = attr(p, "r")?.toIntOrNull()
                        while (r != null && rows.size < r - 1 && rows.size < MAX_ROWS) rows += emptyList<String>()
                        row = ArrayList()
                    }
                    "c" -> {
                        inCell = true
                        type = attr(p, "t") ?: "n"
                        col = attr(p, "r")?.let(::columnOf) ?: (row?.size ?: 0)
                        value = StringBuilder()
                        inline = StringBuilder()
                    }
                    "v" -> inV = true
                    "rPh" -> phonetic++
                    "t" -> inT = inCell && phonetic == 0
                }
                XmlPullParser.TEXT -> {
                    if (inV) value?.append(p.text)
                    if (inT) inline?.append(p.text)
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "rPh" -> phonetic--
                    "c" -> {
                        inCell = false
                        val raw = value.toString()
                        val text = when (type) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "inlineStr" -> inline.toString()
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            "str", "e" -> raw
                            else -> if (raw.isBlank()) "" else number(raw)
                        }
                        val r = row
                        if (r != null && col in 0 until MAX_COLS) {
                            while (r.size < col) r += ""
                            if (r.size == col) r += text
                        } else if (col >= MAX_COLS) {
                            truncated = true
                        }
                    }
                    "row" -> row?.let {
                        // Trailing empty cells carry no information.
                        while (it.isNotEmpty() && it.last().isEmpty()) it.removeAt(it.size - 1)
                        rows += it
                        row = null
                    }
                }
            }
        }
        while (rows.isNotEmpty() && rows.last().isEmpty()) rows.removeAt(rows.size - 1)
        return Sheet(name, rows, truncated)
    }

    override fun close() {
        runCatching { zip.close() }
    }
}
