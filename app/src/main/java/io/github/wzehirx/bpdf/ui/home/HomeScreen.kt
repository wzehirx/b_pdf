package io.github.wzehirx.bpdf.ui.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenTool: (Tool) -> Unit,
    modifier: Modifier = Modifier,
    isAvailable: (Tool) -> Boolean = { it.available },
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
