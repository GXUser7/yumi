package com.mydrop.vpn.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mydrop.vpn.core.model.ThemeMode

val LocalSemanticColors: ProvidableCompositionLocal<MyDropSemanticColors> =
    staticCompositionLocalOf { MyDropDarkColors.toSemanticColors(dark = true) }

@Composable
fun MyDropTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = true,
    /** Pure-black surfaces for OLED panels; only meaningful when the resolved theme is dark. */
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val context = LocalContext.current
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val baseScheme: ColorScheme = when {
        dynamicColor && supportsDynamic && dark -> dynamicDarkColorScheme(context)
        dynamicColor && supportsDynamic -> dynamicLightColorScheme(context)
        dark -> MyDropDarkColors
        else -> MyDropLightColors
    }

    val scheme = if (dark && amoled) baseScheme.toAmoled() else baseScheme

    CompositionLocalProvider(
        LocalSemanticColors provides scheme.toSemanticColors(dark),
    ) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            // The expressive scheme is what gives buttons their springy overshoot and makes the
            // shape morphs feel physical instead of merely interpolated.
            motionScheme = MotionScheme.expressive(),
            typography = MyDropTypography,
            content = content,
        )
    }
}

/**
 * Collapses the elevation ramp onto true black. Container tones are kept slightly apart so
 * cards and sheets remain distinguishable from the background instead of merging into a void.
 */
private fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0C),
    surfaceContainer = Color(0xFF121215),
    surfaceContainerHigh = Color(0xFF1B1B1F),
    surfaceContainerHighest = Color(0xFF242429),
)
