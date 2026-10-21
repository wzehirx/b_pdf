package io.github.wzehirx.bpdf.core.files

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes [files] into one zip at [out]; entry names are the file names. */
fun zipFiles(files: List<File>, out: File) {
    try {
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    } catch (t: Throwable) {
        out.delete()
        throw t
    }
}

/**
 * Creates one document per file inside the folder chosen with `OpenDocumentTree` ([treeUri]) and
 * copies the contents. Returns the number of files written. A document that fails midway is
 * deleted again before the error propagates.
 */
suspend fun FileRepository.exportToTree(files: List<File>, treeUri: Uri): Int =
    withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IOException("Cannot write to $treeUri")
        var count = 0
        for (file in files) {
            val isPdf = file.extension.equals("pdf", ignoreCase = true)
            // Providers append the extension for a known mime type, so pass the bare name then.
            val doc = tree.createFile(
                if (isPdf) "application/pdf" else "application/octet-stream",
                if (isPdf) file.nameWithoutExtension else file.name,
            ) ?: throw IOException("Cannot create ${file.name}")
            try {
                val out = requireNotNull(appContext.contentResolver.openOutputStream(doc.uri, "wt")) {
                    "Cannot open ${doc.uri}"
                }
                out.use { o -> file.inputStream().use { it.copyTo(o) } }
            } catch (t: Throwable) {
                doc.delete()
                throw t
            }
            count++
        }
        count
    }
