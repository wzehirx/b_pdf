package io.github.wzehirx.bpdf.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Single-choice radio group with a plain (non-heading) [label] as its title. */
@Composable
fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Optional text a screen reader speaks instead of [optionLabel] (for labels that contain symbols such as "/"). */
    optionDescription: ((T) -> String?)? = null,
) {
    Column(modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        options.forEach { option ->
            val label = optionLabel(option)
            // A spoken replacement only matters when it differs; then the visible label is hidden from screen readers.
            val spoken = optionDescription?.invoke(option)?.takeIf { it != label }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = option == selected, enabled = enabled, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = 4.dp)
                    .semantics { spoken?.let { contentDescription = it } },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = option == selected, onClick = null, enabled = enabled)
                // With a spoken replacement the visible label is hidden from screen readers, or both would be read.
                Text(label, modifier = if (spoken != null) Modifier.clearAndSetSemantics { } else Modifier)
            }
        }
    }
}
