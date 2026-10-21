package io.github.wzehirx.bpdf.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.reorderable
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.files.ImportedFile

/**
 * Ordered list of chosen files. Each row is one screen-reader item (name, size, position) with
 * TalkBack custom actions, plus visible move up/down/remove buttons for everyone else.
 */
@Composable
fun SelectedFileList(
    files: List<ImportedFile>,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val announce = rememberAnnouncer()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        files.forEachIndexed { index, file ->
            val move: (Int, Int) -> Unit = { from, to ->
                onMove(from, to)
                announce(resources.getString(R.string.file_moved_announcement, file.displayName, to + 1, files.size))
            }
            key(file.file.path) {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .padding(start = 16.dp, end = 4.dp)
                            .semantics(mergeDescendants = true) {}
                            .then(if (enabled) Modifier.reorderable(index, files.size, move) { onRemove(index) } else Modifier),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(file.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                Formatter.formatShortFileSize(context, file.sizeBytes) + " · " +
                                    stringResource(R.string.file_position, index + 1, files.size),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { move(index, index - 1) }, enabled = enabled && index > 0) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.file_move_up_named, file.displayName),
                            )
                        }
                        IconButton(onClick = { move(index, index + 1) }, enabled = enabled && index < files.size - 1) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.file_move_down_named, file.displayName),
                            )
                        }
                        IconButton(onClick = { onRemove(index) }, enabled = enabled) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.file_remove_named, file.displayName),
                            )
                        }
                    }
                }
            }
        }
    }
}
