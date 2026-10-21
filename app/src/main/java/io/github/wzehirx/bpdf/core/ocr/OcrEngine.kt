package io.github.wzehirx.bpdf.core.ocr

import android.graphics.Bitmap

/** Reads the text of a picture; the engine's seam for tests that cannot run native Tesseract. */
interface OcrEngine {
    /**
     * The text in [bitmap]. [languages] are Tesseract language codes joined with `+`, for example `tur+eng`.
     * The caller recycles the bitmap after the call returns. Throws [OcrFailed] when the engine cannot start.
     */
    fun recognize(bitmap: Bitmap, languages: String): String

    /** Asks a [recognize] running on another thread to give up early (its result is then not used). */
    fun stop() {}

    /** Releases the native engine. The engine must not be used afterwards. */
    fun close()
}

/** The OCR engine or its language data could not be prepared. */
class OcrFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The languages offered to the user, as Tesseract codes. */
enum class OcrLanguage(val codes: String) {
    TURKISH_ENGLISH("tur+eng"),
    TURKISH("tur"),
    ENGLISH("eng"),
}
