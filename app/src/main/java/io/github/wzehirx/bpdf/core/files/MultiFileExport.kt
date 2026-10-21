package io.github.wzehirx.bpdf.core.files

import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

/** Saving to a folder failed; every document created by this batch was removed again (best effort). */
class ExportFailed(val writtenBeforeFailure: Int, cause: Throwable) : IOException("Export failed", cause)

/**
 * Creates one document per file inside the folder chosen with `OpenDocumentTree` ([treeUri]) and
 * copies the contents. Returns the number of files written. If any file fails, the documents
 * created so far are deleted and [ExportFailed] is thrown; cancellation also cleans up and
 * rethrows the [kotlinx.coroutines.CancellationException].
 */
suspend fun FileRepository.exportToTree(files: List<File>, treeUri: Uri): Int =
    withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw ExportFailed(0, IOException("Cannot write to $treeUri"))
        val created = ArrayList<DocumentFile>()
        var written = 0
        try {
            for (file in files) {
                ensureActive()
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                    ?: "application/octet-stream"
                val doc = tree.createFile(mime, file.name) ?: throw IOException("Cannot create ${file.name}")
                created += doc
                val out = requireNotNull(appContext.contentResolver.openOutputStream(doc.uri, "wt")) {
                    "Cannot open ${doc.uri}"
                }
                out.use { o -> file.inputStream().use { it.copyTo(o) } }
                written++
            }
            written
        } catch (t: Throwable) {
            created.forEach { runCatching { it.delete() } }
            if (t is CancellationException) throw t
            throw ExportFailed(written, t)
        }
    }
