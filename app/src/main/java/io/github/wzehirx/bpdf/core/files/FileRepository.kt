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
    internal val appContext: Context get() = context
    private val importsDir get() = File(context.cacheDir, "imports").apply { mkdirs() }
    private val sharedDir get() = File(context.cacheDir, "shared").apply { mkdirs() }

    /** Copies the content behind [uri] into the app cache under a unique name. */
    suspend fun importToCache(uri: Uri, extension: String = "pdf"): ImportedFile =
        withContext(Dispatchers.IO) {
            val name = displayName(uri) ?: "document.$extension"
            val dest = File(importsDir, UUID.randomUUID().toString() + ".$extension")
            try {
                val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
                    "Cannot open $uri"
                }
                input.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            } catch (t: Throwable) {
                // Failed or cancelled: do not leave a partial copy behind.
                dest.delete()
                throw t
            }
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

    /**
     * A content:// Uri for [file] via the app's FileProvider. Files already under `shared/` are
     * shared in place unless [displayName] is given; otherwise a copy is placed at
     * `shared/<uuid>/<name>` so the receiving app sees a clean file name.
     */
    fun shareUri(file: File, displayName: String? = null): Uri {
        val shared = sharedDir.canonicalFile
        val inShared = file.canonicalPath.startsWith(shared.path + File.separator)
        val target = if (inShared && displayName == null) {
            file
        } else {
            val name = File(displayName?.takeIf { it.isNotBlank() } ?: file.name).name
            val dir = File(shared, UUID.randomUUID().toString()).apply { mkdirs() }
            File(dir, name).also { file.copyTo(it, overwrite = true) }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    }

    /** A fresh, unique output file in the shareable `shared/` directory. */
    fun newOutputFile(ext: String): File = File(sharedDir, "${UUID.randomUUID()}.$ext")

    /** A fresh, empty directory under `shared/` for tools that produce several files. */
    fun newOutputDir(): File = File(sharedDir, UUID.randomUUID().toString()).apply { mkdirs() }

    /** Deletes cache entries older than [maxAgeMillis]. */
    fun purgeStale(maxAgeMillis: Long, now: Long = System.currentTimeMillis()) {
        for (dir in listOf(importsDir, sharedDir)) {
            dir.listFiles().orEmpty()
                .filter { now - it.lastModified() > maxAgeMillis }
                .forEach { it.deleteRecursively() }
        }
    }

    fun clearCache() {
        importsDir.deleteRecursively()
        sharedDir.deleteRecursively()
    }
}
