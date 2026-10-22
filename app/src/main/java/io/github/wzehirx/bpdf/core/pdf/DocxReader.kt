package io.github.wzehirx.bpdf.core.pdf

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** The input file is not a document of the kind it claims to be (damaged, protected, an old binary format...). */
class DocumentUnreadable(val fileName: String, cause: Throwable? = null) : Exception("Cannot read '$fileName'", cause)

internal fun String.isWordMain() = contains("wordprocessingml") && endsWith("/main")

/**
 * Reads the text of a .docx with XmlPullParser straight from the zip: paragraphs (w:p / w:r / w:t, w:br,
 * w:tab), headings (style Heading 1-3 / Title), bold, list items, tables flattened row by row with " | "
 * and inline pictures (with their size). Floating shapes that are not pictures give their alternative
 * text as a short placeholder, or nothing. Text of tracked deletions is skipped.
 */
internal class DocxReader(file: File, private val displayName: String) : Closeable {
    private val zip: ZipFile = try {
        ZipFile(file)
    } catch (e: IOException) {
        throw DocumentUnreadable(displayName, e)
    }

    /** Bytes of the zip entry [name] (a picture), or null when missing. */
    fun entryBytes(name: String): ByteArray? =
        zip.getEntry(name)?.let { e ->
            if (e.size > OoxmlLimits.MAX_PICTURE_BYTES) return null
            OoxmlLimits.open(zip, e, displayName, OoxmlLimits.MAX_PICTURE_BYTES, xml = false).use { it.readBytes() }
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

    /** styleId -> heading block style, from word/styles.xml (so localized style ids work too). */
    private fun headingStyles(): Map<String, BlockStyle> {
        val map = HashMap<String, BlockStyle>()
        try {
            val p = parser("word/styles.xml") ?: return map
            var id: String? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG || !p.namespace.isWordMain()) continue
                when (p.name) {
                    "style" -> id = attr(p, "styleId")
                    "name" -> {
                        val style = styleOfName(attr(p, "val")?.lowercase())
                        if (id != null && style != null) map[id] = style
                    }
                }
            }
        } catch (_: XmlPullParserException) {
        } catch (_: IOException) {
        }
        return map
    }

    private fun styleOfName(name: String?): BlockStyle? = when (name) {
        "title" -> BlockStyle.TITLE
        "heading 1" -> BlockStyle.H1
        "heading 2" -> BlockStyle.H2
        "heading 3", "heading 4", "heading 5", "heading 6" -> BlockStyle.H3
        else -> null
    }

    private fun styleOfId(id: String?): BlockStyle? = when (id?.lowercase()) {
        "title" -> BlockStyle.TITLE
        "heading1" -> BlockStyle.H1
        "heading2" -> BlockStyle.H2
        "heading3", "heading4", "heading5", "heading6" -> BlockStyle.H3
        else -> null
    }

    /** relationship id -> zip entry of the picture, from word/_rels/document.xml.rels. */
    private fun imageTargets(): Map<String, String> {
        val map = HashMap<String, String>()
        try {
            val p = parser("word/_rels/document.xml.rels") ?: return map
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = attr(p, "Id") ?: continue
                    val target = attr(p, "Target") ?: continue
                    if (attr(p, "TargetMode") == "External") continue
                    map[id] = if (target.startsWith("/")) target.removePrefix("/") else "word/" + target
                }
            }
        } catch (_: XmlPullParserException) {
        } catch (_: IOException) {
        }
        return map
    }

    private class Para {
        val text = StringBuilder()
        var style: BlockStyle? = null
        var list = false
        var boldChars = 0
        var plainChars = 0
        val pictures = ArrayList<PictureBlock>()
        val placeholders = ArrayList<String>()
    }

    private class TableCtx {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
    }

    /** The blocks of the document in order. Throws [DocumentUnreadable] when this is not a .docx. */
    fun blocks(isCancelled: () -> Boolean = { false }): List<LayoutBlock> {
        if (zip.getEntry("word/document.xml") == null) throw DocumentUnreadable(displayName)
        val styles = headingStyles()
        val images = imageTargets()
        val out = ArrayList<LayoutBlock>()
        val tables = ArrayList<TableCtx>()
        var para: Para? = null
        var runBold = false
        var inRun = false
        var fallbackDepth = 0
        var inText = false
        var extentW = 0L
        var extentH = 0L
        var descr: String? = null
        var blip: String? = null
        var drawingDepth = 0

        fun finishPara() {
            val p = para ?: return
            para = null
            val raw = p.text.toString()
            val text = (if (p.list && raw.isNotBlank()) "• " else "") + raw
            if (tables.isNotEmpty()) {
                val cell = tables.last().cell
                if (cell.isNotEmpty() && text.isNotBlank()) cell.append(' ')
                cell.append(text.trim())
                p.placeholders.forEach { cell.append(' ').append(it) }
                return
            }
            val style = p.style ?: BlockStyle.BODY
            val bold = p.boldChars > 0 && p.plainChars == 0
            if (text.isNotBlank() || (p.pictures.isEmpty() && p.placeholders.isEmpty())) {
                out += TextBlock(text, style, bold)
            }
            p.placeholders.forEach { out += TextBlock(it) }
            p.pictures.forEach { out += it }
        }

        val p = parser("word/document.xml") ?: throw DocumentUnreadable(displayName)
        try {
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (isCancelled()) throw java.util.concurrent.CancellationException("Word to PDF cancelled")
                val tag = p.eventType == XmlPullParser.START_TAG || p.eventType == XmlPullParser.END_TAG
                val ns = if (tag) p.namespace.orEmpty() else ""
                when (p.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (p.name == "Fallback") fallbackDepth++
                        if (fallbackDepth > 0) continue
                        if (ns.isWordMain()) when (p.name) {
                            "p" -> { finishPara(); para = Para() }
                            "pStyle" -> para?.let { pp ->
                                val id = attr(p, "val")
                                pp.style = styles[id] ?: styleOfId(id)
                            }
                            "numPr" -> para?.list = true
                            "r" -> { inRun = true; runBold = false }
                            "b" -> if (inRun && para != null) {
                                val v = attr(p, "val")
                                runBold = v == null || v !in setOf("0", "false", "off")
                            }
                            "t" -> inText = true
                            "tab" -> if (inRun) para?.text?.append('\t')
                            "br", "cr" -> if (inRun) para?.text?.append('\n')
                            "noBreakHyphen" -> para?.text?.append('-')
                            "tbl" -> tables += TableCtx()
                            "tr" -> tables.lastOrNull()?.row = ArrayList()
                            "tc" -> tables.lastOrNull()?.cell?.setLength(0)
                        } else when (p.name) {
                            "inline", "anchor" -> if (ns.endsWith("wordprocessingDrawing")) {
                                drawingDepth++
                                extentW = 0; extentH = 0; descr = null; blip = null
                            }
                            "extent" -> if (drawingDepth > 0 && extentW == 0L) {
                                extentW = attr(p, "cx")?.toLongOrNull() ?: 0
                                extentH = attr(p, "cy")?.toLongOrNull() ?: 0
                            }
                            "docPr" -> if (drawingDepth > 0) descr = attr(p, "descr")?.takeIf { it.isNotBlank() } ?: attr(p, "title")
                            "blip" -> if (drawingDepth > 0 && blip == null) blip = attr(p, "embed")
                        }
                    }
                    XmlPullParser.TEXT -> if (fallbackDepth == 0 && inText && para != null) {
                        val pp = para!!
                        val chunk = p.text
                        if (pp.text.length < OoxmlLimits.MAX_TEXT_CHARS) pp.text.append(chunk)
                        val n = chunk.count { !it.isWhitespace() }
                        if (runBold) pp.boldChars += n else pp.plainChars += n
                    }
                    XmlPullParser.END_TAG -> {
                        if (p.name == "Fallback") { fallbackDepth--; continue }
                        if (fallbackDepth > 0) continue
                        if (ns.isWordMain()) when (p.name) {
                            "t" -> inText = false
                            "r" -> inRun = false
                            "p" -> finishPara()
                            "tc" -> tables.lastOrNull()?.let { it.row.add(it.cell.toString()); it.cell.setLength(0) }
                            "tr" -> tables.lastOrNull()?.let { if (it.row.isNotEmpty()) it.rows += it.row; it.row = ArrayList() }
                            "tbl" -> {
                                val t = tables.removeAt(tables.size - 1)
                                val flat = t.rows.map { it.joinToString(" | ") }
                                if (tables.isEmpty()) {
                                    flat.forEach { out += TextBlock(it) }
                                } else {
                                    val parent = tables.last().cell
                                    flat.forEach { if (parent.isNotEmpty()) parent.append(' '); parent.append(it) }
                                }
                            }
                        } else if ((p.name == "inline" || p.name == "anchor") && ns.endsWith("wordprocessingDrawing") && drawingDepth > 0) {
                            drawingDepth--
                            if (drawingDepth == 0) {
                                val target = blip?.let { images[it] }
                                val owner = para
                                if (owner != null && target != null && extentW > 0 && extentH > 0) {
                                    val entry = target
                                    owner.pictures += PictureBlock((extentW / 12700f), (extentH / 12700f)) { entryBytes(entry) }
                                } else if (owner != null && descr != null) {
                                    owner.placeholders += "[$descr]"
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: XmlPullParserException) {
            throw DocumentUnreadable(displayName, e)
        } catch (e: IOException) {
            throw DocumentUnreadable(displayName, e)
        }
        finishPara()
        return out
    }

    override fun close() {
        runCatching { zip.close() }
    }
}
