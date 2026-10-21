package io.github.wzehirx.bpdf.core.ocr

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Puts the bundled Tesseract language files where Tesseract reads them: `<dataDir>/tessdata/<code>.traineddata`.
 * A file is copied on first use (written to a temporary name and renamed, so a copy that was interrupted is never taken
 * for a good one) and again when its size no longer matches [expectedSizes].
 *
 * [openAsset] opens `tessdata/<code>.traineddata` from the app's assets.
 */
class TessDataInstaller(
    private val dataDir: File,
    private val openAsset: (String) -> InputStream,
    private val expectedSizes: Map<String, Long> = BUNDLED_SIZES,
) {
    /**
     * Makes sure the files for [languages] (codes joined with `+`) are installed and returns [dataDir], the path to give
     * Tesseract. Throws [OcrFailed] when a language is unknown or a file cannot be written.
     */
    @Synchronized
    fun ensure(languages: String): File {
        val folder = File(dataDir, FOLDER)
        if (!folder.isDirectory && !folder.mkdirs()) throw OcrFailed("Cannot create $folder")
        for (code in languages.split('+').filter { it.isNotBlank() }) {
            val expected = expectedSizes[code] ?: throw OcrFailed("Unknown language $code")
            val target = File(folder, "$code.traineddata")
            if (target.isFile && target.length() == expected) continue
            install(code, target, expected)
        }
        return dataDir
    }

    private fun install(code: String, target: File, expected: Long) {
        val temp = File(target.parentFile, "$code.traineddata.tmp")
        try {
            openAsset("$FOLDER/$code.traineddata").use { input ->
                temp.outputStream().buffered().use { input.copyTo(it) }
            }
            if (temp.length() != expected) throw IOException("$code.traineddata has ${temp.length()} bytes, expected $expected")
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            throw OcrFailed("Could not install the $code language data", e)
        } finally {
            temp.delete()
        }
    }

    companion object {
        private const val FOLDER = "tessdata"

        /** Sizes, in bytes, of the bundled tessdata_fast files (see the notice next to them). */
        val BUNDLED_SIZES: Map<String, Long> = mapOf("eng" to 4_113_088L, "tur" to 4_550_554L)
    }
}
