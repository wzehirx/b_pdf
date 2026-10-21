package io.github.wzehirx.bpdf.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.pdf.PageSelectionText

/**
 * "Choose pages" alternative to typing a range: a switch that reveals a [PageGrid] whose
 * selection is the page range [rangeText]. Tapping a page, "Select all" or "Clear selection"
 * rewrites the text through [onRangeChange]; a valid text is what the grid shows as selected.
 */
@Composable
fun PageChooser(
    rangeText: String,
    pageCount: Int,
    onRangeChange: (String) -> Unit,
    thumbnail: suspend (Int) -> Bitmap?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = open, role = Role.Switch) { open = it },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.page_grid_choose_pages))
            Switch(checked = open, onCheckedChange = null)
        }
        if (open) {
            val selected = remember(rangeText, pageCount) { PageSelectionText.selected(rangeText, pageCount).toSet() }
            PageGrid(
                pageCount = pageCount,
                thumbnail = thumbnail,
                selected = selected,
                onToggle = { onRangeChange(PageSelectionText.toggle(rangeText, pageCount, it)) },
                rotations = emptyMap(),
                pageActions = { emptyList() },
                enabled = enabled,
                onSelectAll = { onRangeChange(PageSelectionText.all(pageCount)) },
                onClearSelection = { onRangeChange("") },
            )
        }
    }
}
