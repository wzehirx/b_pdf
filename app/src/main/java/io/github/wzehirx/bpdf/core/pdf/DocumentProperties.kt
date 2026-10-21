package io.github.wzehirx.bpdf.core.pdf

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata

/** Document info, XMP metadata and language: what identifies the document rather than its pages. */
internal fun copyDocumentProperties(source: PDDocument, target: PDDocument) {
    target.documentInformation = PDDocumentInformation(COSDictionary(source.documentInformation.cosObject))
    target.documentCatalog.language = source.documentCatalog.language
    source.documentCatalog.metadata?.let { metadata ->
        val xmp = PDMetadata(target)
        xmp.importXMPMetadata(metadata.createInputStream().use { it.readBytes() })
        target.documentCatalog.metadata = xmp
    }
}
