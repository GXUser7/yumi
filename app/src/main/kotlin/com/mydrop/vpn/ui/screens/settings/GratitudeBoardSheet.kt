package com.mydrop.vpn.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.graphics.shapes.toPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.shared.R

internal const val GITHUB_ISSUES_URL = "https://github.com/GXUser7/yumi/issues"

internal enum class GratitudeAvatarShape {
    Circle,
    Square,
    Clover,
    Cookie,
    Pentagon,
    Oval,
}

internal enum class GratitudeTone {
    Primary,
    Secondary,
    Tertiary,
    Neutral,
}

internal data class GratitudeEntry(
    val handle: String,
    val initial: String,
    val issueNumber: Int? = null,
    @param:StringRes val sourceTagRes: Int? = null,
    @param:StringRes val descriptionRes: Int,
    val avatarShape: GratitudeAvatarShape,
    val tone: GratitudeTone,
    val isHandle: Boolean = true,
) {
    val displayName: String get() = if (isHandle) "@$handle" else handle
    val issueTag: String? get() = issueNumber?.let { "#$it" }
    val issueUrl: String? get() = issueNumber?.let { "$GITHUB_ISSUES_URL/$it" }
}

internal val GRATITUDE_ENTRIES = listOf(
    GratitudeEntry(
        handle = "Sha Li",
        initial = "S",
        issueNumber = null,
        sourceTagRes = R.string.gratitude_badge_telegram,
        descriptionRes = R.string.gratitude_sha_li,
        avatarShape = GratitudeAvatarShape.Pentagon,
        tone = GratitudeTone.Primary,
        isHandle = false,
    ),
    GratitudeEntry(
        handle = "EternalBlueInfinity",
        initial = "E",
        issueNumber = 12,
        descriptionRes = R.string.gratitude_eternal_blue,
        avatarShape = GratitudeAvatarShape.Circle,
        tone = GratitudeTone.Secondary,
    ),
    GratitudeEntry(
        handle = "Lesak777",
        initial = "L",
        issueNumber = 11,
        descriptionRes = R.string.gratitude_lesak,
        avatarShape = GratitudeAvatarShape.Square,
        tone = GratitudeTone.Tertiary,
    ),
    GratitudeEntry(
        handle = "Obsida",
        initial = "O",
        issueNumber = 9,
        descriptionRes = R.string.gratitude_obsida,
        avatarShape = GratitudeAvatarShape.Clover,
        tone = GratitudeTone.Neutral,
    ),
    GratitudeEntry(
        handle = "thetimick",
        initial = "T",
        issueNumber = 2,
        descriptionRes = R.string.gratitude_thetimick,
        avatarShape = GratitudeAvatarShape.Cookie,
        tone = GratitudeTone.Primary,
    ),
    GratitudeEntry(
        handle = "aror",
        initial = "A",
        issueNumber = null,
        sourceTagRes = R.string.gratitude_badge_tester,
        descriptionRes = R.string.gratitude_aror,
        avatarShape = GratitudeAvatarShape.Oval,
        tone = GratitudeTone.Secondary,
        isHandle = false,
    ),
)

internal fun segmentedStackShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 24.dp
    val inner = 6.dp
    if (count <= 1) return RoundedCornerShape(outer)
    return when (index) {
        0 -> RoundedCornerShape(
            topStart = outer,
            topEnd = outer,
            bottomStart = inner,
            bottomEnd = inner,
        )

        count - 1 -> RoundedCornerShape(
            topStart = inner,
            topEnd = inner,
            bottomStart = outer,
            bottomEnd = outer,
        )

        else -> RoundedCornerShape(inner)
    }
}

/** Tonal row placed at the bottom of settings to open the contributors sheet. */
@Composable
internal fun GratitudeBoardRow(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_gratitude_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.settings_gratitude_subtitle,
                        count,
                        count,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Modal bottom sheet displaying GitHub Issues contributors in a segmented stack. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GratitudeBoardSheet(
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_gratitude_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_gratitude_sheet_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalIconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_close),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                GRATITUDE_ENTRIES.forEachIndexed { index, entry ->
                    GratitudeContributorCard(
                        entry = entry,
                        shape = segmentedStackShape(index, GRATITUDE_ENTRIES.size),
                        onOpenIssue = entry.issueUrl?.let { url -> { openUrl(context, url) } },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.settings_gratitude_footer_prompt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { openUrl(context, GITHUB_ISSUES_URL) }) {
                    Text(stringResource(R.string.settings_gratitude_footer_action))
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GratitudeContributorCard(
    entry: GratitudeEntry,
    shape: RoundedCornerShape,
    onOpenIssue: (() -> Unit)?,
) {
    val (badgeContainer, badgeContent) = toneColors(entry.tone)
    val cardColor = MaterialTheme.colorScheme.surfaceContainerHigh
    if (onOpenIssue != null) {
        Surface(
            onClick = onOpenIssue,
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = cardColor,
        ) {
            GratitudeContributorBody(
                entry = entry,
                badgeContainer = badgeContainer,
                badgeContent = badgeContent,
            )
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = cardColor,
        ) {
            GratitudeContributorBody(
                entry = entry,
                badgeContainer = badgeContainer,
                badgeContent = badgeContent,
            )
        }
    }
}

@Composable
private fun GratitudeContributorBody(
    entry: GratitudeEntry,
    badgeContainer: Color,
    badgeContent: Color,
) {
    val badgeText = entry.issueTag ?: entry.sourceTagRes?.let { stringResource(it) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ContributorAvatar(
            initial = entry.initial,
            shape = entry.avatarShape,
            containerColor = badgeContainer,
            contentColor = badgeContent,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!badgeText.isNullOrEmpty()) {
                    Surface(
                        shape = CircleShape,
                        color = badgeContainer,
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = badgeContent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Text(
                text = stringResource(entry.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ContributorAvatar(
    initial: String,
    shape: GratitudeAvatarShape,
    containerColor: Color,
    contentColor: Color,
) {
    val path = remember(shape) {
        val polygon = when (shape) {
            GratitudeAvatarShape.Circle -> MaterialShapes.Circle
            GratitudeAvatarShape.Square -> MaterialShapes.Square
            GratitudeAvatarShape.Clover -> MaterialShapes.Clover4Leaf
            GratitudeAvatarShape.Cookie -> MaterialShapes.Cookie4Sided
            GratitudeAvatarShape.Pentagon -> MaterialShapes.Pentagon
            GratitudeAvatarShape.Oval -> MaterialShapes.Oval
        }
        polygon.toPath().asComposePath()
    }
    Box(
        modifier = Modifier.size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // MaterialShapes are normalised into a 0..1 box.
            scale(size.width, size.height, pivot = Offset.Zero) {
                drawPath(path = path, color = containerColor)
            }
        }
        Text(
            text = initial,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = contentColor,
        )
    }
}

@Composable
private fun toneColors(tone: GratitudeTone): Pair<Color, Color> = when (tone) {
    GratitudeTone.Primary ->
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer

    GratitudeTone.Secondary ->
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer

    GratitudeTone.Tertiary ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer

    GratitudeTone.Neutral ->
        MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurface
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
