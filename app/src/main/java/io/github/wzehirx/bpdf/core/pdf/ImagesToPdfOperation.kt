package io.github.wzehirx.bpdf.core.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class PageSizeOption { A4, LETTER, FIT_IMAGE }

enum class Orientation { AUTO, PORTRAIT, LANDSCAPE }

data class ImagesToPdfOptions(
    val pageSize: PageSizeOption = PageSizeOption.A4,
    /** Ignored for [PageSizeOption.FIT_IMAGE], whose page always has the image's shape. */
    val orientation: Orientation = Orientation.AUTO,
    /** Empty border around the image on every side, in PDF points. */
    val marginPt: Float = 0f,
    val jpegQuality: Int = 85,
)

/** Builds a PDF with one page per image, in the given order. */
object ImagesToPdfOperation {
    /** Longest side an image is reduced to before it is embedded. */
    const val MAX_IMAGE_SIDE = 3000

    /** Resolution assumed for [PageSizeOption.FIT_IMAGE] pages (pixels per inch). */
    private const val FIT_DPI = 150f

    /**
     * Writes [images] (at least one) into [out], one page each. Photos are rotated according to their
     * EXIF orientation, reduced to at most [MAX_IMAGE_SIDE] px on the longest side, flattened onto white
     * (JPEG has no transparency) and embedded as JPEG. [progress] receives 0..1. [isCancelled] is polled
     * before each image and after saving; when it returns true a [CancellationException] is thrown.
     * An image that cannot be decoded throws [PdfError.UnreadableImage]. Any failure or cancellation
     * leaves no [out].
     */
    fun run(
        images: List<File>,
        options: ImagesToPdfOptions,
        out: File,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) {
        require(images.isNotEmpty()) { "At least one image is required" }
        val doc = PDDocument(MemoryUsageSetting.setupTempFileOnly())
        var success = false
        try {
            images.forEachIndexed { i, file ->
                if (isCancelled()) throw CancellationException("Images to PDF cancelled")
                val bitmap = decode(file, file.name)
                try {
                    addPage(doc, bitmap, options)
                } catch (e: IOException) {
                    throw PdfError.Io(e)
                } finally {
                    bitmap.recycle()
                }
                progress((i + 1).toFloat() / (images.size + 1))
            }
            try {
                doc.save(out)
            } catch (e: IOException) {
                throw PdfError.Io(e)
            }
            if (isCancelled()) throw CancellationException("Images to PDF cancelled")
            progress(1f)
            success = true
        } finally {
            runCatching { doc.close() }
            if (!success) out.delete()
        }
    }

    private fun addPage(doc: PDDocument, bitmap: Bitmap, options: ImagesToPdfOptions) {
        val margin = options.marginPt.coerceAtLeast(0f)
        val (pageW, pageH) = pageSize(bitmap.width, bitmap.height, options, margin)
        val page = PDPage(PDRectangle(pageW, pageH))
        doc.addPage(page)
        val availW = (pageW - 2 * margin).coerceAtLeast(1f)
        val availH = (pageH - 2 * margin).coerceAtLeast(1f)
        val scale = min(availW / bitmap.width, availH / bitmap.height)
        val drawW = bitmap.width * scale
        val drawH = bitmap.height * scale
        val image = JPEGFactory.createFromImage(doc, bitmap, options.jpegQuality.coerceIn(1, 100) / 100f)
        PDPageContentStream(doc, page).use { cs ->
            cs.drawImage(image, (pageW - drawW) / 2f, (pageH - drawH) / 2f, drawW, drawH)
        }
    }

    private fun pageSize(imageW: Int, imageH: Int, options: ImagesToPdfOptions, margin: Float): Pair<Float, Float> {
        val base = when (options.pageSize) {
            PageSizeOption.A4 -> 595f to 842f
            PageSizeOption.LETTER -> 612f to 792f
            PageSizeOption.FIT_IMAGE ->
                return (imageW * 72f / FIT_DPI + 2 * margin) to (imageH * 72f / FIT_DPI + 2 * margin)
        }
        val landscape = when (options.orientation) {
            Orientation.AUTO -> imageW > imageH
            Orientation.PORTRAIT -> false
            Orientation.LANDSCAPE -> true
        }
        return if (landscape) base.second to base.first else base
    }

    /**
     * Decodes [file] upright (EXIF orientation applied), at most [MAX_IMAGE_SIDE] px on the longest
     * side and without transparency. The caller recycles the result. Throws [PdfError.UnreadableImage]
     * (carrying [name]) when the file is not a decodable image.
     */
    internal fun decode(file: File, name: String): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PdfError.UnreadableImage(name)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw PdfError.UnreadableImage(name)
        var current = decoded
        fun replace(next: Bitmap) {
            if (next !== current) current.recycle()
            current = next
        }
        try {
            val longest = max(current.width, current.height)
            if (longest > MAX_IMAGE_SIDE) {
                val f = MAX_IMAGE_SIDE.toFloat() / longest
                replace(
                    Bitmap.createScaledBitmap(
                        current, (current.width * f).roundToInt().coerceAtLeast(1), (current.height * f).roundToInt().coerceAtLeast(1), true,
                    ),
                )
            }
            exifMatrix(file)?.let { m ->
                replace(Bitmap.createBitmap(current, 0, 0, current.width, current.height, m, true))
            }
            if (current.hasAlpha()) replace(flattenOnWhite(current))
            return current
        } catch (t: Throwable) {
            current.recycle()
            throw t
        }
    }

    private fun flattenOnWhite(source: Bitmap): Bitmap {
        val flat = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, 0f, 0f, null)
        }
        flat.setHasAlpha(false)
        return flat
    }

    private fun exifMatrix(file: File): Matrix? {
        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: IOException) {
            return null
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.apply { setRotate(180f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> m.apply { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> m.apply { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return null
        }
        return m
    }
}
