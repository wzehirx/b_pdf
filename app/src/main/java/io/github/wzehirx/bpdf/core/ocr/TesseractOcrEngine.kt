package io.github.wzehirx.bpdf.core.ocr

import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * [OcrEngine] backed by Tesseract (Tesseract4Android, LSTM engine). [dataPath] is the folder that contains a
 * `tessdata` folder with the `.traineddata` files (see [TessDataInstaller]). The native engine is started on the first
 * [recognize] and again whenever the language changes; it is not thread-safe except for [stop]. Never created in unit
 * tests, which use a fake [OcrEngine].
 */
class TesseractOcrEngine(private val dataPath: File) : OcrEngine {
    @Volatile
    private var api: TessBaseAPI? = null
    private var startedWith: String? = null

    override fun recognize(bitmap: Bitmap, languages: String): String {
        val tess = engineFor(languages)
        tess.setImage(bitmap)
        try {
            return tess.getUTF8Text().orEmpty().trim()
        } finally {
            tess.clear() // frees the picture copy held by Tesseract
        }
    }

    private fun engineFor(languages: String): TessBaseAPI {
        api?.let { if (startedWith == languages) return it }
        release()
        val tess = TessBaseAPI()
        val ok = try {
            tess.init(dataPath.absolutePath, languages, TessBaseAPI.OEM_LSTM_ONLY)
        } catch (e: RuntimeException) {
            tess.recycle()
            throw OcrFailed("Tesseract could not start", e)
        }
        if (!ok) {
            tess.recycle()
            throw OcrFailed("Tesseract could not load the language data for $languages")
        }
        api = tess
        startedWith = languages
        return tess
    }

    // stop() comes from another thread: share a lock with release() so the engine is never recycled during stop().
    private val lifecycle = Any()

    override fun stop() {
        synchronized(lifecycle) { runCatching { api?.stop() } }
    }

    override fun close() = release()

    private fun release() {
        synchronized(lifecycle) {
            val tess = api
            api = null
            startedWith = null
            runCatching { tess?.recycle() }
        }
    }
}
