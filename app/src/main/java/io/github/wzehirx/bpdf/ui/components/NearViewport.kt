package io.github.wzehirx.bpdf.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp

/**
 * An empty area of at least [minHeight] that composes [content] once it comes within one screen
 * (above) or two screens (below) of the visible area, and keeps it afterwards. Lets a long
 * non-lazy column keep every heading composed (so screen-reader heading navigation reaches all of
 * them) while the heavy part under each heading is only built, and its data only loaded, near the viewport.
 */
@Composable
fun ComposeWhenNear(minHeight: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var near by remember { mutableStateOf(false) }
    Box(
        modifier
            .heightIn(min = minHeight)
            .onGloballyPositioned { coordinates ->
                if (!near && coordinates.isAttached) {
                    // Not boundsInRoot(): that clips to the visible area and so hides how far away the box is.
                    val top = coordinates.positionInRoot().y
                    val bottom = top + coordinates.size.height
                    val viewport = coordinates.findRootCoordinates().size.height
                    if (bottom > -viewport && top < 2 * viewport) near = true
                }
            },
    ) {
        if (near) content()
    }
}
