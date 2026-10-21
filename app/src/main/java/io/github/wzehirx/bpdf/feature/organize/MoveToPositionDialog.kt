package io.github.wzehirx.bpdf.feature.organize

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.wzehirx.bpdf.R

/** Parses a typed position; only whole numbers from 1 to [count] are valid. */
internal fun parsePosition(text: String, count: Int): Int? =
    text.trim().takeIf { s -> s.isNotEmpty() && s.all { it in '0'..'9' } }?.toIntOrNull()?.takeIf { it in 1..count }

/**
 * Asks where page [current] (1-based) of [count] should go. [onConfirm] gets a valid 1-based
 * position; an empty or invalid entry shows a specific error on the field and keeps the dialog open.
 */
@Composable
internal fun MoveToPositionDialog(
    current: Int,
    count: Int,
    onConfirm: (position: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current.toString()) }
    var attempted by remember { mutableStateOf(false) }
    val position = parsePosition(text, count)
    val message = when {
        position != null -> null
        text.isBlank() -> if (attempted) stringResource(R.string.organize_position_empty, count) else null
        else -> stringResource(R.string.organize_position_invalid, text.trim(), count)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.organize_move_title, current)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.organize_position_label)) },
                    isError = message != null,
                    supportingText = { Text(message ?: stringResource(R.string.organize_position_hint, count)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { if (message != null) error(message) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (position != null) onConfirm(position) else attempted = true
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.organize_move_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
