package io.github.wzehirx.bpdf.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Returns a function that opens the system "save as" picker with a suggested file name;
 * [onResult] receives the chosen Uri, or null when the user cancelled.
 */
@Composable
fun rememberSaveDocumentLauncher(mimeType: String, onResult: (Uri?) -> Unit): (suggestedName: String) -> Unit {
    val current by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) {
        current(it)
    }
    return { name -> launcher.launch(name) }
}

/** Opens the system share sheet for [uri] (a FileProvider Uri). Returns false if nothing can handle it. */
fun Context.shareFile(uri: Uri, mimeType: String, chooserTitle: String): Boolean {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = android.content.ClipData.newRawUri(null, uri)
    }
    val chooser = Intent.createChooser(send, chooserTitle).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
