package io.github.wzehirx.bpdf.core.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ImportedFile(val file: File, val displayName: String, val sizeBytes: Long)

class FileRepository(context: Context) {
    private val context = context.applicationContext
    private val importsDir get() = File(context.cacheDir, "imports").apply { mkdirs() }
    private val sharedDir get() = File(context.cacheDir, "shared").apply { mkdirs() }

    /** Copies the content behind [uri] into the app cache under a unique name. */
    suspend fun importToCache(uri: Uri): ImportedFile = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "document.pdf"
        val dest = File(importsDir, UUID.randomUUID().toString() + ".pdf")
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open $uri" }
        input.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        ImportedFile(dest, name, dest.length())
    }

    fun displayName(uri: Uri): String? {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
                }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
    }

    suspend fun export(file: File, target: Uri) = withContext(Dispatchers.IO) {
        val out = requireNotNull(context.contentResolver.openOutputStream(target, "wt")) {
            "Cannot open $target"
        }
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** A content:// Uri for [file] via the app's FileProvider; copies into `shared/` if needed. */
    fun shareUri(file: File): Uri {
        val shared = sharedDir.canonicalFile
        val inShared = file.canonicalFile.parentFile == shared
        val target = if (inShared) file else File(shared, UUID.randomUUID().toString() + "_" + file.name)
            .also { file.copyTo(it, overwrite = true) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    }

    /** A fresh, unique output file in the shareable `shared/` directory. */
    fun newOutputFile(ext: String): File = File(sharedDir, "${UUID.randomUUID()}.$ext")

    fun clearCache() {
        importsDir.deleteRecursively()
        sharedDir.deleteRecursively()
    }
}
