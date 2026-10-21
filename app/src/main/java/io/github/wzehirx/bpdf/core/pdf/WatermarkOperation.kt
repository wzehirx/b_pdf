package io.github.wzehirx.bpdf.core.pdf

import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import kotlin.math.cos
import kotlin.math.sin

enum class WatermarkPosition { CENTER, TOP, BOTTOM, TILED }

/**
 * [rotation] is counter-clockwise degrees, [opacity] 0..1, [colorArgb] only its RGB part is used.
 * [pages] are 0-based page indexes (all pages when null).
 */
data class WatermarkOptions(
    val text: String,
    val fontSize: Float = 48f,
    val opacity: Float = 0.3f,
    val rotation: Float = 45f,
    val position: WatermarkPosition = WatermarkPosition.CENTER,
    val colorArgb: Int = 0xFF808080.toInt(),
    val pages: List<Int>? = null,
)

/** Stamps a text watermark (embedded Unicode font, constant transparency) over pages of a PDF. */
object WatermarkOperation {
    private const val EDGE_MARGIN = 36f
    private const val CAP_HALF = 0.36f

    /**
     * Writes a copy of [input] with the watermark to [out] and returns the number of pages marked. Throws
     * [IllegalArgumentException] for blank text (or text the font cannot draw at all), a non-positive size,
     * or an empty / out-of-range [WatermarkOptions.pages]. Characters the font lacks are skipped. The output is not
     * encrypted. [fontLoader] opens the font TTF; [isCancelled] is polled per page and around saving.
     */
    fun run(
        input: File,
        options: WatermarkOptions,
        out: File,
        fontLoader: () -> InputStream,
        password: String? = null,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Int {
        require(options.text.isNotBlank()) { "No watermark text" }
        require(options.fontSize > 0f) { "Font size must be positive" }
        require(options.pages == null || options.pages.isNotEmpty()) { "No pages selected" }
        var success = false
        try {
            if (isCancelled()) throw CancellationException("Watermark cancelled")
            PdfLoader.load(input, password).use { doc ->
                val total = doc.numberOfPages
                val targets = (options.pages ?: (0 until total).toList()).distinct()
                require(targets.all { it in 0 until total }) { "Page index outside 0..${total - 1}" }
                val font = Fonts.unicode(doc, fontLoader)
                val text = font.drawable(options.text.replace(Regex("\\s+"), " ").trim())
                require(text.isNotBlank()) { "The font cannot draw any of the watermark text" }
                val width = font.getStringWidth(text) / 1000f * options.fontSize
                val alpha = PDExtendedGraphicsState().apply {
                    nonStrokingAlphaConstant = options.opacity.coerceIn(0f, 1f)
                    strokingAlphaConstant = options.opacity.coerceIn(0f, 1f)
                }
                val r = (options.colorArgb shr 16) and 0xFF
                val g = (options.colorArgb shr 8) and 0xFF
                val b = options.colorArgb and 0xFF
                targets.forEachIndexed { n, index ->
                    if (isCancelled()) throw CancellationException("Watermark cancelled")
                    PageOverlay.draw(doc, doc.getPage(index)) { cs, visual ->
                        cs.setGraphicsStateParameters(alpha)
                        cs.setNonStrokingColor(r, g, b)
                        cs.beginText()
                        cs.setFont(font, options.fontSize)
                        centers(options, visual, width).forEach { (cx, cy) ->
                            cs.setTextMatrix(textMatrix(cx, cy, width, options))
                            cs.showText(text)
                        }
                        cs.endText()
                    }
                    progress(0.9f * (n + 1) / targets.size)
                }
                if (isCancelled()) throw CancellationException("Watermark cancelled")
                doc.isAllSecurityToBeRemoved = true
                try {
                    doc.save(out)
                } catch (e: IOException) {
                    throw PdfError.Io(e)
                }
                if (isCancelled()) throw CancellationException("Watermark cancelled")
                progress(1f)
                success = true
                return targets.size
            }
        } finally {
            if (!success) out.delete()
        }
    }

    private fun centers(o: WatermarkOptions, v: PageOverlay.Visual, textWidth: Float): List<Pair<Float, Float>> =
        when (o.position) {
            WatermarkPosition.CENTER -> listOf(v.width / 2 to v.height / 2)
            WatermarkPosition.TOP -> listOf(v.width / 2 to v.height - EDGE_MARGIN - o.fontSize / 2)
            WatermarkPosition.BOTTOM -> listOf(v.width / 2 to EDGE_MARGIN + o.fontSize / 2)
            WatermarkPosition.TILED -> {
                val stepX = textWidth + o.fontSize * 2
                val stepY = o.fontSize * 4
                val tiles = ArrayList<Pair<Float, Float>>()
                var row = 0
                var y = stepY / 2
                while (y < v.height + stepY / 2) {
                    var x = if (row % 2 == 0) stepX / 2 else 0f
                    while (x < v.width + stepX / 2) {
                        tiles += x to y
                        x += stepX
                    }
                    y += stepY
                    row++
                }
                tiles
            }
        }

    /** Text matrix that puts the middle of the string at ([cx], [cy]), turned by the rotation. */
    private fun textMatrix(cx: Float, cy: Float, textWidth: Float, o: WatermarkOptions): Matrix {
        val angle = Math.toRadians(o.rotation.toDouble())
        val dx = -textWidth / 2
        val dy = -o.fontSize * CAP_HALF
        val x = cx + (dx * cos(angle) - dy * sin(angle)).toFloat()
        val y = cy + (dx * sin(angle) + dy * cos(angle)).toFloat()
        return Matrix.getRotateInstance(angle, x, y)
    }
}
