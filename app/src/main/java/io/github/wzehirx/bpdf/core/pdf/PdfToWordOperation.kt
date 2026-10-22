package io.github.wzehirx.bpdf.core.pdf

import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * PDF to Word (.docx), written by hand as a minimal OOXML package (no Apache POI). Every page becomes a
 * Heading 1 ("Page N") followed by its paragraphs, so a screen reader in Word can jump from page to page.
 * Depending on [PdfLayout] the embedded pictures are added with alternative text, or every page is added
 * as one picture followed by its text. Fonts, tables and the exact positions of the PDF are not carried
 * over. Pages are processed one at a time; pictures go to a work directory and are zipped at the end.
 */
object PdfToWordOperation {
    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val EMU_PER_PT = 12700L
    private const val EMU_PER_TWIP = 635L
    private const val EMU_PER_PX = 9525L // 96 dpi

    // A4 with 2.5 cm margins, in twips.
    private const val A4_W = 11906
    private const val A4_H = 16838
    private const val MARGIN = 1417

    private class Media(val index: Int, val name: String, val png: Boolean, val widthPx: Int, val heightPx: Int, val relId: String)

    /**
     * Writes [out] from [input]. [title] is the document title, [language] its BCP-47 tag (e.g. "tr-TR").
     * [rasterizer] is needed for [PdfLayout.EXACT_LOOK]. Failure or cancellation leaves no [out].
     * [isCancelled] is polled before every page. Throws [PdfError] for unreadable or protected inputs.
     */
    fun run(
        input: File,
        out: File,
        title: String,
        language: String,
        labels: ConversionLabels = ConversionLabels(),
        layout: PdfLayout = PdfLayout.TEXT_AND_PICTURES,
        rasterizer: PageRasterizer? = null,
        password: String? = null,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ConversionStats {
        require(layout != PdfLayout.EXACT_LOOK || rasterizer != null) { "The exact look needs a page rasterizer" }
        if (isCancelled()) throw CancellationException("PDF to Word cancelled")
        val work = File(out.parentFile, out.name + ".work")
        var success = false
        try {
            PdfContentReader(input, password).use { reader ->
                work.mkdirs()
                val renderer = if (layout == PdfLayout.EXACT_LOOK) ExactPageRenderer(reader, input, work, rasterizer!!) else null
                renderer.use {
                    val stats = try {
                        val body = File(work, "document.xml")
                        val media = ArrayList<Media>()
                        val stats = writeBody(reader, body, work, title, labels, layout, renderer, media, progress, isCancelled)
                        zip(out, body, work, media, title, language)
                        stats
                    } catch (e: IOException) {
                        throw PdfError.Io(e)
                    }
                    if (isCancelled()) throw CancellationException("PDF to Word cancelled")
                    success = true
                    return stats
                }
            }
        } finally {
            work.deleteRecursively()
            if (!success) out.delete()
        }
    }

    private fun writeBody(
        reader: PdfContentReader,
        body: File,
        work: File,
        title: String,
        labels: ConversionLabels,
        layout: PdfLayout,
        renderer: ExactPageRenderer?,
        media: MutableList<Media>,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): ConversionStats {
        val shared = IdentityHashMap<Any, Media>()
        var blank = 0
        var pictures = 0
        var docPrId = 1
        File(work, "media").mkdirs()
        fun addMedia(image: EncodedImage): Media {
            val n = media.size + 1
            val name = "image$n.${image.extension}"
            File(work, "media/$name").writeBytes(image.bytes)
            return Media(n, name, image.png, image.widthPx, image.heightPx, "rId${100 + n}").also { media += it }
        }
        body.outputStream().buffered().use { raw ->
            val w = OutputStreamWriter(raw, Charsets.UTF_8)
            w.write(XML)
            w.write(
                """<w:document xmlns:w="$W_NS" xmlns:r="$OFFICE_REL" """ +
                    """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" """ +
                    """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
                    """xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>""",
            )
            w.write(paragraph(title, "Title"))
            for (page in 0 until reader.pageCount) {
                if (isCancelled()) throw CancellationException("PDF to Word cancelled")
                val (pageW, pageH) = reader.sizePt(page)
                val exact = layout == PdfLayout.EXACT_LOOK
                // The text width and the height left under the heading, in EMU.
                val margin = if (exact) 720 else MARGIN
                val sectionW = if (exact) (pageW * 20).roundToLong().toInt() else A4_W
                val sectionH = if (exact) (pageH * 20).roundToLong().toInt() else A4_H
                val maxW = (sectionW - 2 * margin) * EMU_PER_TWIP
                val maxH = (sectionH - 2 * margin - 900) * EMU_PER_TWIP

                w.write(paragraph(labels.pageHeader(page + 1), "Heading1"))
                if (exact) {
                    val image = renderer!!.render(page)
                    val m = addMedia(image)
                    pictures++
                    val (cx, cy) = fit(image.widthPx * EMU_PER_PX, image.heightPx * EMU_PER_PX, maxW, maxH, enlarge = true)
                    w.write(drawing(m, cx, cy, labels.pageImageAlt(page + 1), docPrId++))
                } else if (layout == PdfLayout.TEXT_AND_PICTURES) {
                    var n = 0
                    for (picture in reader.pictures(page)) {
                        val m = shared[picture.key] ?: reader.encode(picture)?.let { addMedia(it).also { m -> shared[picture.key] = m } } ?: continue
                        n++
                        pictures++
                        val (cx, cy) = fit(m.widthPx * EMU_PER_PX, m.heightPx * EMU_PER_PX, maxW, maxH, enlarge = false)
                        w.write(drawing(m, cx, cy, labels.pictureAlt(n, page + 1), docPrId++))
                    }
                }
                val paragraphs = Paragraphs.split(reader.text(page))
                if (paragraphs.isEmpty()) blank++
                paragraphs.forEach { w.write(paragraph(it, null)) }
                if (exact && page < reader.pageCount - 1) {
                    w.write("""<w:p><w:pPr>${section(sectionW, sectionH, margin)}</w:pPr></w:p>""")
                } else if (exact) {
                    w.write(section(sectionW, sectionH, margin))
                }
                progress((page + 1).toFloat() / reader.pageCount)
            }
            if (layout != PdfLayout.EXACT_LOOK) w.write(section(A4_W, A4_H, MARGIN))
            w.write("</w:body></w:document>")
            w.flush()
        }
        return ConversionStats(reader.pageCount, blank, pictures)
    }

    /** Scales (w, h) to fit in (maxW, maxH), keeping the aspect ratio; a smaller picture is only enlarged if [enlarge]. */
    private fun fit(w: Long, h: Long, maxW: Long, maxH: Long, enlarge: Boolean): Pair<Long, Long> {
        var f = min(maxW.toDouble() / w, maxH.toDouble() / h)
        if (!enlarge) f = min(f, 1.0)
        return (w * f).roundToLong().coerceAtLeast(1) to (h * f).roundToLong().coerceAtLeast(1)
    }

    private fun section(w: Int, h: Int, margin: Int) =
        """<w:sectPr><w:pgSz w:w="$w" w:h="$h"/><w:pgMar w:top="$margin" w:right="$margin" w:bottom="$margin" w:left="$margin" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>"""

    private fun paragraph(text: String, style: String?): String {
        val pPr = if (style == null) "" else """<w:pPr><w:pStyle w:val="$style"/></w:pPr>"""
        return "<w:p>$pPr<w:r><w:t xml:space=\"preserve\">${XmlText.escape(text)}</w:t></w:r></w:p>"
    }

    private fun drawing(m: Media, cx: Long, cy: Long, alt: String, id: Int): String {
        val a = XmlText.escape(alt)
        return """<w:p><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/>""" +
            """<wp:docPr id="$id" name="Picture $id" descr="$a"/>""" +
            """<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect="1"/></wp:cNvGraphicFramePr>""" +
            """<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic>""" +
            """<pic:nvPicPr><pic:cNvPr id="$id" name="${m.name}" descr="$a"/><pic:cNvPicPr/></pic:nvPicPr>""" +
            """<pic:blipFill><a:blip r:embed="${m.relId}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>""" +
            """<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>""" +
            """</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"""
    }

    private fun zip(out: File, body: File, work: File, media: List<Media>, title: String, language: String) {
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.text("[Content_Types].xml", contentTypes(media))
            zip.text("_rels/.rels", ROOT_RELS)
            zip.text("word/_rels/document.xml.rels", documentRels(media))
            zip.text("word/styles.xml", styles(language))
            zip.text("docProps/core.xml", core(title, language))
            zip.putNextEntry(ZipEntry("word/document.xml"))
            body.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            for (m in media) {
                zip.putNextEntry(ZipEntry("word/media/${m.name}"))
                File(work, "media/${m.name}").inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun ZipOutputStream.text(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private const val XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

    private fun contentTypes(media: List<Media>): String {
        val images = buildString {
            if (media.any { it.png }) append("""<Default Extension="png" ContentType="image/png"/>""")
            if (media.any { !it.png }) append("""<Default Extension="jpeg" ContentType="image/jpeg"/>""")
        }
        return XML +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" + images +
            """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
            """<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""" +
            """<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""" +
            """</Types>"""
    }

    private const val ROOT_RELS = XML +
        """<Relationships xmlns="$REL_NS">""" +
        """<Relationship Id="rId1" Type="$OFFICE_REL/officeDocument" Target="word/document.xml"/>""" +
        """<Relationship Id="rId2" Type="$REL_NS/metadata/core-properties" Target="docProps/core.xml"/>""" +
        """</Relationships>"""

    private fun documentRels(media: List<Media>): String = XML +
        """<Relationships xmlns="$REL_NS">""" +
        """<Relationship Id="rId1" Type="$OFFICE_REL/styles" Target="styles.xml"/>""" +
        media.joinToString("") { """<Relationship Id="${it.relId}" Type="$OFFICE_REL/image" Target="media/${it.name}"/>""" } +
        """</Relationships>"""

    private fun styles(language: String): String {
        val lang = XmlText.escape(language)
        return XML +
            """<w:styles xmlns:w="$W_NS">""" +
            """<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri" w:eastAsia="Calibri"/>""" +
            """<w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="$lang" w:eastAsia="$lang" w:bidi="ar-SA"/></w:rPr></w:rPrDefault>""" +
            """<w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>""" +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>""" +
            """<w:pPr><w:spacing w:after="240"/></w:pPr><w:rPr><w:b/><w:sz w:val="48"/><w:szCs w:val="48"/></w:rPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:uiPriority w:val="9"/><w:qFormat/>""" +
            """<w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before="360" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr>""" +
            """<w:rPr><w:b/><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr></w:style>""" +
            """</w:styles>"""
    }

    private fun core(title: String, language: String): String {
        val now = java.time.Instant.now().toString().substringBefore('.').removeSuffix("Z") + "Z"
        return XML +
            """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" """ +
            """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""" +
            """<dc:title>${XmlText.escape(title)}</dc:title><dc:language>${XmlText.escape(language)}</dc:language>""" +
            """<dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created>""" +
            """<dcterms:modified xsi:type="dcterms:W3CDTF">$now</dcterms:modified></cp:coreProperties>"""
    }
}
