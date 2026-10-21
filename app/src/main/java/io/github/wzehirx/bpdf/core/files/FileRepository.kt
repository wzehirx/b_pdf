package io.github.wzehirx.bpdf.core.files

import android.content.Context
import android.content.Intent
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
    private val cameraDir get() = File(context.cacheDir, "camera").apply { mkdirs() }

    // Decrypted copies PdfRenderer reads from (thumbnails, viewer); normally deleted when closed,
    // purged here when the app was killed first.
    private val renderDirs get() = listOf("thumbnails", "viewer").map { File(context.cacheDir, it) }

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

    /**
     * Asks the system to keep read access to [uri] across restarts, so it can be reopened from the
     * recent files list. [intentFlags] are the flags of the intent that delivered the Uri (an
     * "open with" request); the permission is only taken when they allow it. Pass null for a Uri
     * from the document picker. Returns false, and never throws, when the Uri cannot be kept.
     */
    fun tryPersistReadPermission(uri: Uri, intentFlags: Int? = null): Boolean {
        if (uri.scheme != "content") return false
        if (intentFlags != null && intentFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return false
        return try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: Exception) {
            false // SecurityException, UnsupportedOperationException, IllegalArgumentException...
        }
    }

    /** True when this app still holds a persisted read permission for [uri]. */
    fun hasPersistedReadPermission(uri: Uri): Boolean =
        try {
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        } catch (_: Exception) {
            false
        }

    /** Gives back a permission taken with [tryPersistReadPermission]; the system caps how many an app may hold. */
    fun releasePersistedPermission(uri: Uri) {
        if (uri.scheme != "content") return
        try {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Nothing was persisted for it.
        }
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

    /** A fresh, empty file the camera app writes a photo into (see [cameraUri]). */
    fun newCameraFile(): File = File(cameraDir, "${UUID.randomUUID()}.jpg").also { it.createNewFile() }

    /** The FileProvider Uri of a [newCameraFile], to hand to the camera app as its output. */
    fun cameraUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** A fresh, unique output file in the shareable `shared/` directory. */
    fun newOutputFile(ext: String): File = File(sharedDir, "${UUID.randomUUID()}.$ext")

    /** A fresh, empty directory under `shared/` for tools that produce several files. */
    fun newOutputDir(): File = File(sharedDir, UUID.randomUUID().toString()).apply { mkdirs() }

    /** Deletes cache entries older than [maxAgeMillis]. */
    fun purgeStale(maxAgeMillis: Long, now: Long = System.currentTimeMillis()) {
        for (dir in listOf(importsDir, sharedDir, cameraDir) + renderDirs) {
            dir.listFiles().orEmpty()
                .filter { now - it.lastModified() > maxAgeMillis }
                .forEach { it.deleteRecursively() }
        }
    }

    fun clearCache() {
        importsDir.deleteRecursively()
        sharedDir.deleteRecursively()
        cameraDir.deleteRecursively()
        renderDirs.forEach { it.deleteRecursively() }
    }
}
