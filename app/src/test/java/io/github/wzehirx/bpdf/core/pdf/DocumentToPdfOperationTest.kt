package io.github.wzehirx.bpdf.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class DocumentToPdfOperationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fontLoader = { context.assets.open(Fonts.ASSET_PATH) }

    @Before
    fun init() = PdfBoxInit.ensure(context)

    private fun zip(name: String, parts: Map<String, ByteArray>): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { z ->
            parts.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return f
    }

    private val W = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture""""

    private fun docx(body: String, extra: Map<String, ByteArray> = emptyMap(), styles: String? = null, name: String = "t.docx"): File =
        zip(
            name,
            buildMap {
                put("word/document.xml", """<?xml version="1.0" encoding="UTF-8"?><w:document $W><w:body>$body</w:body></w:document>""".toByteArray())
                if (styles != null) put("word/styles.xml", styles.toByteArray())
                putAll(extra)
            },
        )

    private fun p(text: String, style: String? = null, bold: Boolean = false) =
        "<w:p>" + (if (style != null) """<w:pPr><w:pStyle w:val="$style"/></w:pPr>""" else "") +
            "<w:r>" + (if (bold) "<w:rPr><w:b/></w:rPr>" else "") + """<w:t xml:space="preserve">$text</w:t></w:r></w:p>"""

    private fun convert(input: File, name: String = input.name, isCancelled: () -> Boolean = { false }): Pair<File, ToPdfStats> {
        val out = File(tmp.root, "out-${System.nanoTime()}.pdf")
        val stats = DocumentToPdfOperation.run(input, name, out, "Başlık", "tr", fontLoader, isCancelled = isCancelled)
        return out to stats
    }

    private fun pages(pdf: File) = TestPdfs.text(pdf)
    private fun flat(pdf: File) = pages(pdf).joinToString("\n").replace(Regex("\\s+"), " ")

    // ---- Word ----

    @Test
    fun docx_paragraphsHeadingsTabsBreaksAndTurkish_appearInThePdf() {
        val src = docx(
            p("Şükrü Öğüt İçerik", "Heading1") + p("Gövde metni ığüşöç") +
                """<w:p><w:r><w:t>Sol</w:t><w:tab/><w:t>Sağ</w:t><w:br/><w:t>Alt satır</w:t></w:r></w:p>""" + p("Kalın", bold = true),
        )
        val (pdf, stats) = convert(src)
        assertEquals(1, stats.pages)
        val text = flat(pdf)
        assertTrue(text, text.contains("Şükrü Öğüt İçerik"))
        assertTrue(text, text.contains("Gövde metni ığüşöç"))
        assertTrue(text, text.contains("Sol") && text.contains("Sağ") && text.contains("Alt satır"))
        assertTrue(text, text.contains("Kalın"))
    }

    @Test
    fun docx_setsTitleLanguageAndA4() {
        val (pdf, _) = convert(docx(p("x")))
        PdfLoader.load(pdf).use { doc ->
            assertEquals("Başlık", doc.documentInformation.title)
            assertEquals("tr", doc.documentCatalog.language)
            assertEquals(595f, doc.getPage(0).mediaBox.width, 1f)
            assertEquals(842f, doc.getPage(0).mediaBox.height, 1f)
        }
    }

    @Test
    fun docx_longText_spansSeveralPages_andWrapsLines() {
        val sentence = "Bu uzun bir cümledir ve satır sonunda kaydırılmalıdır. "
        val body = (1..80).joinToString("") { p("Paragraf $it. " + sentence.repeat(6)) }
        val (pdf, stats) = convert(docx(body))
        assertTrue("pages ${stats.pages}", stats.pages >= 3)
        val text = flat(pdf)
        assertTrue(text.contains("Paragraf 1.") && text.contains("Paragraf 80."))
        // Wrapped: no line is wider than the text area.
        PdfLoader.load(pdf).use { doc ->
            assertEquals(stats.pages, doc.numberOfPages)
        }
    }

    @Test
    fun docx_headingsAreFoundByStyleNameToo_andTablesAreFlattened() {
        val styles = """<w:styles $W><w:style w:type="paragraph" w:styleId="Balk1"><w:name w:val="heading 1"/></w:style></w:styles>"""
        val table = "<w:tbl>" +
            "<w:tr><w:tc>" + p("Ad") + "</w:tc><w:tc>" + p("Soyad") + "</w:tc></w:tr>" +
            "<w:tr><w:tc>" + p("Ayşe") + "</w:tc><w:tc>" + p("Yılmaz") + "</w:tc></w:tr></w:tbl>"
        val src = docx(p("Giriş", "Balk1") + table + p("Son"), styles = styles)
        val reader = DocxReader(src, "t.docx")
        val blocks = reader.use { it.blocks() }.filterIsInstance<TextBlock>()
        assertEquals(BlockStyle.H1, blocks.first().style)
        assertEquals(listOf("Giriş", "Ad | Soyad", "Ayşe | Yılmaz", "Son"), blocks.map { it.text })
        assertTrue(flat(convert(src).first).contains("Ayşe | Yılmaz"))
    }

    @Test
    fun docx_boldOnlyWhenWholeParagraphIsBold_andDeletedTextIsSkipped() {
        val src = docx(
            p("Hepsi kalın", bold = true) + p("Düz") +
                """<w:p><w:del><w:r><w:delText>silinmiş</w:delText></w:r></w:del><w:r><w:t>kalan</w:t></w:r></w:p>""",
        )
        val blocks = DocxReader(src, "t.docx").use { it.blocks() }.filterIsInstance<TextBlock>()
        assertTrue(blocks[0].bold)
        assertFalse(blocks[1].bold)
        assertEquals("kalan", blocks[2].text)
    }

    @Test
    fun docx_inlinePicture_isDrawnAsImageXObject_andShapesGivePlaceholderText() {
        val bmp = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        fun drawing(rel: String?, descr: String) =
            """<w:p><w:r><w:drawing><wp:inline><wp:extent cx="1905000" cy="1270000"/><wp:docPr id="1" name="x" descr="$descr"/>""" +
                """<a:graphic><a:graphicData>""" + (if (rel != null) """<pic:pic><pic:blipFill><a:blip r:embed="$rel"/></pic:blipFill></pic:pic>""" else "") +
                """</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"""
        val rels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId5" Type="x/image" Target="media/image1.png"/></Relationships>"""
        val src = docx(
            p("Önce") + drawing("rId5", "Kırmızı kare") + drawing(null, "Akış şeması") + p("Sonra"),
            extra = mapOf("word/media/image1.png" to png, "word/_rels/document.xml.rels" to rels.toByteArray()),
        )
        val (pdf, stats) = convert(src)
        assertEquals(1, stats.pictures)
        PdfLoader.load(pdf).use { doc ->
            val names = doc.getPage(0).resources.xObjectNames.toList()
            assertEquals(1, names.size)
            assertTrue(doc.getPage(0).resources.isImageXObject(names.single()))
        }
        val text = flat(pdf)
        assertTrue(text, text.contains("Önce") && text.contains("Sonra"))
        assertTrue(text, text.contains("[Akış şeması]"))
        assertFalse(text, text.contains("Kırmızı kare"))
    }

    @Test
    fun docx_withDoctype_isUnreadable_soEntitiesCannotExpand() {
        val bomb = File(tmp.root, "bomb.docx")
        ZipOutputStream(bomb.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write(
                """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;">]><w:document $W><w:body><w:p><w:r><w:t>&b;</w:t></w:r></w:p></w:body></w:document>""".toByteArray(),
            )
            z.closeEntry()
        }
        try { convert(bomb); fail() } catch (_: DocumentUnreadable) { }
    }

    @Test
    fun docx_partThatInflatesPastTheLimit_isUnreadable() {
        val big = File(tmp.root, "big.docx")
        ZipOutputStream(big.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write("""<?xml version="1.0"?><w:document $W><w:body><!--""".toByteArray())
            val chunk = ByteArray(1 shl 20) { 'a'.code.toByte() }
            repeat(65) { z.write(chunk) }
            z.write("--></w:body></w:document>".toByteArray())
            z.closeEntry()
        }
        try { convert(big); fail() } catch (_: DocumentUnreadable) { }
    }

    @Test
    fun docx_notAZipOrWithoutDocument_isUnreadable() {
        val garbage = File(tmp.root, "bad.docx").apply { writeText("not a zip") }
        try { convert(garbage); fail() } catch (_: DocumentUnreadable) { }
        val empty = zip("empty.docx", mapOf("foo.txt" to ByteArray(1)))
        try { convert(empty); fail() } catch (_: DocumentUnreadable) { }
        val old = File(tmp.root, "old.doc").apply { writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())) }
        try { convert(old); fail() } catch (_: DocumentUnreadable) { }
        assertTrue(tmp.root.list()!!.none { it.startsWith("out-") })
    }

    // ---- text ----

    @Test
    fun txt_utf8_roundTrips_withTurkishAndBlankLinesAndIndent() {
        val src = File(tmp.root, "a.txt").apply { writeText("﻿Şükrü Öğüt İçerik\n\n    girintili satır\nson", Charsets.UTF_8) }
        val (pdf, _) = convert(src)
        val text = flat(pdf)
        assertTrue(text, text.contains("Şükrü Öğüt İçerik"))
        assertTrue(text, text.contains("girintili satır") && text.contains("son"))
        assertFalse(text.contains("﻿"))
    }

    @Test
    fun txt_windows1254_fallsBackForTurkish() {
        val src = File(tmp.root, "tr.txt").apply { writeBytes("Şükrü Öğüt İçerik ığ".toByteArray(charset("windows-1254"))) }
        assertTrue(flat(convert(src).first).contains("Şükrü Öğüt İçerik ığ"))
    }

    @Test
    fun txt_asciiLater_nonUtf8Byte_isStillDetected() {
        // 100 KB of ASCII, then a windows-1254 byte: the whole file is checked, not only the start.
        val bytes = ByteArray(100_000) { 'a'.code.toByte() } + byteArrayOf(0xFE.toByte())
        val (charset, skip) = TextDecoder.charsetOf(File(tmp.root, "late.txt").apply { writeBytes(bytes) })
        assertEquals("windows-1254", charset.name().lowercase())
        assertEquals(0, skip)
    }

    @Test
    fun txt_utf16Bom_isRead() {
        val src = File(tmp.root, "u16.txt").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "İçerik".toByteArray(Charsets.UTF_16LE)) }
        assertTrue(flat(convert(src).first).contains("İçerik"))
    }

    @Test
    fun txt_longAndWideLines_wrapAndPaginate() {
        val src = File(tmp.root, "long.txt").apply {
            writeText((1..300).joinToString("\n") { "Satır $it " + "uzun ".repeat(40) } + "\n" + "x".repeat(500))
        }
        val (pdf, stats) = convert(src)
        assertTrue(stats.pages >= 5)
        assertTrue(flat(pdf).contains("Satır 300"))
        assertTrue(flat(pdf).replace(" ", "").contains("x".repeat(500)))
    }

    @Test
    fun emptyTxt_givesOneBlankPage() {
        val (pdf, stats) = convert(File(tmp.root, "e.txt").apply { writeText("") })
        assertEquals(1, stats.pages)
        assertEquals("", pages(pdf).single())
    }

    @Test
    fun cancelled_leavesNoOutput() {
        val src = File(tmp.root, "c.txt").apply { writeText((1..1000).joinToString("\n") { "line $it" }) }
        val out = File(tmp.root, "c.pdf")
        try {
            DocumentToPdfOperation.run(src, "c.txt", out, "c", "en", fontLoader, isCancelled = { true })
            fail()
        } catch (_: CancellationException) {
        }
        assertFalse(out.exists())
    }

    // ---- Excel ----

    private fun xlsx(vararg sheets: Pair<String, String>, shared: List<String> = emptyList(), name: String = "t.xlsx"): File {
        val parts = LinkedHashMap<String, ByteArray>()
        val ns = """xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships""""
        parts["xl/workbook.xml"] = ("""<workbook $ns><sheets>""" +
            sheets.mapIndexed { i, s -> """<sheet name="${s.first}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") +
            "</sheets></workbook>").toByteArray()
        parts["xl/_rels/workbook.xml.rels"] = ("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="x/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } +
            "</Relationships>").toByteArray()
        if (shared.isNotEmpty()) {
            parts["xl/sharedStrings.xml"] = ("""<sst $ns>""" + shared.joinToString("") { "<si><t>$it</t></si>" } + "</sst>").toByteArray()
        }
        sheets.forEachIndexed { i, s -> parts["xl/worksheets/sheet${i + 1}.xml"] = """<worksheet $ns><sheetData>${s.second}</sheetData></worksheet>""".toByteArray() }
        return zip(name, parts)
    }

    @Test
    fun xlsx_sharedInlineNumbersBooleans_renderAsGrid_landscape_withSheetHeadings() {
        val sheet1 = """<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="inlineStr"><is><t>Şehir</t></is></c><c r="C1" t="b"><v>1</v></c></row>""" +
            """<row r="2"><c r="A2"><v>0.30000000000000004</v></c><c r="C2"><v>1234567</v></c></row>"""
        val sheet2 = """<row r="1"><c r="A1" t="inlineStr"><is><t>İkinci sayfa</t></is></c></row>"""
        val src = xlsx("Satışlar" to sheet1, "Özet" to sheet2, shared = listOf("Ürün adı"))
        val (pdf, stats) = convert(src)
        assertEquals(2, stats.sheets)
        val text = flat(pdf)
        assertTrue(text, text.contains("Satışlar") && text.contains("Özet"))
        assertTrue(text, text.contains("Ürün adı") && text.contains("Şehir") && text.contains("TRUE"))
        assertTrue(text, text.contains("0.3") && !text.contains("0.30000000000000004") && text.contains("1234567"))
        assertTrue(text, text.contains("İkinci sayfa"))
        PdfLoader.load(pdf).use { doc ->
            assertEquals(2, doc.numberOfPages)
            assertEquals(842f, doc.getPage(0).mediaBox.width, 1f)
            assertEquals(595f, doc.getPage(0).mediaBox.height, 1f)
        }
    }

    @Test
    fun xlsx_gapsKeepColumns_manyColumnsContinueBelow_andLongCellsAreCut() {
        val cells = (0 until 30).joinToString("") { c ->
            val ref = PdfToExcelOperation.columnName(c) + "1"
            """<c r="$ref" t="inlineStr"><is><t>Kolon$c ${"genişlik ".repeat(3)}</t></is></c>"""
        }
        val long = """<row r="2"><c r="C2" t="inlineStr"><is><t>${"çok uzun hücre ".repeat(200)}</t></is></c></row>"""
        val src = xlsx("Geniş" to """<row r="1">$cells</row>$long""")
        val (pdf, stats) = convert(src)
        assertTrue(stats.pages >= 2)
        val text = flat(pdf)
        assertTrue(text.contains("Kolon0") && text.contains("Kolon29"))
        assertTrue(text.contains("…"))
        assertTrue(text, text.contains("Geniş (") && text.contains("-AD)"))
    }

    @Test
    fun xlsx_rowLimit_isReported() {
        val rows = (1..XlsxReader.MAX_ROWS + 10).joinToString("") { """<row r="$it"><c r="A$it"><v>$it</v></c></row>""" }
        val sheets = XlsxReader(xlsx("S" to rows), "t.xlsx").use { it.sheets() }
        assertEquals(XlsxReader.MAX_ROWS, sheets.single().rows.size)
        assertTrue(sheets.single().truncated)
    }

    @Test
    fun xlsx_unreadable_throws() {
        try { convert(File(tmp.root, "bad.xlsx").apply { writeText("no") }); fail() } catch (_: DocumentUnreadable) { }
    }

    @Test
    fun detect_usesExtensionThenContent() {
        val d = docx(p("x"), name = "noext")
        assertEquals(DocumentKind.DOCX, DocumentKind.detect(d, "Report"))
        assertEquals(DocumentKind.XLSX, DocumentKind.detect(xlsx("S" to ""), "Sheet"))
        assertEquals(DocumentKind.TEXT, DocumentKind.detect(File(tmp.root, "z").apply { writeText("hi") }, "notes"))
        assertEquals(DocumentKind.TEXT, DocumentKind.detect(d, "NOTES.TXT"))
    }
}
