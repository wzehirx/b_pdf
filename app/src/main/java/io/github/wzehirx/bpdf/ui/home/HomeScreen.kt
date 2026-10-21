package io.github.wzehirx.bpdf.ui.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.files.RecentFile
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.ui.Tool
import io.github.wzehirx.bpdf.ui.ToolCategory

/** Stateful home: the tools plus the recent files from [HomeViewModel]. */
@Composable
fun HomeRoute(
    onOpenTool: (Tool) -> Unit,
    onOpenRecent: (RecentFile) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val recents by viewModel.recents.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val announce = rememberAnnouncer()
    HomeScreen(
        onOpenTool = onOpenTool,
        recents = recents,
        onOpenRecent = onOpenRecent,
        onRemoveRecent = {
            viewModel.remove(it)
            announce(resources.getString(R.string.home_recent_removed, it.name))
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenTool: (Tool) -> Unit,
    modifier: Modifier = Modifier,
    isAvailable: (Tool) -> Boolean = { it.available },
    recents: List<RecentFile> = emptyList(),
    onOpenRecent: (RecentFile) -> Unit = {},
    onRemoveRecent: (RecentFile) -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        modifier = Modifier.semantics { heading() },
                    )
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (recents.isNotEmpty()) {
                item(key = "recent_header", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.home_recent_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                    )
                }
                items(recents, key = { "recent_${it.uri}" }, span = { GridItemSpan(maxLineSpan) }) { file ->
                    RecentRow(file, onOpen = { onOpenRecent(file) }, onRemove = { onRemoveRecent(file) })
                }
            }
            ToolCategory.entries.forEach { category ->
                item(key = "category_${category.name}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(category.title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                    )
                }
                items(Tool.entries.filter { it.category == category }, key = { "tool_${it.name}" }) { tool ->
                    ToolCard(tool, isAvailable(tool), onOpenTool)
                }
            }
        }
    }
}

/** One recent file: opens in the reader; can be removed with a screen-reader action or the visible button. */
@Composable
private fun RecentRow(file: RecentFile, onOpen: () -> Unit, onRemove: () -> Unit) {
    val openLabel = stringResource(R.string.home_recent_open)
    val removeAction = stringResource(R.string.home_recent_remove_action)
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
                    .semantics { customActions = listOf(CustomAccessibilityAction(removeAction) { onRemove(); true }) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.home_recent_remove, file.name))
            }
        }
    }
}

@Composable
private fun ToolCard(tool: Tool, available: Boolean, onOpen: (Tool) -> Unit) {
    val title = stringResource(tool.title)
    val description = stringResource(tool.description)
    val comingSoon = stringResource(R.string.tool_coming_soon)
    val label = "$title. $description"
    val base = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    val interaction = if (available) {
        base.clickable(role = Role.Button) { onOpen(tool) }
            .semantics(mergeDescendants = true) { contentDescription = label }
    } else {
        // Still focusable so screen-reader and keyboard users can discover it, but inert.
        base.focusable().semantics(mergeDescendants = true) {
            contentDescription = label
            role = Role.Button
            stateDescription = comingSoon
            disabled()
        }
    }
    Card(
        modifier = interaction,
        colors = CardDefaults.cardColors(
            containerColor = if (available) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp).alpha(if (available) 1f else 0.7f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(tool.icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
