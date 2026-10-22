package io.github.wzehirx.bpdf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.files.ImportedFile

/**
 * The single chosen PDF of a one-file tool: [summary] (name and page count) with a remove button
 * labelled [removeButtonDescription] and, while [enabled], a screen-reader action named [removeActionLabel].
 *
 * The card speaks for the file, because the picker returns silently: once per [file] it announces
 * "<name> loaded, N pages" ([pageCount] null = no count to give; not repeated after a rotation), and
 * removing it announces "<name> removed" and moves focus up to the picker before the card goes.
 */
@Composable
fun SelectedPdfCard(
    file: ImportedFile,
    pageCount: Int?,
    summary: String,
    removeActionLabel: String,
    removeButtonDescription: String,
    enabled: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val announce = rememberAnnouncer()
    val focusManager = LocalFocusManager.current
    var spokenFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(file.file.path) {
        if (spokenFor != file.file.path) {
            spokenFor = file.file.path
            announce(
                if (pageCount != null) {
                    resources.getQuantityString(R.plurals.single_file_loaded, pageCount, file.displayName, pageCount)
                } else {
                    resources.getString(R.string.single_file_loaded_no_count, file.displayName)
                },
            )
        }
    }
    val remove = {
        announce(resources.getString(R.string.single_file_removed, file.displayName))
        focusManager.moveFocus(FocusDirection.Up)
        onRemove()
    }
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                .semantics(mergeDescendants = true) {
                    if (enabled) {
                        customActions = listOf(CustomAccessibilityAction(removeActionLabel) { remove(); true })
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PdfBadge()
            Text(
                summary,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp),
            )
            IconButton(onClick = remove, enabled = enabled) {
                Icon(Icons.Default.Close, contentDescription = removeButtonDescription)
            }
        }
    }
}

/** The small tinted "PDF" tile shown at the start of a file card. Decorative: the card's text already names the file. */
@Composable
internal fun PdfBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp))
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "PDF",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
