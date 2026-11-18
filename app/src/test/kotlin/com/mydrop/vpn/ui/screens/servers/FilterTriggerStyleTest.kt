package com.mydrop.vpn.ui.screens.servers

import androidx.compose.ui.graphics.Color
import com.mydrop.vpn.core.model.Palette
import com.mydrop.vpn.ui.theme.MyDropDarkColors
import com.mydrop.vpn.ui.theme.MyDropLightColors
import com.mydrop.vpn.ui.theme.scheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FilterTriggerStyleTest {

    @Test
    fun `inactive state keeps surfaceContainerHigh, onSurface, and no border across all palettes`() {
        val schemes = listOf(MyDropDarkColors, MyDropLightColors) +
            Palette.entries.flatMap { listOf(it.scheme(dark = true), it.scheme(dark = false)) }

        schemes.forEach { scheme ->
            val inactive = filterTriggerStyle(active = false, colorScheme = scheme)
            assertEquals(scheme.surfaceContainerHigh, inactive.containerColor)
            assertEquals(scheme.onSurface, inactive.contentColor)
            assertEquals(Color.Transparent, inactive.badgeContainerColor)
            assertEquals(Color.Transparent, inactive.badgeContentColor)
            assertFalse(inactive.hasBorder)
        }
    }

    @Test
    fun `active state uses borderless primary fill, onPrimary content, and contrasted onPrimary badge across all palettes`() {
        val schemes = listOf(MyDropDarkColors, MyDropLightColors) +
            Palette.entries.flatMap { listOf(it.scheme(dark = true), it.scheme(dark = false)) }

        schemes.forEach { scheme ->
            val active = filterTriggerStyle(active = true, colorScheme = scheme)
            assertEquals(scheme.primary, active.containerColor)
            assertEquals(scheme.onPrimary, active.contentColor)
            assertEquals(scheme.onPrimary, active.badgeContainerColor)
            assertEquals(scheme.primary, active.badgeContentColor)
            assertFalse(active.hasBorder)
            assertNotEquals(active.containerColor, active.contentColor)
            assertNotEquals(active.badgeContainerColor, active.badgeContentColor)
        }
    }
}
