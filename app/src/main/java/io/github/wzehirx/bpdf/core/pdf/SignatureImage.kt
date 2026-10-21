package io.github.wzehirx.bpdf.core.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.io.File
import java.io.IOException

/** One point of a drawn signature, as a fraction (0..1) of the width and height of the drawing area. */
data class SignaturePoint(val x: Float, val y: Float)

/** Turns a signature drawn with the finger (a list of strokes) into a transparent PNG for [SignOperation]. */
object SignatureImage {
    /** Width : height of the drawing area and of the PNG. */
    const val ASPECT = 2.5f

    private const val WIDTH_PX = 1000
    private const val INK = 0xFF111111.toInt()

    /** True when at least one stroke has a point. */
    fun hasInk(strokes: List<List<SignaturePoint>>): Boolean = strokes.any { it.isNotEmpty() }

    /**
     * Draws [strokes] in dark ink on a transparent background and writes the PNG to [out]. Throws
     * [IllegalArgumentException] when there is nothing to draw and [PdfError.Io] when writing fails (no [out] is left).
     */
    fun render(strokes: List<List<SignaturePoint>>, out: File) {
        require(hasInk(strokes)) { "Nothing drawn" }
        val height = (WIDTH_PX / ASPECT).toInt()
        val bitmap = Bitmap.createBitmap(WIDTH_PX, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(bitmap)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = INK
                style = Paint.Style.STROKE
                strokeWidth = 7f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; style = Paint.Style.FILL }
            for (stroke in strokes) {
                if (stroke.isEmpty()) continue
                val first = stroke.first()
                if (stroke.size == 1) {
                    canvas.drawCircle(first.x * WIDTH_PX, first.y * height, 4f, dot)
                    continue
                }
                val path = Path().apply {
                    moveTo(first.x * WIDTH_PX, first.y * height)
                    for (p in stroke.drop(1)) lineTo(p.x * WIDTH_PX, p.y * height)
                }
                canvas.drawPath(path, line)
            }
            try {
                out.outputStream().buffered().use { stream ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException("Could not encode the signature")
                }
            } catch (e: IOException) {
                out.delete()
                throw PdfError.Io(e)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
