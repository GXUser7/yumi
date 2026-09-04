package com.mydrop.vpn.tv

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.ui.theme.MyDropTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rendering smoke test; CI device profiles provide the 1920x1080 and 1280x720 captures. */
@RunWith(AndroidJUnit4::class)
class TvConnectionStateRenderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun allConnectionStatesProduceANonEmptyFrame() {
        val state = mutableStateOf<VpnState>(VpnState.Disconnected)
        compose.setContent {
            MyDropTheme(dynamicColor = false) {
                TvConnectScreen(
                    state = TvUiState(vpnState = state.value),
                    navFocus = remember { FocusRequester() },
                    onToggle = {},
                    onOpenServers = {},
                )
            }
        }

        listOf(
            VpnState.Disconnected,
            VpnState.Connecting("test"),
            VpnState.Connected("test", System.currentTimeMillis()),
            VpnState.Failed("test", "Test failure"),
        ).forEach { vpnState ->
            compose.runOnIdle { state.value = vpnState }
            compose.waitForIdle()
            val image = compose.onRoot().captureToImage()
            assertTrue(image.width > 0 && image.height > 0)
            assertTrue(image.toPixelMap()[image.width / 2, image.height / 2] != Color.Transparent)
        }
    }
}
