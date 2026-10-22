package io.github.wzehirx.bpdf.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.settings.SaveBehavior

/** The "default save behavior" setting, provided once at the top of the app. */
val LocalSaveBehavior = compositionLocalOf { SaveBehavior.ASK }

/** Save and Share side by side, both always available; with the Share preference, Share comes first as the filled primary button. */
@Composable
fun SaveResultActions(onSave: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shareFirst = LocalSaveBehavior.current == SaveBehavior.SHARE
    val buttons = Modifier.heightIn(min = 48.dp)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (shareFirst) {
            Button(onClick = onShare, enabled = enabled, modifier = buttons.weight(1f)) { Text(stringResource(R.string.share)) }
            OutlinedButton(onClick = onSave, enabled = enabled, modifier = buttons.weight(1f)) { Text(stringResource(R.string.save)) }
        } else {
            Button(onClick = onSave, enabled = enabled, modifier = buttons.weight(1f)) { Text(stringResource(R.string.save)) }
            OutlinedButton(onClick = onShare, enabled = enabled, modifier = buttons.weight(1f)) { Text(stringResource(R.string.share)) }
        }
    }
}
