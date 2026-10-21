package io.github.wzehirx.bpdf.core.a11y

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import io.github.wzehirx.bpdf.R

/**
 * Adds TalkBack custom actions (move up/down/top/bottom, remove) so a list can be reordered
 * without drag gestures. Only actions valid for [index] in a list of [count] are offered.
 */
@Composable
fun Modifier.reorderable(
    index: Int,
    count: Int,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (() -> Unit)? = null,
): Modifier {
    val up = stringResource(R.string.action_move_up)
    val down = stringResource(R.string.action_move_down)
    val top = stringResource(R.string.action_move_top)
    val bottom = stringResource(R.string.action_move_bottom)
    val remove = stringResource(R.string.action_remove)
    return semantics {
        val actions = mutableListOf<CustomAccessibilityAction>()
        fun add(label: String, to: Int) {
            actions += CustomAccessibilityAction(label) { onMove(index, to); true }
        }
        if (index > 0) add(up, index - 1)
        if (index < count - 1) add(down, index + 1)
        if (index > 0) add(top, 0)
        if (index < count - 1) add(bottom, count - 1)
        if (onRemove != null) actions += CustomAccessibilityAction(remove) { onRemove(); true }
        customActions = actions
    }
}
