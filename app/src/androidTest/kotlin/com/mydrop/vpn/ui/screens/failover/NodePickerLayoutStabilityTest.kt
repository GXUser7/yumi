package com.mydrop.vpn.ui.screens.failover

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mydrop.vpn.core.model.AppSettings
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.ui.theme.MyDropTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NodePickerLayoutStabilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val nodes = listOf(
        ProxyNode(
            id = "node-1",
            name = "Alpha Server",
            server = "alpha.example.com",
            port = 443,
            settings = ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555555"),
        ),
        ProxyNode(
            id = "node-2",
            name = "Beta Server",
            server = "beta.example.com",
            port = 443,
            settings = ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555556"),
        ),
    )

    @Test
    fun failoverPicker_keepsServerListPositionOnFirstSelectAndReset() {
        assertPickerDoesNotShift(NodePickerKind.Failover, R.string.failover_clear_selection)
    }

    @Test
    fun mobilePicker_keepsServerListPositionOnFirstSelectAndReset() {
        assertPickerDoesNotShift(NodePickerKind.Mobile, R.string.mobile_nodes_clear)
    }

    private fun assertPickerDoesNotShift(kind: NodePickerKind, clearLabelRes: Int) {
        val clearLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(clearLabelRes)
        compose.setContent {
            var settings by remember { mutableStateOf(AppSettings()) }
            MyDropTheme(dynamicColor = false) {
                NodePickerScreen(
                    kind = kind,
                    settings = settings,
                    nodes = nodes,
                    latencies = emptyMap(),
                    onUpdate = { transform -> settings = transform(settings) },
                    onBack = {},
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }

        compose.onNodeWithText(clearLabel).assertIsDisplayed().assertIsNotEnabled()
        val topBefore = compose.onNodeWithText("Alpha Server").getUnclippedBoundsInRoot().top

        compose.onNodeWithText("Alpha Server").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(clearLabel).assertIsDisplayed().assertIsEnabled()
        val topAfterSelect = compose.onNodeWithText("Alpha Server").getUnclippedBoundsInRoot().top
        assertEquals(topBefore.value, topAfterSelect.value, 0.5f)

        compose.onNodeWithText(clearLabel).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(clearLabel).assertIsDisplayed().assertIsNotEnabled()
        val topAfterReset = compose.onNodeWithText("Alpha Server").getUnclippedBoundsInRoot().top
        assertEquals(topBefore.value, topAfterReset.value, 0.5f)
    }
}
