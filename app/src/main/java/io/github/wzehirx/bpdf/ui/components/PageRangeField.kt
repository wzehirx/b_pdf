package io.github.wzehirx.bpdf.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.pdf.PdfError

@Composable
fun PageRangeField(
    value: String,
    onValueChange: (String) -> Unit,
    pageCount: Int,
    error: PdfError.InvalidRange?,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val errorMessage = error?.toMessage(resources)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.page_range_label)) },
        isError = errorMessage != null,
        supportingText = {
            if (errorMessage != null) {
                Text(errorMessage)
            } else {
                Text(pluralStringResource(R.plurals.page_range_hint, pageCount, pageCount))
            }
        },
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (errorMessage != null) error(errorMessage) },
    )
}
