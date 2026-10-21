package io.github.wzehirx.bpdf.feature.sign

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.pdf.SignatureImage
import io.github.wzehirx.bpdf.core.pdf.SignaturePoint

/**
 * A box to draw a signature in with a finger. The whole box is one element for a screen reader, described as
 * "Drawn signature, empty" or "not empty". Finished strokes are handed to [onStroke] with points relative to the box
 * (the same shape as the picture [SignatureImage] makes); the screen keeps them.
 */
@Composable
fun SignaturePad(
    strokes: List<List<SignaturePoint>>,
    enabled: Boolean,
    onStroke: (List<SignaturePoint>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val current = remember { mutableStateListOf<Offset>() }
    val description = resources.getString(if (SignatureImage.hasInk(strokes)) R.string.sign_pad_not_empty else R.string.sign_pad_empty)
    val ink = MaterialTheme.colorScheme.onSurface
    val border = if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(SignatureImage.ASPECT)
            .border(2.dp, border)
            .clipToBounds()
            .onSizeChanged { size = it }
            .semantics { contentDescription = description }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                fun finish() {
                    val w = size.width.coerceAtLeast(1).toFloat()
                    val h = size.height.coerceAtLeast(1).toFloat()
                    if (current.isNotEmpty()) {
                        onStroke(current.map { SignaturePoint((it.x / w).coerceIn(0f, 1f), (it.y / h).coerceIn(0f, 1f)) })
                    }
                    current.clear()
                }
                detectDragGestures(
                    onDragStart = { current.clear(); current += it },
                    onDrag = { change, _ -> current += change.position },
                    onDragEnd = ::finish,
                    onDragCancel = ::finish,
                )
            },
    ) {
        val width = 3.dp.toPx()
        strokes.forEach { stroke -> drawStroke(stroke.map { Offset(it.x * this.size.width, it.y * this.size.height) }, ink, width) }
        drawStroke(current.toList(), ink, width)
    }
}



private fun DrawScope.drawStroke(points: List<Offset>, color: androidx.compose.ui.graphics.Color, width: Float) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color, radius = width, center = points.first())
        return
    }
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
