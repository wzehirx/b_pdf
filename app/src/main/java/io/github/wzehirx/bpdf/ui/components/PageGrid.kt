package io.github.wzehirx.bpdf.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.pdf.ThumbnailLoader
import java.io.File

/** Width thumbnails are drawn at; cells are at most this wide on phones. */
private val ThumbnailWidth = 160.dp

/** [ThumbnailWidth] in pixels for the current screen density. */
@Composable
fun rememberThumbnailWidthPx(): Int {
    val density = LocalDensity.current
    return remember(density) { with(density) { ThumbnailWidth.roundToPx() } }
}

/**
 * A thumbnail source for [file]: renders page `index` (0-based) through a [ThumbnailLoader] that
 * lives as long as the calling composition. Returns null thumbnails while [file] is null or when
 * a page cannot be drawn.
 */
@Composable
fun rememberPageThumbnails(file: File?, password: String? = null): suspend (Int) -> Bitmap? {
    val context = LocalContext.current
    val widthPx = rememberThumbnailWidthPx()
    val loader = remember(context) { ThumbnailLoader(File(context.cacheDir, "thumbnails")) }
    DisposableEffect(loader) { onDispose { loader.close() } }
    DisposableEffect(loader, file) { onDispose { file?.let(loader::release) } }
    return remember(loader, file, password, widthPx) {
        { index: Int -> file?.let { loader.renderOrNull(it, index, widthPx, password) } }
    }
}

/**
 * Scrollable grid of page thumbnails. Every cell is one focusable node whose label is
 * "Page N of M" (plus its rotation); the thumbnail itself is decorative. Cells are
 * checkboxes when [onToggle] is given (then "Select all" / "Clear selection" and a selected count
 * appear above the grid); otherwise they only carry [pageActions]. Thumbnails are requested by
 * each visible cell only, via [thumbnail] (called with the cell's page index); [thumbnailKey]
 * says when a cell has to ask again (default: when its index changes).
 *
 * [pageActions] supplies the screen-reader actions for a page; with [overflowMenu] the same actions
 * are also offered in a per-cell menu button. While not [enabled] nothing can be changed.
 * The grid scrolls inside a [gridHeight] tall area so it can sit in a scrolling screen.
 */
@Composable
fun PageGrid(
    pageCount: Int,
    thumbnail: suspend (Int) -> Bitmap?,
    selected: Set<Int>,
    onToggle: ((Int) -> Unit)?,
    rotations: Map<Int, Int>,
    pageActions: (Int) -> List<CustomAccessibilityAction>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelectAll: (() -> Unit)? = null,
    onClearSelection: (() -> Unit)? = null,
    overflowMenu: Boolean = false,
    thumbnailKey: (Int) -> Any = { it },
    gridHeight: Dp = 420.dp,
) {
    val resources = LocalResources.current
    val announce = rememberAnnouncer()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onToggle != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onSelectAll?.invoke()
                        announce(resources.getQuantityString(R.plurals.page_grid_selected_count, pageCount, pageCount))
                    },
                    enabled = enabled && onSelectAll != null && selected.size < pageCount,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.page_grid_select_all)) }
                OutlinedButton(
                    onClick = {
                        onClearSelection?.invoke()
                        announce(resources.getQuantityString(R.plurals.page_grid_selected_count, 0, 0))
                    },
                    enabled = enabled && onClearSelection != null && selected.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.page_grid_clear_selection)) }
            }
            // Plain text on purpose: single toggles are heard from the cell's own state; only the
            // bulk buttons above announce the new total.
            Text(
                pluralStringResource(R.plurals.page_grid_selected_count, selected.size, selected.size),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            modifier = Modifier.fillMaxWidth().height(gridHeight),
        ) {
            items((0 until pageCount).toList(), key = { it }) { index ->
                PageCell(
                    index = index,
                    pageCount = pageCount,
                    selected = index in selected,
                    selectable = onToggle != null,
                    enabled = enabled,
                    rotation = Math.floorMod(rotations[index] ?: 0, 360),
                    actions = if (enabled) pageActions(index) else emptyList(),
                    thumbnail = thumbnail,
                    thumbnailKey = thumbnailKey(index),
                    overflowMenu = overflowMenu,
                    onToggle = { onToggle?.invoke(index) },
                )
            }
        }
    }
}

@Composable
private fun PageCell(
    index: Int,
    pageCount: Int,
    selected: Boolean,
    selectable: Boolean,
    enabled: Boolean,
    rotation: Int,
    actions: List<CustomAccessibilityAction>,
    thumbnail: suspend (Int) -> Bitmap?,
    thumbnailKey: Any,
    overflowMenu: Boolean,
    onToggle: () -> Unit,
) {
    val label = if (rotation == 0) {
        stringResource(R.string.page_grid_cell, index + 1, pageCount)
    } else {
        stringResource(R.string.page_grid_cell_rotated, index + 1, pageCount, rotation)
    }
    val selectedText = stringResource(R.string.page_grid_selected)
    val notSelectedText = stringResource(R.string.page_grid_not_selected)
    val shape = RoundedCornerShape(8.dp)
    val border = if (selected) {
        BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    }
    Box(Modifier.padding(4.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(border, shape)
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    if (selectable) stateDescription = if (selected) selectedText else notSelectedText
                    customActions = actions
                }
                .then(
                    if (selectable) {
                        Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox) { onToggle() }
                    } else {
                        Modifier.focusable(enabled)
                    },
                )
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PageThumbnail(index, thumbnailKey, rotation, thumbnail)
            // The number is only a visual cue; the cell's label already says it.
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally).clearAndSetSemantics { },
            )
        }
        if (overflowMenu && actions.isNotEmpty()) {
            PageActionsMenu(index, actions, Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun PageThumbnail(index: Int, key: Any, rotation: Int, thumbnail: suspend (Int) -> Bitmap?) {
    val currentThumbnail by rememberUpdatedState(thumbnail)
    val currentIndex by rememberUpdatedState(index)
    var bitmap by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    // Runs only while the cell is composed, i.e. visible (plus the grid's small prefetch).
    LaunchedEffect(key) { bitmap = currentThumbnail(currentIndex)?.asImageBitmap() }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.75f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clip(RoundedCornerShape(4.dp)),
    ) {
        val turned = Modifier.fillMaxSize().graphicsLayer {
            rotationZ = rotation.toFloat()
            if (rotation % 180 != 0) {
                // A quarter turn swaps width and height; shrink so the page stays inside the cell.
                val s = minOf(size.width / size.height, size.height / size.width)
                scaleX = s
                scaleY = s
            }
        }
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = turned)
        }
    }
}

@Composable
private fun PageActionsMenu(index: Int, actions: List<CustomAccessibilityAction>, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.page_grid_actions_for_page, index + 1))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    onClick = {
                        open = false
                        action.action()
                    },
                )
            }
        }
    }
}
