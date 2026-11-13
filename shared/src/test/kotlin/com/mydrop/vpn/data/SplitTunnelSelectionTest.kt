package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.SplitTunnelMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SplitTunnelSelectionTest {
    @get:Rule val folder = TemporaryFolder()

    private fun withSettings(test: (SettingsRepository) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            test(SettingsRepository(folder.newFolder(), scope))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an uninstall clears the selection and a subsequent install does not restore it`() {
        for (mode in SplitTunnelMode.entries) withSettings { settings ->
            settings.update {
                it.copy(splitTunnelMode = mode, splitTunnelPackages = setOf("removed", "kept"))
            }
            settings.onPackageRemoved("removed", replacing = false)
            settings.pruneSplitTunnelPackages { true } // Both packages installed again.
            assertEquals(setOf("kept"), settings.value.splitTunnelPackages)
            assertEquals(mode, settings.value.splitTunnelMode)
        }
    }

    @Test
    fun `updating an application keeps its selection`() = withSettings { settings ->
        settings.update { it.copy(splitTunnelPackages = setOf("app")) }
        settings.onPackageRemoved("app", replacing = true)
        assertEquals(setOf("app"), settings.value.splitTunnelPackages)
    }

    @Test
    fun `startup removes applications uninstalled while the process was dead`() = withSettings { settings ->
        settings.update { it.copy(splitTunnelPackages = setOf("gone", "system", "kept")) }
        settings.pruneSplitTunnelPackages { it in setOf("system", "kept") }
        assertEquals(setOf("system", "kept"), settings.value.splitTunnelPackages)
    }

    @Test
    fun `removing an unrelated package leaves settings intact`() = withSettings { settings ->
        settings.update { it.copy(splitTunnelPackages = setOf("app"), blockAds = true) }
        val before = settings.value
        settings.onPackageRemoved("other", replacing = false)
        assertEquals(before, settings.value)
    }
}
