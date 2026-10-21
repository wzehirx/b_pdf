package io.github.wzehirx.bpdf.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.wzehirx.bpdf.R

@Composable
fun PasswordDialog(
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    wrongPassword: Boolean,
) {
    // Deliberately not rememberSaveable: never write the password into saved instance state.
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val wrongMessage = stringResource(R.string.err_wrong_password)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.password_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password_label)) },
                    singleLine = true,
                    isError = wrongPassword,
                    supportingText = if (wrongPassword) {
                        { Text(wrongMessage, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
                    } else {
                        null
                    },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit(password) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                val stateText = stringResource(
                    if (visible) R.string.password_state_visible else R.string.password_state_hidden,
                )
                TextButton(
                    onClick = { visible = !visible },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = stateText },
                ) {
                    Text(stringResource(if (visible) R.string.password_hide else R.string.password_show))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
