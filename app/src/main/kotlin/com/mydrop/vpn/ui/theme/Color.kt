package com.mydrop.vpn.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * One accent, spent in one place.
 *
 * Glacier blue on a near-black ground: it reads as "protected" without the green-means-on
 * cliché, and it survives a phone screen in sunlight. Connection state is carried by the shape
 * of the flow figure — empty outline, warming, dense — so colour only ever confirms what the
 * form already said. That keeps the tunnel screen legible to a colour-blind user.
 *
 * Neutrals are biased cold rather than left pure grey, so they agree with the accent instead of
 * merely sitting next to it. Sand is the second data colour (upload against download) and coral
 * is reserved for failure; neither is allowed to compete with the ice.
 */

private val Ice = Color(0xFFA8C7FF)
private val IceInk = Color(0xFF07203F)
private val IceContainer = Color(0xFF24314A)
private val IceOnContainer = Color(0xFFD5E3FF)
private val IceDark = Color(0xFF2B5CAB)
private val IceLightContainer = Color(0xFFD8E4FA)

private val Sand = Color(0xFFF0BE73)
private val SandInk = Color(0xFF402B08)
private val SandContainer = Color(0xFF5A4118)
private val SandOnContainer = Color(0xFFFFDEB0)
private val SandDark = Color(0xFF7A5514)
private val SandLightContainer = Color(0xFFFBE0B4)

private val Mint = Color(0xFF8FD6C0)
private val MintInk = Color(0xFF00382A)
private val MintContainer = Color(0xFF06503C)
private val MintOnContainer = Color(0xFFA9F2DC)
private val MintDark = Color(0xFF1F6B54)
private val MintLightContainer = Color(0xFFB9EFDC)

private val Coral = Color(0xFFFF9885)
private val CoralInk = Color(0xFF5C1508)
private val CoralContainer = Color(0xFF7E2415)
private val CoralOnContainer = Color(0xFFFFDAD3)

val MyDropDarkColors = darkColorScheme(
    primary = Ice,
    onPrimary = IceInk,
    primaryContainer = IceContainer,
    onPrimaryContainer = IceOnContainer,
    inversePrimary = IceDark,

    secondary = Sand,
    onSecondary = SandInk,
    secondaryContainer = SandContainer,
    onSecondaryContainer = SandOnContainer,

    tertiary = Mint,
    onTertiary = MintInk,
    tertiaryContainer = MintContainer,
    onTertiaryContainer = MintOnContainer,

    error = Coral,
    onError = CoralInk,
    errorContainer = CoralContainer,
    onErrorContainer = CoralOnContainer,

    background = Color(0xFF090B0F),
    onBackground = Color(0xFFE7ECF4),
    surface = Color(0xFF090B0F),
    onSurface = Color(0xFFE7ECF4),
    surfaceVariant = Color(0xFF29313D),
    onSurfaceVariant = Color(0xFF9AA5B6),
    surfaceTint = Ice,

    surfaceBright = Color(0xFF2C333F),
    surfaceDim = Color(0xFF090B0F),
    surfaceContainerLowest = Color(0xFF05070A),
    surfaceContainerLow = Color(0xFF0F131A),
    surfaceContainer = Color(0xFF151A22),
    surfaceContainerHigh = Color(0xFF1C222C),
    surfaceContainerHighest = Color(0xFF232A35),

    inverseSurface = Color(0xFFE7ECF4),
    inverseOnSurface = Color(0xFF1A1F27),

    outline = Color(0xFF4E5867),
    outlineVariant = Color(0xFF29313D),
    scrim = Color.Black,
)

/*
 * The light scheme is a translation, not an inversion: glacier blue at full lightness has no
 * contrast on paper, so the accent drops to its darker sibling while the containers keep the
 * original hue. Everything that was a tone above the ground becomes a tone below it.
 */
val MyDropLightColors = lightColorScheme(
    primary = IceDark,
    onPrimary = Color.White,
    primaryContainer = IceLightContainer,
    onPrimaryContainer = Color(0xFF08203F),
    inversePrimary = Ice,

    secondary = SandDark,
    onSecondary = Color.White,
    secondaryContainer = SandLightContainer,
    onSecondaryContainer = Color(0xFF2A1C03),

    tertiary = MintDark,
    onTertiary = Color.White,
    tertiaryContainer = MintLightContainer,
    onTertiaryContainer = Color(0xFF002018),

    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD3),
    onErrorContainer = Color(0xFF410800),

    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF0E1319),
    surface = Color(0xFFF4F6FA),
    onSurface = Color(0xFF0E1319),
    surfaceVariant = Color(0xFFDFE4EC),
    onSurfaceVariant = Color(0xFF4A5568),
    surfaceTint = IceDark,

    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD8DDE6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEEF1F7),
    surfaceContainer = Color(0xFFE7EBF2),
    surfaceContainerHigh = Color(0xFFE1E6EE),
    surfaceContainerHighest = Color(0xFFDAE0E9),

    inverseSurface = Color(0xFF1A1F27),
    inverseOnSurface = Color(0xFFF0F3F8),

    outline = Color(0xFF77839A),
    outlineVariant = Color(0xFFC5CCD8),
    scrim = Color.Black,
)

/**
 * Colours the M3 roles cannot express: tunnel health and latency quality.
 *
 * [connected] deliberately is not green. The flow figure already says "running" by being full
 * of moving traffic, so the accent stays ice and the palette keeps a single loud colour. The
 * latency ramp is the one place a green/amber/red scale earns its keep — there the colour *is*
 * the measurement, and a list of two dozen servers has to be scannable at a glance.
 */
data class MyDropSemanticColors(
    val connected: Color,
    val onConnected: Color,
    val connectedContainer: Color,
    val onConnectedContainer: Color,
    val connecting: Color,
    val latencyFast: Color,
    val latencyMedium: Color,
    val latencySlow: Color,
    val latencyDead: Color,
    val download: Color,
    val upload: Color,
)

/**
 * Derives the semantic colours from whichever scheme is active.
 *
 * This is what makes the dynamic-colour switch mean something. The tunnel state, the traffic
 * waves and the control's outline all read from here, so hardcoding them left the screen stubbornly
 * ice-blue in exactly those places while everything else followed the wallpaper — the accent has to
 * come from the scheme, not from a constant beside it. With the built-in palette `primary` *is* the
 * ice blue, so nothing changes there.
 *
 * The latency ramp is the deliberate exception. Green/amber/red is a measurement scale rather than
 * decoration: the colour *is* the reading, and rewriting it from the user's wallpaper would make a
 * fast server indistinguishable from a dead one.
 */
fun ColorScheme.toSemanticColors(dark: Boolean): MyDropSemanticColors = MyDropSemanticColors(
    connected = primary,
    onConnected = onPrimary,
    connectedContainer = primaryContainer,
    onConnectedContainer = onPrimaryContainer,
    connecting = secondary,
    latencyFast = if (dark) Color(0xFF7EE0A8) else Color(0xFF127A4A),
    latencyMedium = if (dark) Sand else SandDark,
    latencySlow = if (dark) Coral else Color(0xFFB3261E),
    latencyDead = if (dark) Color(0xFF7A8496) else Color(0xFF77839A),
    // Two channels have to stay apart at a glance; primary and tertiary are the furthest apart
    // any Material scheme guarantees.
    download = primary,
    upload = tertiary,
)
