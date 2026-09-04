package com.mydrop.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.mydrop.vpn.core.parse.QrMatrix
import kotlin.math.floor

/**
 * Draws a QR code from its module grid.
 *
 * Rendering rather than blitting a bitmap keeps the code crisp at any size and lets it take the
 * surrounding surface's colours, which is what stops it looking like a pasted-in screenshot.
 *
 * The modules are rounded, but only slightly, and the three finder squares keep their full shape:
 * a decoder locates the code by those, and softening them is where stylised QR codes stop
 * scanning.
 */
@Composable
fun QrCode(
    text: String,
    modifier: Modifier = Modifier,
    foreground: Color = Color.Black,
    background: Color = Color.White,
) {
    val matrix = remember(text) { QrMatrix.of(text) } ?: return

    Canvas(modifier = modifier.aspectRatio(1f)) {
        // The quiet zone is part of the specification, not padding: without it a decoder cannot
        // tell where the code ends, and a code drawn edge-to-edge on a card often will not read.
        val modules = matrix.size + QUIET_ZONE * 2
        val module = floor(size.minDimension / modules)
        val origin = Offset(
            x = (size.width - module * modules) / 2 + module * QUIET_ZONE,
            y = (size.height - module * modules) / 2 + module * QUIET_ZONE,
        )

        drawRect(color = background)

        val radius = CornerRadius(module * 0.28f)
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (!matrix.isDark(x, y)) continue
                val square = matrix.inFinder(x, y)
                drawModule(
                    topLeft = Offset(origin.x + x * module, origin.y + y * module),
                    // Adjacent modules are drawn a hair oversized so rounding does not open
                    // hairline gaps down the middle of a solid run.
                    side = module + if (square) 0.5f else 0.6f,
                    radius = if (square) CornerRadius.Zero else radius,
                    color = foreground,
                )
            }
        }
    }
}

private fun DrawScope.drawModule(
    topLeft: Offset,
    side: Float,
    radius: CornerRadius,
    color: Color,
) {
    if (radius == CornerRadius.Zero) {
        drawRect(color = color, topLeft = topLeft, size = Size(side, side))
    } else {
        drawRoundRect(color = color, topLeft = topLeft, size = Size(side, side), cornerRadius = radius)
    }
}

/** The three 7x7 position markers, which have to stay square to remain findable. */
private fun QrMatrix.inFinder(x: Int, y: Int): Boolean {
    val far = size - FINDER
    return (x < FINDER && y < FINDER) || (x >= far && y < FINDER) || (x < FINDER && y >= far)
}

private const val FINDER = 7
private const val QUIET_ZONE = 3
