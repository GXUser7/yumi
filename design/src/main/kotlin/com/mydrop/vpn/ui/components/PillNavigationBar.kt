package com.mydrop.vpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.ui.theme.FrostState
import com.mydrop.vpn.ui.theme.Glass
import com.mydrop.vpn.ui.theme.LocalFrostState
import com.mydrop.vpn.ui.theme.LocalGlassColors
import com.mydrop.vpn.ui.theme.frostedGlass

/**
 * The floating toolbar every tab stands over, as YouCloud's home screen has it: the tabs on a pill
 * of the panel tone, each by its mark, the chosen one an accent capsule; and the screen's own
 * actions beside it as floating squares of the same glass.
 *
 * Marks without words, because the words did not fit. Four labelled tabs filled the width of a
 * phone on their own, and the actions had nowhere to stand but on top of the list or above the
 * headline. The headline of every tab already names it, so the mark only has to be told apart
 * from three others.
 *
 * Everything scales down together when it does not fit — the tunnel tab can carry two actions,
 * the speed test and the remote, and on a 360 dp phone the full-size bar is twenty dp too wide.
 * Shrinking every piece by the same factor keeps the proportions YouCloud's bar has, where
 * shrinking one piece or wrapping the actions to a second row would not.
 *
 * The one pane in the app with real blur behind it: lists scroll under the pill, and a translucent
 * surface over moving text without a blur reads as a smudge rather than as glass.
 */
@Composable
fun PillNavigationBar(
    itemCount: Int,
    modifier: Modifier = Modifier,
    frost: FrostState? = LocalFrostState.current,
    /** How many [actions] there are, so the bar can be sized before it is laid out. */
    actionCount: Int = 0,
    /** The screen's own actions, as [PillActionButton]s. */
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = MaterialTheme.colorScheme.background
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp),
    ) {
        val needed = pillWidth(itemCount) + (ActionGap + BarHeight) * actionCount
        val scale = (maxWidth / needed).coerceAtMost(1f)
        CompositionLocalProvider(LocalPillScale provides scale) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalContentColor provides LocalGlassColors.current.onPanel) {
                    Row(
                        modifier = Modifier
                            .height(BarHeight * scale)
                            .frostedGlass(frost, Glass.panel(), CircleShape, backdrop)
                            .padding(BarPadding * scale),
                        horizontalArrangement = Arrangement.spacedBy(ItemGap * scale),
                        verticalAlignment = Alignment.CenterVertically,
                        content = content,
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ActionGap * scale),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

/**
 * One tab. The chosen one is the accent, wider than the others so the capsule reads as a place
 * rather than as a highlighted icon — the width eases in on the scheme's spring, as YouCloud's does.
 */
@Composable
fun PillNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val scale = LocalPillScale.current
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (selected) scheme.primary else Color.Transparent,
        animationSpec = tween(250),
        label = "pill-item-container",
    )
    val content by animateColorAsState(
        targetValue = if (selected) scheme.onPrimary else LocalContentColor.current,
        animationSpec = tween(250),
        label = "pill-item-content",
    )
    val width by animateDpAsState(
        targetValue = (if (selected) SelectedItemWidth else ItemSize) * scale,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "pill-item-width",
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(width = width, height = ItemSize * scale)
            .semantics {
                this.selected = selected
                role = Role.Tab
            },
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(IconSize * scale))
        }
    }
}

/**
 * An action beside the pill: a square of the same glass, its mark in the accent — the speed test,
 * the remote, measuring every server, adding a subscription.
 *
 * [content] is the mark — usually an [Icon] — and may change while the action runs; the button
 * keeps its place and only what is on it moves.
 */
@Composable
fun PillActionButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    frost: FrostState? = LocalFrostState.current,
    content: @Composable () -> Unit,
) {
    val scale = LocalPillScale.current
    val shape = RoundedCornerShape(ActionCorner * scale)
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(BarHeight * scale)
            .frostedGlass(frost, Glass.panel(), shape, MaterialTheme.colorScheme.background)
            .semantics { this.contentDescription = contentDescription },
        shape = shape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** The size an icon on the bar is drawn at, scaled with the bar. */
@Composable
fun pillIconSize(base: Dp = IconSize): Dp = base * LocalPillScale.current

private fun pillWidth(items: Int): Dp =
    BarPadding * 2 + ItemSize * (items - 1) + SelectedItemWidth + ItemGap * (items - 1).coerceAtLeast(0)

/** How much the bar was shrunk to fit; 1 when it fits as drawn. */
private val LocalPillScale = staticCompositionLocalOf { 1f }

private val BarHeight = 64.dp
private val BarPadding = 8.dp
private val ItemSize = 48.dp
private val SelectedItemWidth = 64.dp
private val ItemGap = 4.dp
private val ActionGap = 8.dp
private val ActionCorner = 20.dp
private val IconSize = 24.dp
