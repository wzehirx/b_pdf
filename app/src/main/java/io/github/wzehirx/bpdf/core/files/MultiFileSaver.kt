package io.github.wzehirx.bpdf.core.files

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saves the several output files of a tool (split parts, page pictures) to a folder chosen with
 * `OpenDocumentTree` or as one zip, for the ViewModels of those tools. The ViewModel marks its own
 * `saving` state before calling [toFolder] / [toZip]; exactly one of [Callbacks.onSaved] (with the
 * number of files written) or [Callbacks.onError] runs, then [Callbacks.onFinished]. Cancellation of
 * [scope] is not reported as an error.
 */
class MultiFileSaver(
    private val fileRepository: FileRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    class Callbacks(
        val onSaved: suspend (count: Int) -> Unit,
        val onError: (Throwable) -> Unit,
        val onFinished: () -> Unit,
    )

    /** Writes every file into the folder [treeUri]; a failure removes what this batch wrote. */
    fun toFolder(scope: CoroutineScope, files: List<File>, treeUri: Uri, callbacks: Callbacks) =
        launch(scope, callbacks) { fileRepository.exportToTree(files, treeUri) }

    /** Writes all files as one zip to [target]; the temporary zip is always deleted. */
    fun toZip(scope: CoroutineScope, files: List<File>, target: Uri, callbacks: Callbacks) =
        launch(scope, callbacks) {
            val zip = fileRepository.newOutputFile("zip")
            try {
                withContext(ioDispatcher) { zipFiles(files, zip) }
                fileRepository.export(zip, target)
            } finally {
                zip.delete()
            }
            files.size
        }

    private fun launch(scope: CoroutineScope, callbacks: Callbacks, block: suspend () -> Int) {
        scope.launch {
            try {
                callbacks.onSaved(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                callbacks.onError(e)
            } finally {
                callbacks.onFinished()
            }
        }
    }
}
