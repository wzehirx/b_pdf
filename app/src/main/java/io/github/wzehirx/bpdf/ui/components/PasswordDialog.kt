package io.github.wzehirx.bpdf.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.heading
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.android.awaitFrame
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.work.PasswordPrompt

@Composable
fun PasswordDialog(
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    wrongPassword: Boolean,
    /** Which file is asking, spoken before the field; omitted when null. */
    fileName: String? = null,
    /** Number of wrong passwords so far; every increase announces the error again. */
    attempt: Int = 0,
    /** True while the last password is being tested: OK is off and "Checking" is shown. */
    checking: Boolean = false,
) {
    // Deliberately not rememberSaveable: never write the password into saved instance state.
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val wrongMessage = stringResource(R.string.err_wrong_password)
    val focus = remember { FocusRequester() }
    val announce = rememberAnnouncer()
    val checkingText = stringResource(R.string.password_checking)
    // The one announcement mechanism of the app: says the error on every wrong attempt, not only the first.
    LaunchedEffect(attempt, wrongPassword) {
        if (wrongPassword) announce(wrongMessage)
    }
    // After a wrong password the field starts empty: a screen reader user would otherwise have to
    // delete the wrong text one character ("dot") at a time.
    LaunchedEffect(attempt) { if (attempt > 0) password = "" }
    val submit = { if (password.isNotEmpty() && !checking) onSubmit(password) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.password_title), Modifier.semantics { heading() }) },
        text = {
            // Inside the dialog's own composition so the requester is attached before use.
            LaunchedEffect(Unit) {
                awaitFrame()
                runCatching { focus.requestFocus() }
            }
            Column {
                if (checking) {
                    Text(checkingText, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (fileName != null) {
                    Text(stringResource(R.string.password_prompt_for, fileName))
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password_label)) },
                    singleLine = true,
                    isError = wrongPassword,
                    supportingText = if (wrongPassword) {
                        { Text(wrongMessage) }
                    } else {
                        null
                    },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    // A shown password is plain text for the keyboard too, so what was typed can be checked by ear.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (visible) KeyboardType.Text else KeyboardType.Password,
                        autoCorrectEnabled = if (visible) false else null,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                val stateText = stringResource(
                    if (visible) R.string.password_state_visible else R.string.password_state_hidden,
                )
                TextButton(
                    onClick = { visible = !visible },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = stateText },
                ) {
                    Text(stringResource(R.string.password_toggle))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = password.isNotEmpty() && !checking, modifier = Modifier.heightIn(min = 48.dp)) {
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

/** Shows [PasswordDialog] for [prompt] while there is one; a wrong attempt keeps the dialog and flags the field. */
@Composable
fun PasswordPromptDialog(prompt: PasswordPrompt?, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    if (prompt != null) PasswordDialog(onSubmit, onDismiss, prompt.wrong, prompt.fileName, prompt.attempt, prompt.checking)
}
