package io.github.wzehirx.bpdf.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.CancellationException

/**
 * Document information of a PDF. The text fields are null when the file has none (or only blanks).
 * [pageCount], [encrypted] and [pdfVersion] describe the file and are ignored when writing.
 */
data class PdfMetadata(
    val title: String?,
    val author: String?,
    val subject: String?,
    val keywords: String?,
    val creator: String?,
    val producer: String?,
    val pageCount: Int,
    val encrypted: Boolean,
    val pdfVersion: Float,
)

/** Reads and rewrites the document information dictionary (Unicode text is stored as UTF-16). */
object MetadataOperation {
    /** Throws [PdfError.PasswordRequired] / [PdfError.WrongPassword] for a protected [input]. */
    fun read(input: File, password: String? = null): PdfMetadata =
        PdfLoader.load(input, password).use { doc ->
            val info = doc.documentInformation
            PdfMetadata(
                title = info.title.clean(),
                author = info.author.clean(),
                subject = info.subject.clean(),
                keywords = info.keywords.clean(),
                creator = info.creator.clean(),
                producer = info.producer.clean(),
                pageCount = doc.numberOfPages,
                encrypted = doc.isEncrypted,
                pdfVersion = doc.version,
            )
        }

    /**
     * Writes a copy of [input] with the text fields of [md] as its document information (blank fields are
     * removed) and the modification date set to now. The output is not encrypted (like every tool that
     * rewrites a document, PDFBox cannot keep an unknown owner password). [isCancelled] is polled before loading and after saving; any failure or cancellation
     * leaves no [out].
     */
    fun write(
        input: File,
        md: PdfMetadata,
        out: File,
        password: String? = null,
        isCancelled: () -> Boolean = { false },
    ) {
        var success = false
        try {
            if (isCancelled()) throw CancellationException("Metadata cancelled")
            PdfLoader.load(input, password).use { doc ->
                apply(doc, md)
                doc.isAllSecurityToBeRemoved = true
                try {
                    doc.save(out)
                } catch (e: IOException) {
                    throw PdfError.Io(e)
                }
            }
            if (isCancelled()) throw CancellationException("Metadata cancelled")
            success = true
        } finally {
            if (!success) out.delete()
        }
    }

    private fun apply(doc: PDDocument, md: PdfMetadata) {
        doc.documentInformation.apply {
            title = md.title.clean()
            author = md.author.clean()
            subject = md.subject.clean()
            keywords = md.keywords.clean()
            creator = md.creator.clean()
            producer = md.producer.clean()
            modificationDate = Calendar.getInstance()
        }
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
