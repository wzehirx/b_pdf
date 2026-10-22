package io.github.wzehirx.bpdf.core.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap

/** What the PDF-to-document converters keep of the page's look. */
enum class PdfLayout {
    /** Headings and paragraphs only. */
    TEXT_ONLY,

    /** Headings, paragraphs and every embedded picture (editable). */
    TEXT_AND_PICTURES,

    /** Every page drawn as one picture (keeps vector shapes and layout), followed by its text. */
    EXACT_LOOK,
}

/** Texts a converter writes around the content; the UI gives localized ones. */
class ConversionLabels(
    /** Heading of the 1-based page. */
    val pageHeader: (Int) -> String = { "Page $it" },
    /** Alternative text of picture [n] (1-based) on page [page] (1-based). */
    val pictureAlt: (n: Int, page: Int) -> String = { n, page -> "Picture $n on page $page" },
    /** Alternative text of the picture of a whole page in the exact look. */
    val pageImageAlt: (page: Int) -> String = { "Page $it as a picture" },
)

/** What a PDF-to-document conversion produced. */
data class ConversionStats(val pages: Int, val blankPages: Int, val pictures: Int = 0)

/** A picture ready to be embedded. */
internal class EncodedImage(val bytes: ByteArray, val png: Boolean, val widthPx: Int, val heightPx: Int) {
    val extension: String get() = if (png) "png" else "jpeg"
    val mimeType: String get() = if (png) "image/png" else "image/jpeg"
}

internal object ImageEncoder {
    const val MAX_SIDE = 3000
    private const val MIN_SIDE = 8

    /** Encodes [bitmap] (not recycled here). Opaque pictures become JPEG unless [forcePng]. */
    fun encode(bitmap: Bitmap, forcePng: Boolean = false, jpegQuality: Int = 88): EncodedImage? {
        if (bitmap.width < MIN_SIDE || bitmap.height < MIN_SIDE) return null
        var source = bitmap
        var scaled: Bitmap? = null
        var flat: Bitmap? = null
        try {
            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest > MAX_SIDE) {
                val f = MAX_SIDE.toFloat() / longest
                scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt().coerceAtLeast(1), (bitmap.height * f).toInt().coerceAtLeast(1), true)
                source = scaled
            }
            val out = ByteArrayOutputStream()
            if (forcePng) {
                if (!source.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
            } else {
                if (source.hasAlpha()) {
                    flat = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                    Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(source, 0f, 0f, null) }
                    source = flat
                }
                if (!source.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)) return null
            }
            return EncodedImage(out.toByteArray(), forcePng, source.width, source.height)
        } finally {
            if (scaled != null && scaled !== bitmap) scaled.recycle()
            flat?.recycle()
        }
    }
}

/** One open PDF read by the converters: page text, page size and embedded pictures. */
internal class PdfContentReader(file: File, password: String? = null) : Closeable {
    private val doc: PDDocument = PdfLoader.load(file, password)

    val pageCount: Int = try {
        doc.numberOfPages
    } catch (t: Throwable) {
        runCatching { doc.close() }
        throw t
    }

    val isEncrypted: Boolean get() = doc.isEncrypted

    /**
     * Text of page [index] (0-based), lines in reading order. With [columns] the lines are sorted by position
     * and a tab is written where the gap between two words is much wider than a space, so table columns can
     * be told apart.
     */
    fun text(index: Int, columns: Boolean = false): String = try {
        (if (columns) GapStripper() else PDFTextStripper()).apply {
            startPage = index + 1
            endPage = index + 1
            if (columns) setSortByPosition(true)
        }.getText(doc)
    } catch (e: IOException) {
        throw PdfError.Corrupt(e)
    } catch (e: RuntimeException) {
        throw PdfError.Corrupt(e)
    }

    private class GapStripper : PDFTextStripper() {
        private var lastEnd = Float.NaN

        override fun writeLineSeparator() {
            lastEnd = Float.NaN
            super.writeLineSeparator()
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            if (textPositions.isNotEmpty()) {
                val first = textPositions.first()
                val last = textPositions.last()
                if (!lastEnd.isNaN() && first.xDirAdj - lastEnd > first.fontSizeInPt * 0.8f) output.write("\t")
                lastEnd = last.xDirAdj + last.widthDirAdj
            }
            super.writeString(text, textPositions)
        }
    }

    /** Page size in points as it is displayed (rotation applied). */
    fun sizePt(index: Int): Pair<Float, Float> {
        val page = doc.getPage(index)
        val box = page.mediaBox
        val rotated = page.rotation % 180 != 0
        return if (rotated) box.height to box.width else box.width to box.height
    }

    /**
     * The pictures on page [index] (0-based): images in the page's resources, including those inside form
     * XObjects, once each. [key] identifies the picture across pages.
     */
    fun pictures(index: Int): List<Picture> {
        val seen = IdentityHashMap<Any, Boolean>()
        val out = ArrayList<Picture>()
        try {
            collect(doc.getPage(index).resources, seen, out, 0)
        } catch (e: IOException) {
            // A damaged resource dictionary: keep what was found.
        } catch (e: RuntimeException) {
        }
        return out
    }

    class Picture(val key: Any, val image: PDImageXObject)

    private fun collect(resources: PDResources?, seen: IdentityHashMap<Any, Boolean>, out: MutableList<Picture>, depth: Int) {
        if (resources == null || depth > 4) return
        val names: List<COSName> = resources.xObjectNames.toList()
        for (name in names) {
            val x = try { resources.getXObject(name) } catch (e: IOException) { null } ?: continue
            if (seen.put(x.cosObject, true) != null) continue
            when (x) {
                is PDImageXObject -> out += Picture(x.cosObject, x)
                is PDFormXObject -> collect(x.resources, seen, out, depth + 1)
                else -> Unit
            }
        }
    }

    /** Encodes [picture], or null when it is tiny or cannot be decoded. The bitmap is recycled here. */
    fun encode(picture: Picture): EncodedImage? {
        val image = picture.image
        // Decode large pictures at reduced size: ImageEncoder shrinks them to MAX_SIDE anyway, and a
        // full-size 10000 x 14000 scan alone would need over 500 MB.
        val sub = maxOf(1, Math.ceil(maxOf(image.width, image.height).toDouble() / ImageEncoder.MAX_SIDE).toInt())
        val bitmap = try { if (sub > 1) image.getImage(null, sub) else image.image } catch (e: IOException) { null } catch (e: RuntimeException) { null } ?: return null
        return try {
            val transparent = try { image.softMask != null || image.mask != null || image.isStencil } catch (e: Exception) { false }
            ImageEncoder.encode(bitmap, forcePng = transparent)
        } finally {
            bitmap.recycle()
        }
    }

    /** Saves a copy without password protection (for the system renderer, which cannot open protected files). */
    fun saveUnprotected(to: File) {
        try {
            doc.isAllSecurityToBeRemoved = true
            doc.save(to)
        } catch (e: IOException) {
            throw PdfError.Io(e)
        }
    }

    override fun close() {
        runCatching { doc.close() }
    }
}
