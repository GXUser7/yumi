package com.mydrop.vpn.ui.screens.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RecommendedBadgeContractTest {

    private fun settingsScreenSource(): String {
        var dir: File? = File(".").canonicalFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) {
                return File(dir, "app/src/main/kotlin/com/mydrop/vpn/ui/screens/settings/SettingsScreen.kt").readText()
            }
            dir = dir.parentFile
        }
        fail("Unable to locate repo root from ${File(".").canonicalPath}")
        error("unreachable")
    }

    @Test
    fun `RecommendedBadge has no border and no AutoAwesome icon`() {
        val source = settingsScreenSource()
        val fnStart = source.indexOf("internal fun RecommendedBadge(")
        assertTrue("RecommendedBadge function not found", fnStart >= 0)
        val nextFn = source.indexOf("internal fun NavigationRow(", fnStart)
        assertTrue("Expected NavigationRow after RecommendedBadge", nextFn > fnStart)

        val body = source.substring(fnStart, nextFn)
        assertFalse("RecommendedBadge must not have a BorderStroke border", body.contains("BorderStroke"))
        assertFalse("RecommendedBadge must not render AutoAwesome icon/emoji", body.contains("AutoAwesome"))
        assertFalse("RecommendedBadge must not contain border =", body.contains("border ="))
        assertTrue("RecommendedBadge must render settings_recommended_badge", body.contains("settings_recommended_badge"))
    }

    @Test
    fun `SwitchRow gates RecommendedBadge behind LocalShowRecommendedBadges`() {
        val source = settingsScreenSource()
        assertTrue(
            "SettingsScreen must check LocalShowRecommendedBadges.current in SwitchRow",
            source.contains("LocalShowRecommendedBadges.current"),
        )
    }
}
