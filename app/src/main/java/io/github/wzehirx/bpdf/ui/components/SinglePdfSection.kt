package io.github.wzehirx.bpdf.ui.components

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.files.ImportedFile

/** Picker button, "opening" note and the chosen-file card shared by the single-PDF tools. */
@Composable
fun SinglePdfSection(
    file: ImportedFile?,
    pageCount: Int,
    importing: Boolean,
    busy: Boolean,
    onPick: (List<Uri>) -> Unit,
    onRemove: () -> Unit,
) {
    PickFilesButton(
        label = stringResource(if (file == null) R.string.single_file_choose else R.string.single_file_replace),
        mimeTypes = arrayOf("application/pdf"),
        multiple = false,
        onPicked = onPick,
        enabled = !busy,
    )
    if (importing) {
        Text(
            stringResource(R.string.single_file_importing),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (file != null) {
        SelectedPdfCard(
            file = file,
            pageCount = pageCount,
            summary = pluralStringResource(R.plurals.single_file_summary, pageCount, file.displayName, pageCount),
            removeActionLabel = stringResource(R.string.single_file_remove_action),
            removeButtonDescription = stringResource(R.string.single_file_remove, file.displayName),
            enabled = !busy,
            onRemove = onRemove,
        )
    }
}
