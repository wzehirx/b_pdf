package io.github.wzehirx.bpdf.ui.components

import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The single chosen PDF of a one-file tool: [summary] (name and page count) with a remove button
 * labelled [removeButtonDescription] and, while [enabled], a screen-reader action named [removeActionLabel].
 */
@Composable
fun SelectedPdfCard(
    summary: String,
    removeActionLabel: String,
    removeButtonDescription: String,
    enabled: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .padding(start = 16.dp, end = 4.dp)
                .semantics(mergeDescendants = true) {
                    if (enabled) {
                        customActions = listOf(CustomAccessibilityAction(removeActionLabel) { onRemove(); true })
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(Icons.Default.Close, contentDescription = removeButtonDescription)
            }
        }
    }
}
