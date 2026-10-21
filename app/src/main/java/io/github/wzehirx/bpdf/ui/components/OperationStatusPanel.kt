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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.work.OperationState

/**
 * Shows progress / result / error for a long-running operation. Progress and success are
 * polite live regions, errors assertive; focus moves to the result text so screen-reader users
 * land on the outcome.
 */
@Composable
fun OperationStatusPanel(
    state: OperationState<*>,
    successText: (Any?) -> String,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalContext.current.resources
    val focus = remember { FocusRequester() }
    val finished = state is OperationState.Success<*> || state is OperationState.Failed
    LaunchedEffect(if (finished) state else null) {
        if (finished) focus.requestFocus()
    }
    val resultModifier = Modifier.fillMaxWidth().focusRequester(focus).focusable()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            OperationState.Idle -> Unit
            is OperationState.Running -> {
                val p = state.progress?.coerceIn(0f, 1f)
                Text(
                    text = if (p == null) {
                        stringResource(R.string.working)
                    } else {
                        stringResource(R.string.working_percent, (p * 100).toInt())
                    },
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
            is OperationState.Success<*> -> {
                Text(
                    text = successText(state.value),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = resultModifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                SaveResultActions(onSave = onSave, onShare = onShare)
            }
            is OperationState.Failed -> {
                Text(
                    text = state.error.toUserMessage(resources),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = resultModifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
                OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}
