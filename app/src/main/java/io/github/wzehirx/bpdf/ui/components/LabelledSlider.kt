package io.github.wzehirx.bpdf.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics

/**
 * A slider with a visible "[label]: [valueText]" line. The slider is one accessibility element named [label]
 * whose state is the spoken [valueText] (value and unit, e.g. "48 points"), and it can be adjusted in
 * [step] increments with swipes or the volume keys; the visible line is hidden from screen readers to
 * avoid reading everything twice.
 */
@Composable
fun LabelledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    valueText: (Float) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val steps = (((valueRange.endInclusive - valueRange.start) / step).toInt() - 1).coerceAtLeast(0)
    val spoken = valueText(value)
    Column(modifier.fillMaxWidth()) {
        Text(
            "$label: $spoken",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier
                // One merged element (name, spoken value, adjust action) that is at least 48dp tall.
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    stateDescription = spoken
                }
                .heightIn(min = 48.dp),
        )
    }
}
