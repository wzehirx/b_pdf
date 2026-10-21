package io.github.wzehirx.bpdf.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** Fixture builder for engine tests. Page i (1-based) contains the ASCII text "$textPrefix$i". */
object TestPdfs {
    private var counter = 0

    fun create(dir: File, pages: Int, textPrefix: String = "Page", password: String? = null): File {
        val file = File(dir, "fixture-${counter++}.pdf")
        PDDocument().use { doc ->
            for (i in 0 until pages) {
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 18f)
                    cs.newLineAtOffset(72f, 700f)
                    cs.showText("$textPrefix${i + 1}")
                    cs.endText()
                }
            }
            if (password != null) {
                val policy = StandardProtectionPolicy(password, password, AccessPermission())
                policy.encryptionKeyLength = 256
                doc.protect(policy)
            }
            doc.save(file)
        }
        return file
    }

    /** Text of each page, trimmed, in page order. */
    fun text(file: File, password: String? = null): List<String> =
        PDDocument.load(file, password ?: "").use { doc ->
            (1..doc.numberOfPages).map { p ->
                PDFTextStripper().apply { startPage = p; endPage = p }.getText(doc).trim()
            }
        }
}
