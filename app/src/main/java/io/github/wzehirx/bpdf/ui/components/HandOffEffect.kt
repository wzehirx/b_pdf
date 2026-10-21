package io.github.wzehirx.bpdf.ui.components

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Hands a file given to the screen on opening (the viewer's "Open with tool", or a file opened from
 * another app) to [onUri] exactly once. The "already handed over" flag survives rotation and process
 * death, so the file is not picked again.
 */
@Composable
fun HandOffEffect(uri: Uri?, onUri: (Uri) -> Unit) {
    var handedOver by rememberSaveable(uri.toString()) { mutableStateOf(false) }
    val current by rememberUpdatedState(onUri)
    LaunchedEffect(uri, handedOver) {
        if (uri != null && !handedOver) {
            handedOver = true
            current(uri)
        }
    }
}
