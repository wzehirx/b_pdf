package io.github.wzehirx.bpdf.core.pdf

import android.graphics.Bitmap
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.OperatorProcessor
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDTransparencyGroup
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/** How hard to squeeze: JPEG quality (0..1) and the highest resolution, in dots per inch, an image keeps on the page. */
enum class CompressLevel(val jpegQuality: Float, val maxDpi: Int) {
    LOW(0.85f, 200),
    MEDIUM(0.7f, 150),
    HIGH(0.5f, 100),
}

/** File sizes in bytes before and after compression. [afterBytes] is never reported as a saving when it is not smaller. */
data class CompressResult(val beforeBytes: Long, val afterBytes: Long) {
    val savedBytes: Long get() = (beforeBytes - afterBytes).coerceAtLeast(0)

    /** Whole percent saved (0 when nothing was saved). */
    val savedPercent: Int get() = if (beforeBytes <= 0) 0 else (savedBytes * 100 / beforeBytes).toInt()
}

/**
 * Shrinks a PDF by re-encoding its pictures as JPEG at [CompressLevel.jpegQuality] and scaling down
 * pictures shown at more than [CompressLevel.maxDpi]. Pictures with transparency or masks, and pictures
 * that would not get smaller, are left untouched. PdfBox 2.0 cannot write compressed object streams, so
 * only pictures (and optionally metadata) are reduced.
 */
object CompressOperation {
    private class ImageUse(val resources: PDResources, val name: COSName, val image: PDImageXObject, var dpi: Float)

    /** Finds every picture drawn on a page (also inside form XObjects) and the resolution it is shown at. */
    private class Scanner(val uses: MutableList<ImageUse>) : PDFStreamEngine() {
        init {
            addOperator(Concatenate())
            addOperator(Save())
            addOperator(Restore())
            addOperator(SetGraphicsStateParameters())
            addOperator(SetMatrix())
            addOperator(object : OperatorProcessor() {
                override fun process(operator: Operator, operands: MutableList<COSBase>) {
                    val name = operands.firstOrNull() as? COSName ?: return
                    when (val x = context.resources.getXObject(name)) {
                        is PDImageXObject -> {
                            val m = context.graphicsState.currentTransformationMatrix
                            val wIn = m.scalingFactorX / 72f
                            val hIn = m.scalingFactorY / 72f
                            val dpi = maxOf(
                                if (wIn > 0f) x.width / wIn else 0f,
                                if (hIn > 0f) x.height / hIn else 0f,
                            )
                            uses += ImageUse(context.resources, name, x, dpi)
                        }
                        is PDTransparencyGroup -> context.showTransparencyGroup(x)
                        is PDFormXObject -> context.showForm(x)
                        else -> Unit
                    }
                }

                override fun getName() = "Do"
            })
        }
    }

    /**
     * Writes a smaller copy of [input] to [out] and returns the sizes. When the result would not be smaller,
     * [input] is copied as is (an encrypted input is saved without encryption instead). [removeMetadata] also
     * clears the document information and XMP metadata. [isCancelled] is polled between pictures and around
     * saving; any failure or cancellation leaves no [out].
     */
    fun run(
        input: File,
        level: CompressLevel,
        out: File,
        removeMetadata: Boolean = false,
        password: String? = null,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): CompressResult {
        var success = false
        try {
            if (isCancelled()) throw CancellationException("Compress cancelled")
            val before = input.length()
            PdfLoader.load(input, password).use { doc ->
                val encrypted = doc.isEncrypted
                val uses = ArrayList<ImageUse>()
                val total = doc.numberOfPages
                for (index in 0 until total) {
                    if (isCancelled()) throw CancellationException("Compress cancelled")
                    scanPage(doc.getPage(index), uses)
                    progress(0.3f * (index + 1) / total)
                }
                recompress(doc, uses, level, progress, isCancelled)
                if (removeMetadata) {
                    doc.documentInformation = PDDocumentInformation()
                    doc.documentCatalog.metadata = null
                }
                if (isCancelled()) throw CancellationException("Compress cancelled")
                doc.isAllSecurityToBeRemoved = true
                try {
                    doc.save(out)
                } catch (e: IOException) {
                    throw PdfError.Io(e)
                }
                if (isCancelled()) throw CancellationException("Compress cancelled")
                if (out.length() >= before && !encrypted && !removeMetadata) {
                    try {
                        input.copyTo(out, overwrite = true)
                    } catch (e: IOException) {
                        throw PdfError.Io(e)
                    }
                }
                progress(1f)
                success = true
                return CompressResult(before, out.length())
            }
        } finally {
            if (!success) out.delete()
        }
    }

    private fun scanPage(page: PDPage, uses: MutableList<ImageUse>) {
        try {
            Scanner(uses).processPage(page)
        } catch (_: IOException) {
            // A page whose content cannot be read keeps its pictures as they are.
        } catch (_: RuntimeException) {
        }
    }

    private fun recompress(
        doc: PDDocument,
        uses: List<ImageUse>,
        level: CompressLevel,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        // One replacement per picture, however many pages or forms show it; the strongest need decides the size.
        val byStream = IdentityHashMap<COSStream, MutableList<ImageUse>>()
        uses.forEach { byStream.getOrPut(it.image.cosObject) { ArrayList() } += it }
        var done = 0
        for ((_, group) in byStream) {
            if (isCancelled()) throw CancellationException("Compress cancelled")
            val original = group.first().image
            val dpi = group.maxOf { it.dpi }
            val replacement = recompressImage(doc, original, dpi, level)
            if (replacement != null) {
                group.forEach { it.resources.put(it.name, replacement) }
            }
            progress(0.3f + 0.6f * (++done) / byStream.size)
        }
    }

    private fun recompressImage(doc: PDDocument, image: PDImageXObject, dpi: Float, level: CompressLevel): PDImageXObject? {
        var bitmap: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            if (image.isStencil || image.softMask != null || image.mask != null || image.colorKeyMask != null) return null
            if (image.cosObject.containsKey(COSName.getPDFName("SMaskInData"))) return null
            bitmap = image.image ?: return null
            var target = bitmap
            if (dpi > level.maxDpi) {
                val factor = level.maxDpi / dpi
                val w = (bitmap.width * factor).toInt().coerceAtLeast(1)
                val h = (bitmap.height * factor).toInt().coerceAtLeast(1)
                scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
                target = scaled
            }
            val candidate = JPEGFactory.createFromImage(doc, target, level.jpegQuality)
            return if (rawSize(candidate) < rawSize(image)) candidate else null
        } catch (_: IOException) {
            return null
        } catch (_: RuntimeException) {
            return null
        } catch (_: OutOfMemoryError) {
            return null
        } finally {
            if (scaled != null && scaled !== bitmap) scaled.recycle()
            bitmap?.recycle()
        }
    }

    private fun rawSize(image: PDImageXObject): Long =
        image.cosObject.createRawInputStream().use { stream ->
            val buffer = ByteArray(8192)
            var n = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                n += read
            }
            n
        }
}
