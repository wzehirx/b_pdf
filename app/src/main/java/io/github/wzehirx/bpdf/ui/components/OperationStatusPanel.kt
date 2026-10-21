package io.github.wzehirx.bpdf.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.work.OperationState

/**
 * Shows progress / result / error for a long-running operation. Progress and success are
 * announced once at start; focus (not a live region, to avoid double speech) moves to the
 * result or error text so screen-reader users land on the outcome.
 */
@Composable
fun <T> OperationStatusPanel(
    state: OperationState<T>,
    successText: (T) -> String,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onSave: () -> Unit = {},
    onShare: () -> Unit = {},
    actionsEnabled: Boolean = true,
    /** True when an input needed a password: the saved copy has none, and the result text says so. */
    unprotectedOutput: Boolean = false,
    /** What to show (and announce politely whenever it changes) instead of "Working… N%" while running, e.g. the current page. */
    runningLabel: String? = null,
    successActions: @Composable () -> Unit = { SaveResultActions(onSave = onSave, onShare = onShare, enabled = actionsEnabled) },
) {
    val resources = LocalResources.current
    val focus = remember { FocusRequester() }
    val finished = state is OperationState.Success<*> || state is OperationState.Failed
    LaunchedEffect(if (finished) state else null) {
        if (finished) focus.requestFocus()
    }
    // Announce the start once; progress itself is conveyed by the progress bar semantics.
    val announce = rememberAnnouncer()
    val workingText = stringResource(R.string.working)
    val running = state is OperationState.Running
    LaunchedEffect(running) { if (running) announce(workingText) }
    val resultModifier = Modifier.fillMaxWidth().focusRequester(focus).focusable()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            OperationState.Idle -> Unit
            is OperationState.Running -> {
                val p = state.progress?.coerceIn(0f, 1f)
                Text(
                    text = runningLabel ?: if (p == null) {
                        stringResource(R.string.working)
                    } else {
                        stringResource(R.string.working_percent, (p * 100).toInt())
                    },
                    modifier = if (runningLabel != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                )
                if (p == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cancel))
                }
            }
            is OperationState.Success -> {
                Text(
                    text = successText(state.value).withUnprotectedNote(resources, unprotectedOutput),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = resultModifier,
                )
                successActions()
            }
            is OperationState.Failed -> {
                Text(
                    text = state.error.toUserMessage(resources),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = resultModifier,
                )
                OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}
