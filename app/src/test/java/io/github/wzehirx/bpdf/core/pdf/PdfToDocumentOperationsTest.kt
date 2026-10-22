package io.github.wzehirx.bpdf.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Xml
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class PdfToDocumentOperationsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fontLoader = { context.assets.open(Fonts.ASSET_PATH) }
    private val labels = ConversionLabels(pageHeader = { "Sayfa $it" })

    @Before
    fun init() = PdfBoxInit.ensure(context)

    private fun out(name: String) = File(tmp.root, name)

    /** A PDF whose pages hold the given lines (Unicode font, so Turkish works), optionally with a picture. */
    private fun pdf(pages: List<List<String>>, picture: Boolean = false): File {
        val file = File(tmp.root, "src-${System.nanoTime()}.pdf")
        PDDocument().use { doc ->
            val font = Fonts.unicode(doc, fontLoader)
            for (lines in pages) {
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    var y = 760f
                    for (line in lines) {
                        cs.beginText(); cs.setFont(font, 12f); cs.newLineAtOffset(72f, y); cs.showText(line); cs.endText()
                        y -= 20f
                    }
                    if (picture) {
                        val bmp = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
                        cs.drawImage(LosslessFactory.createFromImage(doc, bmp), 72f, 400f, 240f, 160f)
                        bmp.recycle()
                    }
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun entries(file: File): Map<String, ByteArray> =
        ZipFile(file).use { z -> z.entries().asSequence().associate { it.name to z.getInputStream(it).readBytes() } }

    private fun assertWellFormed(name: String, bytes: ByteArray) {
        val p = Xml.newPullParser()
        p.setInput(bytes.inputStream(), "UTF-8")
        try {
            while (p.next() != XmlPullParser.END_DOCUMENT) { /* walk */ }
        } catch (e: Exception) {
            fail("$name is not well-formed XML: $e")
        }
    }

    private fun assertAllXmlWellFormed(parts: Map<String, ByteArray>) =
        parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (n, b) -> assertWellFormed(n, b) }

    private fun text(parts: Map<String, ByteArray>, name: String) = String(parts.getValue(name), Charsets.UTF_8)

    // ---- Word ----

    @Test
    fun word_hasRequiredParts_wellFormedXml_andOneHeadingPerPage() {
        val src = pdf(listOf(listOf("Birinci sayfa."), listOf("Ikinci sayfa.")))
        val stats = PdfToWordOperation.run(src, out("a.docx"), "Kitap", "tr-TR", labels)
        assertEquals(2, stats.pages)
        val parts = entries(out("a.docx"))
        listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/_rels/document.xml.rels", "docProps/core.xml")
            .forEach { assertTrue(it, parts.containsKey(it)) }
        assertAllXmlWellFormed(parts)
        val doc = text(parts, "word/document.xml")
        assertEquals(2, Regex("""<w:pStyle w:val="Heading1"/>""").findAll(doc).count())
        assertTrue(doc.contains("Sayfa 1") && doc.contains("Sayfa 2"))
        assertTrue(text(parts, "word/styles.xml").contains("""w:styleId="Heading1""""))
        assertTrue(text(parts, "docProps/core.xml").contains("<dc:title>Kitap</dc:title>"))
        assertEquals("[Content_Types].xml", ZipFile(out("a.docx")).use { it.entries().nextElement().name })
    }

    @Test
    fun word_turkishText_roundTrips_andMarkupIsEscaped() {
        val src = pdf(listOf(listOf("Şükrü Öğüt İçerik", "a < b & c")))
        PdfToWordOperation.run(src, out("t.docx"), "Şükrü & <Öğüt>", "tr-TR", labels)
        val parts = entries(out("t.docx"))
        assertAllXmlWellFormed(parts)
        val doc = text(parts, "word/document.xml")
        assertTrue(doc, doc.contains("Şükrü Öğüt İçerik"))
        assertTrue(doc, doc.contains("a &lt; b &amp; c"))
        assertTrue(text(parts, "docProps/core.xml").contains("Şükrü &amp; &lt;Öğüt&gt;"))
    }

    @Test
    fun word_embeddedPicture_isKeptWithAltText() {
        val src = pdf(listOf(listOf("Resimli sayfa.")), picture = true)
        val stats = PdfToWordOperation.run(src, out("p.docx"), "Resimli", "tr-TR", labels)
        assertEquals(1, stats.pictures)
        val parts = entries(out("p.docx"))
        assertAllXmlWellFormed(parts)
        assertTrue(parts.keys.any { it.startsWith("word/media/image1.") })
        val doc = text(parts, "word/document.xml")
        assertTrue(doc.contains("<w:drawing>"))
        assertTrue(doc, doc.contains("""descr="Picture 1 on page 1""""))
        val rels = text(parts, "word/_rels/document.xml.rels")
        assertTrue(rels, rels.contains("""Target="media/image1."""))
        val rid = Regex("""r:embed="([^"]+)"""").find(doc)!!.groupValues[1]
        assertTrue(rels.contains("""Id="$rid""""))
        val types = text(parts, "[Content_Types].xml")
        assertTrue(types, types.contains("""Extension="png"""") || types.contains("""Extension="jpeg""""))
        assertTrue(doc.contains("Resimli sayfa."))
    }

    @Test
    fun word_textOnly_hasNoPictures() {
        val src = pdf(listOf(listOf("Yazı")), picture = true)
        val stats = PdfToWordOperation.run(src, out("n.docx"), "n", "tr-TR", labels, PdfLayout.TEXT_ONLY)
        assertEquals(0, stats.pictures)
        assertFalse(entries(out("n.docx")).keys.any { it.startsWith("word/media/") })
    }

    @Test
    fun word_exactLook_putsOnePagePictureBeforeTheText_andKeepsText() {
        val src = pdf(listOf(listOf("Bir"), listOf("İki"), listOf("Üç")))
        var calls = 0
        val rasterizer = object : PageRasterizer {
            override fun render(file: File, pageIndex: Int, dpi: Int): Bitmap {
                calls++
                assertEquals(150, dpi)
                return Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            }
        }
        val stats = PdfToWordOperation.run(src, out("e.docx"), "e", "tr-TR", labels, PdfLayout.EXACT_LOOK, rasterizer)
        assertEquals(3, calls)
        assertEquals(3, stats.pictures)
        val parts = entries(out("e.docx"))
        assertAllXmlWellFormed(parts)
        assertEquals(3, parts.keys.count { it.startsWith("word/media/") })
        val doc = text(parts, "word/document.xml")
        assertEquals(6, Regex("""descr="Page \d as a picture"""").findAll(doc).count()) // wp:docPr and pic:cNvPr
        assertEquals(3, Regex("<w:sectPr>").findAll(doc).count()) // one section per page, sized to the page
        assertTrue(doc.contains("İki") && doc.contains("Üç"))
        assertTrue(doc.indexOf("Page 1 as a picture") < doc.indexOf("Bir"))
    }

    @Test
    fun word_exactLook_encryptedInput_rendersAnUnprotectedCopy_andCleansUp() {
        val src = TestPdfs.create(tmp.root, 1, "Gizli", password = "pw")
        val seen = mutableListOf<Boolean>()
        val rasterizer = object : PageRasterizer {
            override fun render(file: File, pageIndex: Int, dpi: Int): Bitmap {
                seen += PdfLoader.isEncrypted(file)
                return Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
            }
        }
        PdfToWordOperation.run(src, out("x.docx"), "x", "en", labels, PdfLayout.EXACT_LOOK, rasterizer, password = "pw")
        assertEquals(listOf(false), seen)
        assertTrue(entries(out("x.docx")).getValue("word/document.xml").toString(Charsets.UTF_8).contains("Gizli1"))
        assertEquals(listOf("x.docx"), tmp.root.list()!!.filter { it.startsWith("x.docx") })
    }

    @Test
    fun word_encryptedWithoutPassword_throws_andLeavesNothing() {
        val src = TestPdfs.create(tmp.root, 1, password = "pw")
        try {
            PdfToWordOperation.run(src, out("z.docx"), "z", "en", labels)
            fail()
        } catch (_: PdfError.PasswordRequired) {
        }
        assertFalse(out("z.docx").exists())
        assertEquals("Page1", PdfToWordOperation.run(src, out("z2.docx"), "z", "en", password = "pw").let {
            Regex("Page1").find(entries(out("z2.docx")).getValue("word/document.xml").toString(Charsets.UTF_8))!!.value
        })
    }

    @Test
    fun cancelled_leavesNoOutput() {
        val src = TestPdfs.create(tmp.root, 3)
        var polls = 0
        try {
            PdfToWordOperation.run(src, out("c.docx"), "c", "en", isCancelled = { ++polls > 2 })
            fail()
        } catch (_: CancellationException) {
        }
        assertFalse(out("c.docx").exists())
        assertTrue(tmp.root.list()!!.none { it.startsWith("c.docx") })
    }

    // ---- HTML ----

    @Test
    fun html_isAccessible_escaped_andKeepsPicturesWithAlt() {
        val src = pdf(listOf(listOf("Şükrü Öğüt İçerik", "x < y & \"z\""), listOf("İkinci")), picture = true)
        val stats = PdfToHtmlOperation.run(src, out("a.html"), "Başlık <1>", "tr", labels)
        assertEquals(2, stats.pages)
        assertEquals(2, stats.pictures)
        val html = out("a.html").readText()
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("""<html lang="tr">"""))
        assertTrue(html.contains("<title>Başlık &lt;1&gt;</title>"))
        assertTrue(html.contains("<h1>Başlık &lt;1&gt;</h1>"))
        assertEquals(2, Regex("<h2 ").findAll(html).count())
        assertTrue(html.contains(">Sayfa 2</h2>"))
        assertTrue(html.contains("<p>Şükrü Öğüt İçerik"))
        assertTrue(html, html.contains("x &lt; y &amp; &quot;z&quot;"))
        assertTrue(html, Regex("""<img src="data:image/(png|jpeg);base64,[A-Za-z0-9+/=]+"[^>]* alt="Picture 1 on page 1">""").containsMatchIn(html))
        assertFalse(html.contains("<script"))
    }

    @Test
    fun html_exactLook_hasOneImagePerPage() {
        val src = pdf(listOf(listOf("a"), listOf("b")))
        val rasterizer = object : PageRasterizer {
            override fun render(file: File, pageIndex: Int, dpi: Int) =
                Bitmap.createBitmap(100, 140, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        }
        PdfToHtmlOperation.run(src, out("e.html"), "e", "en", ConversionLabels(), PdfLayout.EXACT_LOOK, rasterizer)
        val html = out("e.html").readText()
        assertEquals(2, Regex("<img ").findAll(html).count())
        assertTrue(html.contains("""alt="Page 2 as a picture""""))
    }

    // ---- Excel ----

    @Test
    fun excel_hasRequiredParts_oneSheetPerPage_inlineStrings_andTurkishText() {
        val src = pdf(listOf(listOf("Şükrü Öğüt İçerik"), listOf("ikinci")))
        val stats = PdfToExcelOperation.run(src, out("a.xlsx"), "Tablo", { "Sayfa $it" })
        assertEquals(2, stats.pages)
        val parts = entries(out("a.xlsx"))
        listOf(
            "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
            "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/styles.xml",
        ).forEach { assertTrue(it, parts.containsKey(it)) }
        assertFalse(parts.containsKey("xl/sharedStrings.xml"))
        assertAllXmlWellFormed(parts)
        val book = text(parts, "xl/workbook.xml")
        assertTrue(book, book.contains("""name="Sayfa 1"""") && book.contains("""name="Sayfa 2""""))
        val sheet = text(parts, "xl/worksheets/sheet1.xml")
        assertTrue(sheet, sheet.contains("""t="inlineStr""""))
        assertTrue(sheet, sheet.contains("Şükrü Öğüt İçerik"))
    }

    @Test
    fun excel_splitsCellsOnWideGapsAndTabs() {
        // Two cells separated by a tab character in the text layer, a third after a wide gap.
        val src = File(tmp.root, "cols.pdf")
        PDDocument().use { doc ->
            val font = Fonts.unicode(doc, fontLoader)
            val page = PDPage(); doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                for ((text, x) in listOf("Ad" to 72f, "Soyad" to 250f, "Şehir" to 400f)) {
                    cs.beginText(); cs.setFont(font, 12f); cs.newLineAtOffset(x, 700f); cs.showText(text); cs.endText()
                }
            }
            doc.save(src)
        }
        PdfToExcelOperation.run(src, out("cols.xlsx"), "c", { "P$it" })
        val sheet = text(entries(out("cols.xlsx")), "xl/worksheets/sheet1.xml")
        val cells = Regex("""<c r="([A-Z]+\d+)"[^>]*><is><t[^>]*>([^<]*)</t>""").findAll(sheet).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(listOf("A1" to "Ad", "B1" to "Soyad", "C1" to "Şehir"), cells)
    }

    @Test
    fun excel_columnNames() {
        assertEquals(listOf("A", "Z", "AA", "AZ", "BA", "ZZ", "AAA"), listOf(0, 25, 26, 51, 52, 701, 702).map { PdfToExcelOperation.columnName(it) })
    }

    // ---- paragraphs ----

    @Test
    fun paragraphs_splitOnBlankLines_andJoinWrappedLines() {
        val text = "This is a long first line that wraps\nonto the next line.\nShort end.\n\nNew paragraph here"
        val p = Paragraphs.split(text)
        assertEquals(
            listOf(
                "This is a long first line that wraps onto the next line.",
                "Short end.",
                "New paragraph here",
            ),
            p,
        )
        assertTrue(Paragraphs.split("  \n\n ").isEmpty())
    }

    @Test
    fun xmlText_dropsIllegalCharacters() {
        assertEquals("ab", XmlText.clean("a\u0000b"))
        assertNotNull(XmlText.escape("\uD800 x"))
    }
}
