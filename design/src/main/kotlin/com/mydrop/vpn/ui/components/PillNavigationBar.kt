package com.mydrop.vpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.ui.theme.FrostState
import com.mydrop.vpn.ui.theme.Glass
import com.mydrop.vpn.ui.theme.LocalFrostState
import com.mydrop.vpn.ui.theme.LocalGlassColors
import com.mydrop.vpn.ui.theme.frostedGlass

/**
 * The floating toolbar every tab stands over, as YouCloud's home screen has it: the tabs on a pill
 * of the panel tone, each by its mark, the chosen one an accent capsule; and the screen's own action
 * beside it as a floating square of the same glass.
 *
 * Marks without words, because the words did not fit. Four labelled tabs filled the width of a
 * phone on their own, and the action — measure every server, add a subscription — had nowhere to
 * stand but on top of the list as a separate button, over the rows it was meant to act on. The
 * headline of every tab already names it, so the mark only has to be told apart from three others.
 *
 * The one pane in the app with real blur behind it: lists scroll under the pill, and a translucent
 * surface over moving text without a blur reads as a smudge rather than as glass.
 */
@Composable
fun PillNavigationBar(
    modifier: Modifier = Modifier,
    frost: FrostState? = LocalFrostState.current,
    /** The screen's own action, or nothing; see [PillActionButton]. */
    action: (@Composable () -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = MaterialTheme.colorScheme.background
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides LocalGlassColors.current.onPanel) {
            Row(
                modifier = Modifier
                    .height(BarHeight)
                    .frostedGlass(frost, Glass.panel(), CircleShape, backdrop)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
        Spacer(Modifier.weight(1f))
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            action()
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
        targetValue = if (selected) SelectedItemWidth else ItemSize,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "pill-item-width",
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(width = width, height = ItemSize)
            .semantics {
                this.selected = selected
                role = Role.Tab
            },
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * The action that sits beside the pill: a square of the same glass, its mark in the accent.
 *
 * [content] is the mark — usually an [Icon] — and may change while the action runs; the button
 * keeps its place and only what is on it moves.
 */
@Composable
fun PillActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    frost: FrostState? = LocalFrostState.current,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(BarHeight)
            .frostedGlass(frost, Glass.panel(), ActionShape, MaterialTheme.colorScheme.background),
        shape = ActionShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

private val BarHeight = 64.dp
private val ItemSize = 48.dp
private val SelectedItemWidth = 64.dp
private val ActionShape = RoundedCornerShape(20.dp)
