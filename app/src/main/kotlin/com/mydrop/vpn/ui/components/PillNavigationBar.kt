package com.mydrop.vpn.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Floating pill navigation bar.
 *
 * The insets are consumed by the outer [Box] and explicitly zeroed on [ShortNavigationBar] —
 * otherwise the bar would pad for the gesture area a second time and the pill would sit far
 * higher than intended.
 */
@Composable
fun PillNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp, top = 4.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(percent = 50),
            // The pill floats over the connect screen's sheet, so it needs a container tone
            // that is clearly distinct from it rather than the near-background surfaceContainer.
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 12.dp,
        ) {
            // This Box is load-bearing. Surface lays its content out with
            // propagateMinConstraints = true, so the bar would receive an exact width — and
            // ShortNavigationBar's EqualWeight policy runs each item's width through
            // constraints.constrain(), which clamps it back up to that minimum. Every item then
            // measures full-width and all but the first get placed off-screen. A plain Box
            // defaults to propagateMinConstraints = false and restores minWidth = 0.
            Box(Modifier.fillMaxWidth()) {
                ShortNavigationBar(
                    containerColor = Color.Transparent,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    content = content,
                )
            }
        }
    }
}
