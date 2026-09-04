package com.mydrop.vpn.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mydrop.vpn.ui.theme.MyDropTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvDpadNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun initialFocusAndColumnTransitionsFollowTvContract() {
        render()

        compose.onNodeWithTag("connection-button").assertIsFocused()
            .performKeyInput { keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft) }
        compose.onNodeWithTag("server-selector").assertIsFocused()
        compose.onNodeWithTag("server-selector")
            .performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
        compose.onNodeWithTag("connection-button").assertIsFocused()
            .performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        compose.onNodeWithTag("navigation-connect").assertIsFocused()
    }

    @Test
    fun everyHomeActionIsDpadReachableAndNavigationDispatches() {
        var selected = TvDestination.Connect
        render { selected = it }

        listOf(
            "connection-button",
            "server-selector",
            "navigation-connect",
            "navigation-servers",
            "navigation-subscriptions",
            "navigation-settings",
        ).forEach { tag ->
            compose.onNodeWithTag(tag).assertExists().assertHasClickAction()
        }

        compose.onNodeWithTag("navigation-settings").performClick()
        compose.runOnIdle { assertEquals(TvDestination.Settings, selected) }
    }

    /**
     * Mirrors how [YumiTvApp] puts the screen and the bar together.
     *
     * The bar is no longer part of the tunnel screen — it belongs to the app so that it can travel
     * to the right edge on the other tabs — so the focus contract this test is about only exists
     * once the two are composed as siblings, which is what this does.
     */
    private fun render(onDestination: (TvDestination) -> Unit = {}) {
        compose.setContent {
            MyDropTheme(dynamicColor = false) {
                val navFocus = remember { FocusRequester() }
                Box(Modifier.fillMaxSize()) {
                    TvConnectScreen(
                        state = TvUiState(),
                        navFocus = navFocus,
                        onToggle = {},
                        onOpenServers = {},
                    )
                    TvNavigationBar(
                        selected = TvDestination.Connect,
                        onSelected = onDestination,
                        firstFocusRequester = navFocus,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .fillMaxWidth(0.5f)
                            .height(152.dp)
                            .padding(24.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
    }
}
