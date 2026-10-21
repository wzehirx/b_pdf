package io.github.wzehirx.bpdf.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.wzehirx.bpdf.R

@Composable
fun SaveResultActions(onSave: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onSave, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.save))
        }
        OutlinedButton(onClick = onShare, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.share))
        }
    }
}
