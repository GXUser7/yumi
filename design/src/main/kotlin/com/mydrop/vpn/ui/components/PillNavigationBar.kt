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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.ui.theme.FrostState
import com.mydrop.vpn.ui.theme.Glass
import com.mydrop.vpn.ui.theme.GlassTone
import com.mydrop.vpn.ui.theme.LocalFrostState
import com.mydrop.vpn.ui.theme.frostedGlass

private val PillShape = RoundedCornerShape(percent = 50)

/**
 * Floating pill navigation bar, in frosted glass.
 *
 * The one pane in the app with real blur behind it: lists scroll under the pill, and a translucent
 * surface over moving text without a blur reads as a smudge rather than as glass. It blurs whatever
 * [frost] was taken from — the whole screen, backdrop included — and falls back to a nearly opaque
 * pane where the platform has no blur to give.
 *
 * No shadow any more. A shadow under a translucent surface shows through it as a dark bruise in the
 * middle of the glass; the rim and the blur are what lift the pill off the content now.
 *
 * The insets are consumed by the outer [Box] and explicitly zeroed on [ShortNavigationBar] —
 * otherwise the bar would pad for the gesture area a second time and the pill would sit far
 * higher than intended.
 */
@Composable
fun PillNavigationBar(
    modifier: Modifier = Modifier,
    frost: FrostState? = LocalFrostState.current,
    content: @Composable () -> Unit,
) {
    val style = Glass.style(tone = GlassTone.Regular)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp, top = 4.dp),
    ) {
        // This Box is load-bearing. ShortNavigationBar's EqualWeight policy runs each item's width
        // through constraints.constrain(), which clamps it back up to the minimum width — so a bar
        // handed an exact width measures every item full-width and places all but the first
        // off-screen. The pane used to be a Surface, which lays its content out with
        // propagateMinConstraints = true and caused exactly that; a plain Box does not propagate
        // them, and keeping the pane on a Box of its own keeps it that way.
        Box(
            Modifier
                .fillMaxWidth()
                .frostedGlass(
                    state = frost,
                    style = style,
                    shape = PillShape,
                    backdrop = MaterialTheme.colorScheme.background,
                ),
        ) {
            ShortNavigationBar(
                containerColor = Color.Transparent,
                windowInsets = WindowInsets(0, 0, 0, 0),
                content = content,
            )
        }
    }
}
