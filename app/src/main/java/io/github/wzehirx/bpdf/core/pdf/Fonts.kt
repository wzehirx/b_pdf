package io.github.wzehirx.bpdf.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.IOException
import java.io.InputStream

/** The bundled Unicode font (Noto Sans Regular, OFL) used for every text drawn into a PDF. */
object Fonts {
    /** Asset path of the font in the app. */
    const val ASSET_PATH = "fonts/NotoSans-Regular.ttf"

    /** Embeds the font (subset) in [doc]. [loader] opens a fresh stream of the TTF each call; the stream is closed here. */
    fun unicode(doc: PDDocument, loader: () -> InputStream): PDType0Font =
        try {
            loader().use { PDType0Font.load(doc, it, true) }
        } catch (e: IOException) {
            throw PdfError.Io(e)
        }
}

/** What [Fonts] can do with a string before it is drawn. */
internal fun PDType0Font.drawable(text: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        if (Character.isISOControl(cp)) continue
        val s = String(Character.toChars(cp))
        try {
            getStringWidth(s) // throws when the font has no glyph for it
            out.append(s)
        } catch (_: IllegalArgumentException) {
        }
    }
    return out.toString()
}
