package io.github.wzehirx.bpdf.core.pdf

import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.Base64
import java.util.concurrent.CancellationException

/**
 * PDF to one accessible HTML file: `<html lang>`, an `<h1>` with the title, an `<h2>` per page, `<p>` per
 * paragraph, all text escaped. Pictures are embedded as data: URIs with alternative text (`<img alt>`),
 * so the file needs nothing else. A picture used on several pages is embedded again on each of them.
 */
object PdfToHtmlOperation {
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
        if (isCancelled()) throw CancellationException("PDF to HTML cancelled")
        val work = File(out.parentFile, out.name + ".work")
        var success = false
        try {
            PdfContentReader(input, password).use { reader ->
                val renderer = if (layout == PdfLayout.EXACT_LOOK) ExactPageRenderer(reader, input, work, rasterizer!!) else null
                renderer.use {
                    var blank = 0
                    var pictures = 0
                    try {
                        OutputStreamWriter(out.outputStream().buffered(), Charsets.UTF_8).use { w ->
                            w.write("<!DOCTYPE html>\n<html lang=\"${XmlText.escape(language)}\">\n<head>\n<meta charset=\"utf-8\">\n")
                            w.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                            w.write("<title>${XmlText.escape(title)}</title>\n")
                            w.write("<style>body{font-family:sans-serif;line-height:1.5;max-width:50em;margin:0 auto;padding:1em}img{max-width:100%;height:auto}</style>\n")
                            w.write("</head>\n<body>\n<main>\n<h1>${XmlText.escape(title)}</h1>\n")
                            for (page in 0 until reader.pageCount) {
                                if (isCancelled()) throw CancellationException("PDF to HTML cancelled")
                                w.write("<section aria-labelledby=\"page${page + 1}\">\n")
                                w.write("<h2 id=\"page${page + 1}\">${XmlText.escape(labels.pageHeader(page + 1))}</h2>\n")
                                if (layout == PdfLayout.EXACT_LOOK) {
                                    w.write(img(renderer!!.render(page), labels.pageImageAlt(page + 1)))
                                    pictures++
                                } else if (layout == PdfLayout.TEXT_AND_PICTURES) {
                                    var n = 0
                                    for (picture in reader.pictures(page)) {
                                        val image = reader.encode(picture) ?: continue
                                        n++
                                        pictures++
                                        w.write(img(image, labels.pictureAlt(n, page + 1)))
                                    }
                                }
                                val paragraphs = Paragraphs.split(reader.text(page))
                                if (paragraphs.isEmpty()) blank++
                                paragraphs.forEach { w.write("<p>${XmlText.escape(it)}</p>\n") }
                                w.write("</section>\n")
                                progress((page + 1).toFloat() / reader.pageCount)
                            }
                            w.write("</main>\n</body>\n</html>\n")
                        }
                    } catch (e: IOException) {
                        throw PdfError.Io(e)
                    }
                    if (isCancelled()) throw CancellationException("PDF to HTML cancelled")
                    success = true
                    return ConversionStats(reader.pageCount, blank, pictures)
                }
            }
        } finally {
            work.deleteRecursively()
            if (!success) out.delete()
        }
    }

    private fun img(image: EncodedImage, alt: String): String =
        "<p><img src=\"data:${image.mimeType};base64,${Base64.getEncoder().encodeToString(image.bytes)}\" " +
            "width=\"${image.widthPx}\" height=\"${image.heightPx}\" alt=\"${XmlText.escape(alt)}\"></p>\n"
}
