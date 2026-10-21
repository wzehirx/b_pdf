package io.github.wzehirx.bpdf.feature.pdftext

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.pdf.PageText
import io.github.wzehirx.bpdf.ui.components.ComposeWhenNear

/**
 * The text of [pages], one after the other, each under a "Page N" heading (so heading navigation jumps page to page).
 * Every heading is composed at once; the text under it is built, and so read, only near the visible area. Shared by
 * PDF to text and OCR.
 */
@Composable
fun PageTextList(pages: List<PageText>, @StringRes emptyPageText: Int = R.string.text_page_empty) {
    pages.forEach { PageTextItem(it, emptyPageText) }
}

/** One page: a heading ("Page N", so heading navigation jumps page to page) and its selectable text. */
@Composable
private fun PageTextItem(page: PageText, @StringRes emptyPageText: Int) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.text_page_header, page.pageIndex + 1),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (page.isBlank) {
            Text(stringResource(emptyPageText), style = MaterialTheme.typography.bodyMedium)
        } else {
            // Long text is only built when the page nears the window; the heading above is always there.
            ComposeWhenNear(minHeight = 48.dp) {
                SelectionContainer { Text(page.text, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}
