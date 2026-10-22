package io.github.wzehirx.bpdf.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.files.RecentFile
import io.github.wzehirx.bpdf.ui.Tool
import io.github.wzehirx.bpdf.ui.ToolCategory

/** Stateful home: the tools plus the recent files from [HomeViewModel]. */
@Composable
fun HomeRoute(
    onOpenTool: (Tool) -> Unit,
    onOpenRecent: (RecentFile) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val recents by viewModel.recents.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val announce = rememberAnnouncer()
    HomeScreen(
        onOpenTool = onOpenTool,
        recents = recents,
        onOpenRecent = onOpenRecent,
        onOpenSettings = onOpenSettings,
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
    onOpenSettings: () -> Unit = {},
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
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // Two equal columns of rows from 600dp up, one column on phones.
            val columns = if (maxWidth >= 600.dp) 2 else 1
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp),
            ) {
                // Always the first item (empty when there are none): recents that load a moment later appear at the
                // top instead of pushing the list down, because a lazy list keeps its first visible item in place.
                item(key = "recent") {
                    if (recents.isNotEmpty()) {
                        HomeSection(stringResource(R.string.home_recent_title), Modifier.padding(bottom = 24.dp)) {
                            recents.forEachIndexed { index, file ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                RecentRow(file, onOpen = { onOpenRecent(file) }, onRemove = { onRemoveRecent(file) })
                            }
                        }
                    }
                }
                ToolCategory.entries.forEach { category ->
                    item(key = "category_${category.name}") {
                        HomeSection(stringResource(category.title), Modifier.padding(bottom = 24.dp)) {
                            Tool.entries.filter { it.category == category }.chunked(columns).forEachIndexed { index, rowTools ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Row(Modifier.height(IntrinsicSize.Min)) {
                                    rowTools.forEachIndexed { i, tool ->
                                        if (i > 0) VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        ToolRow(tool, isAvailable(tool), onOpenTool, Modifier.weight(1f).fillMaxHeight())
                                    }
                                    if (rowTools.size < columns) Spacer(Modifier.weight((columns - rowTools.size).toFloat()))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A heading (titleMedium) above a rounded surface that groups its rows, separated by dividers. */
@Composable
private fun HomeSection(title: String, modifier: Modifier = Modifier, rows: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column { rows() }
        }
    }
}

/** One recent file: opens in the reader; can be removed with a screen-reader action or the visible button. */
@Composable
private fun RecentRow(file: RecentFile, onOpen: () -> Unit, onRemove: () -> Unit) {
    val openLabel = stringResource(R.string.home_recent_open)
    val removeAction = stringResource(R.string.home_recent_remove_action)
    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = file.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
                .semantics { customActions = listOf(CustomAccessibilityAction(removeAction) { onRemove(); true }) }
                .padding(vertical = 16.dp),
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.home_recent_remove, file.name))
        }
    }
}

/**
 * One tool as a list row: tinted icon, title, description, chevron. The row is a single merged item, so a screen reader
 * speaks the visible title and description once (no extra contentDescription); the icon and chevron are decorative.
 */
@Composable
private fun ToolRow(tool: Tool, available: Boolean, onOpen: (Tool) -> Unit, modifier: Modifier = Modifier) {
    val comingSoon = stringResource(R.string.tool_coming_soon)
    val base = modifier.heightIn(min = 72.dp)
    val interaction = if (available) {
        base.clickable(role = Role.Button) { onOpen(tool) }
    } else {
        // Still focusable so screen-reader and keyboard users can discover it, but inert.
        base.focusable().semantics(mergeDescendants = true) {
            role = Role.Button
            stateDescription = comingSoon
            disabled()
        }
    }
    val container = if (available) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val onContainer = if (available) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = interaction.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            tool.icon,
            contentDescription = null,
            tint = onContainer,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(container)
                .padding(8.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(tool.title), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            Text(
                stringResource(tool.description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (available) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // The state is already spoken as "Coming soon"; this is the visible counterpart.
            Text(
                comingSoon,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}
