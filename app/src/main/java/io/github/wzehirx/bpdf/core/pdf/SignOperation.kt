package io.github.wzehirx.bpdf.core.pdf

import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.util.concurrent.CancellationException
import kotlin.math.min

/** What is stamped: a typed name (drawn with the embedded Unicode font) or a picture (a PNG, e.g. a drawn signature). */
sealed interface SignatureSource {
    data class Typed(val text: String) : SignatureSource

    data class Image(val pngFile: File) : SignatureSource
}

enum class SignPosition { BOTTOM_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, TOP_RIGHT, TOP_LEFT, CENTER }

/** [page] is the 0-based page index; [widthPt] the width of the signature in points (1/72 inch). */
data class SignOptions(
    val page: Int,
    val position: SignPosition = SignPosition.BOTTOM_RIGHT,
    val widthPt: Float = 160f,
    val addDate: Boolean = false,
)

/**
 * Stamps a visual signature on one page. This is a picture or text on the page, NOT a cryptographic
 * (digital) signature: nothing is certified and the document is not tamper-evident.
 */
object SignOperation {
    private const val MARGIN = 36f
    private const val MAX_TEXT_SIZE = 30f
    private const val MIN_TEXT_SIZE = 6f
    private const val DATE_SIZE = 9f
    private const val DATE_GAP = 4f
    private const val LINE_HEIGHT = 1.2f
    private const val RULE_GAP = 3f

    /**
     * Writes a copy of [input] with the signature on page [SignOptions.page] to [out]. Throws
     * [IllegalArgumentException] for blank text (or text the font cannot draw), a non-positive width, a page outside the
     * document, or a missing or unreadable picture. Characters the font lacks are skipped. The output is not
     * encrypted. [fontLoader] opens the font TTF, [today] gives the date printed when [SignOptions.addDate] is set;
     * [isCancelled] is polled around the work and saving, and a failure or cancellation leaves no [out].
     */
    fun run(
        input: File,
        source: SignatureSource,
        options: SignOptions,
        out: File,
        fontLoader: () -> InputStream,
        password: String? = null,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        today: () -> LocalDate = { LocalDate.now() },
    ) {
        require(options.widthPt > 0f) { "Width must be positive" }
        require(options.page >= 0) { "Page index must not be negative" }
        if (source is SignatureSource.Typed) require(source.text.isNotBlank()) { "No signature text" }
        if (source is SignatureSource.Image) require(source.pngFile.isFile) { "Signature picture is missing" }
        var success = false
        try {
            if (isCancelled()) throw CancellationException("Sign cancelled")
            PdfLoader.load(input, password).use { doc ->
                require(options.page < doc.numberOfPages) { "Page index outside 0..${doc.numberOfPages - 1}" }
                val font = Fonts.unicode(doc, fontLoader)
                val dateText = if (options.addDate) font.drawable(today().toString()) else ""
                val dateHeight = if (dateText.isEmpty()) 0f else DATE_SIZE * LINE_HEIGHT + DATE_GAP
                val page = doc.getPage(options.page)
                val visual = PageOverlay.visualSize(page)
                val maxWidth = (visual.width - 2 * MARGIN).coerceAtLeast(1f)
                val maxHeight = (visual.height - 2 * MARGIN).coerceAtLeast(1f)
                progress(0.1f)

                when (source) {
                    is SignatureSource.Typed -> {
                        val text = font.drawable(source.text.replace(Regex("\\s+"), " ").trim())
                        require(text.isNotBlank()) { "The font cannot draw any of the signature text" }
                        val units = font.getStringWidth(text) / 1000f
                        val size = min(MAX_TEXT_SIZE, min(options.widthPt, maxWidth) / units).coerceAtLeast(MIN_TEXT_SIZE)
                        val width = units * size
                        val height = size * LINE_HEIGHT + dateHeight
                        val (x, y) = origin(options.position, visual, width, height)
                        PageOverlay.draw(doc, page) { cs, _ ->
                            cs.setNonStrokingColor(0, 0, 0)
                            cs.setStrokingColor(0, 0, 0)
                            val baseline = y + dateHeight + size * 0.25f
                            cs.beginText()
                            cs.setFont(font, size)
                            cs.newLineAtOffset(x, baseline)
                            cs.showText(text)
                            cs.endText()
                            cs.setLineWidth(0.75f)
                            cs.moveTo(x, baseline - RULE_GAP)
                            cs.lineTo(x + width, baseline - RULE_GAP)
                            cs.stroke()
                            drawDate(cs, font, dateText, x, y, width)
                        }
                    }
                    is SignatureSource.Image -> {
                        val bitmap = BitmapFactory.decodeFile(source.pngFile.absolutePath)
                            ?: throw IllegalArgumentException("Signature picture cannot be read")
                        try {
                            val ratio = bitmap.height.toFloat() / bitmap.width
                            var width = min(options.widthPt, maxWidth)
                            if (width * ratio + dateHeight > maxHeight) width = (maxHeight - dateHeight).coerceAtLeast(1f) / ratio
                            val height = width * ratio + dateHeight
                            val (x, y) = origin(options.position, visual, width, height)
                            val image = LosslessFactory.createFromImage(doc, bitmap)
                            PageOverlay.draw(doc, page) { cs, _ ->
                                cs.drawImage(image, x, y + dateHeight, width, width * ratio)
                                drawDate(cs, font, dateText, x, y, width)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
                progress(0.7f)
                if (isCancelled()) throw CancellationException("Sign cancelled")
                doc.isAllSecurityToBeRemoved = true
                try {
                    doc.save(out)
                } catch (e: IOException) {
                    throw PdfError.Io(e)
                }
                if (isCancelled()) throw CancellationException("Sign cancelled")
                progress(1f)
                success = true
            }
        } finally {
            if (!success) out.delete()
        }
    }

    private fun drawDate(
        cs: com.tom_roush.pdfbox.pdmodel.PDPageContentStream,
        font: PDType0Font,
        text: String,
        x: Float,
        y: Float,
        blockWidth: Float,
    ) {
        if (text.isEmpty()) return
        val width = font.getStringWidth(text) / 1000f * DATE_SIZE
        cs.beginText()
        cs.setFont(font, DATE_SIZE)
        cs.newLineAtOffset(x + (blockWidth - width) / 2, y + 2f)
        cs.showText(text)
        cs.endText()
    }

    /** Lower-left corner of a [width] by [height] block placed at [position] on the visible page. */
    private fun origin(position: SignPosition, v: PageOverlay.Visual, width: Float, height: Float): Pair<Float, Float> {
        val x = when (position) {
            SignPosition.BOTTOM_LEFT, SignPosition.TOP_LEFT -> MARGIN
            SignPosition.BOTTOM_CENTER, SignPosition.CENTER -> (v.width - width) / 2
            SignPosition.BOTTOM_RIGHT, SignPosition.TOP_RIGHT -> v.width - MARGIN - width
        }
        val y = when (position) {
            SignPosition.TOP_LEFT, SignPosition.TOP_RIGHT -> v.height - MARGIN - height
            SignPosition.CENTER -> (v.height - height) / 2
            else -> MARGIN
        }
        return x to y
    }
}
