package io.github.wzehirx.bpdf.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Button that opens the system document picker for [mimeTypes]. */
@Composable
fun PickFilesButton(
    label: String,
    mimeTypes: Array<String>,
    multiple: Boolean,
    onPicked: (List<Uri>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(onPicked)
    val single = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) current(listOf(it))
    }
    val many = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) current(it)
    }
    Button(
        onClick = { if (multiple) many.launch(mimeTypes) else single.launch(mimeTypes) },
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Text(label)
    }
}
