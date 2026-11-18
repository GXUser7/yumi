package com.mydrop.vpn.ui.screens.servers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.then
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mydrop.vpn.core.model.NodeGroup
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.WorldMap
import com.mydrop.vpn.ui.ServersUiState
import com.mydrop.vpn.ui.theme.MyDropTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServersScreenFilterTest {

    @get:Rule
    val compose = createComposeRule()

    private fun node(
        id: String,
        name: String,
        subscriptionId: String? = "sub-1",
        settings: ProxySettings = ProxySettings.Vless(uuid = "11111111-2222-3333-4444-555555555555"),
        group: NodeGroup? = null,
    ) = ProxyNode(
        id = id,
        name = name,
        server = "$id.example.com",
        port = 443,
        settings = settings,
        subscriptionId = subscriptionId,
        group = group,
    )

    private fun sub(id: String = "sub-1", name: String = "Main") = Subscription(
        id = id,
        name = name,
        url = "https://example.com/$id",
    )

    private fun renderServers(
        state: ServersUiState,
        widthDp: Dp = 448.dp,
        fontScale: Float = 1.0f,
        localeTag: String? = null,
    ) {
        val baseOverride = DeviceConfigurationOverride.ForcedSize(DpSize(widthDp, 800.dp)) then
            DeviceConfigurationOverride.FontScale(fontScale)
        val override = if (localeTag != null) {
            baseOverride then DeviceConfigurationOverride.Locales(LocaleList(localeTag))
        } else {
            baseOverride
        }
        compose.setContent {
            var filterState by remember { mutableStateOf(state.filter) }
            DeviceConfigurationOverride(override) {
                MyDropTheme(dynamicColor = false) {
                    Box(Modifier.width(widthDp).height(800.dp)) {
                        ServersScreen(
                            state = state.copy(filter = filterState),
                            onSelect = {},
                            onPing = {},
                            onRemove = {},
                            onSetTlsInsecure = { _, _ -> },
                            onToggleGroup = {},
                            onQueryChange = { filterState = filterState.copy(query = it) },
                            onSortChange = { filterState = filterState.copy(sort = it) },
                            onToggleProtocol = { protocol ->
                                val next = if (protocol in filterState.selectedProtocols) {
                                    filterState.selectedProtocols - protocol
                                } else {
                                    filterState.selectedProtocols + protocol
                                }
                                filterState = filterState.copy(selectedProtocols = next)
                            },
                            onClearProtocols = {
                                filterState = filterState.copy(selectedProtocols = emptySet())
                            },
                            onToggleCountry = { code ->
                                val next = if (code in filterState.selectedCountries) {
                                    filterState.selectedCountries - code
                                } else {
                                    filterState.selectedCountries + code
                                }
                                filterState = filterState.copy(selectedCountries = next)
                            },
                            onClearCountries = {
                                filterState = filterState.copy(selectedCountries = emptySet())
                            },
                            onResetFilters = {
                                filterState = filterState.copy(
                                    sort = ServerSort.Default,
                                    selectedProtocols = emptySet(),
                                    selectedCountries = emptySet(),
                                )
                            },
                            contentPadding = PaddingValues(0.dp),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun filterButtonAppearsNextToSearch_withNoInlineFilterCards() {
        val codes = listOf("US", "DE", "NL", "LV")
        val nodes = codes.mapIndexed { i, code ->
            node(id = "n-$i", name = "${WorldMap.flagOf(code)} Server $code", subscriptionId = "sub-1")
        } + node(id = "manual-pl", name = "${WorldMap.flagOf("PL")} Manual PL", subscriptionId = null)

        renderServers(
            ServersUiState(
                nodes = nodes,
                subscriptions = listOf(sub()),
            ),
        )

        compose.onNodeWithTag("servers-filter-button").assertIsDisplayed()
        compose.onNodeWithTag("servers-filter-button-count").assertDoesNotExist()
        compose.onNodeWithTag("protocol-filter-block").assertDoesNotExist()
        compose.onNodeWithTag("country-filter-block").assertDoesNotExist()
        compose.onNodeWithTag("protocol-chip-VLESS").assertDoesNotExist()
        compose.onNodeWithTag("country-tile-DE").assertDoesNotExist()
    }

    @Test
    fun filterBottomSheet_filtersByProtocolAndCountry_excludesManualCountries_andResets() {
        val nodes = listOf(
            node(
                id = "s1",
                name = "${WorldMap.flagOf("DE")} DE VLESS",
                settings = ProxySettings.Vless(uuid = "u1"),
            ),
            node(
                id = "s2",
                name = "${WorldMap.flagOf("DE")} DE Trojan",
                settings = ProxySettings.Trojan(password = "p1"),
            ),
            node(
                id = "s3",
                name = "${WorldMap.flagOf("NL")} NL VLESS",
                settings = ProxySettings.Vless(uuid = "u2"),
            ),
            node(
                id = "s4",
                name = "${WorldMap.flagOf("NL")} NL Group",
                settings = ProxySettings.Hysteria2(password = "h1"),
                group = NodeGroup(
                    members = listOf(
                        node(id = "m1", name = "M1", settings = ProxySettings.Vless(uuid = "u3")),
                    ),
                ),
            ),
            node(
                id = "manual-pl",
                name = "${WorldMap.flagOf("PL")} Manual PL",
                subscriptionId = null,
            ),
        )

        renderServers(
            ServersUiState(
                nodes = nodes,
                subscriptions = listOf(sub()),
            ),
            localeTag = "ru",
        )

        // Open filters sheet -> filter by VLESS (matches s1, s3, s4, manual-pl; excludes s2)
        compose.onNodeWithTag("servers-filter-button").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("servers-filter-protocols").assertIsDisplayed()
        compose.onNodeWithTag("servers-filter-sort-bar").assertIsDisplayed()
        compose.onNodeWithTag("protocol-chip-VLESS").performClick()
        compose.waitForIdle()

        // Navigate to country picker sub-page inside the sheet
        compose.onNodeWithTag("servers-filter-countries-row").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("country-tile-DE").assertIsDisplayed()
        compose.onNodeWithTag("country-tile-PL").assertDoesNotExist()
        compose.onNodeWithTag("country-row-PL").assertDoesNotExist()

        // Country search works in Russian and by ISO code
        compose.onNodeWithTag("servers-filter-country-search").performTextReplacement("Герм")
        compose.waitForIdle()
        compose.onNodeWithTag("country-row-DE").assertIsDisplayed()
        compose.onNodeWithTag("country-row-DE").performClick()
        compose.waitForIdle()

        // Apply and close sheet
        compose.onNodeWithTag("servers-filter-apply").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("servers-filter-button-count").assertTextEquals("2")
        compose.onNodeWithTag("server-row-s1").assertExists()
        compose.onNodeWithTag("server-row-s2").assertDoesNotExist()
        compose.onNodeWithTag("server-row-s3").assertDoesNotExist()
        compose.onNodeWithTag("server-row-s4").assertDoesNotExist()

        // Reopen sheet and reset all filters
        compose.onNodeWithTag("servers-filter-button").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("servers-filter-reset-all").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("servers-filter-apply").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("servers-filter-button-count").assertDoesNotExist()
        listOf("s1", "s2", "s3", "s4").forEach { id ->
            compose.onNodeWithTag("server-row-$id").assertExists()
        }
    }

    @Test
    fun russianLocale_at320dpLargeFontScale_rendersFilterSheetAndCountCleanly() {
        val nodes = buildList {
            repeat(120) { i ->
                add(
                    node(
                        id = "mw-$i",
                        name = "${WorldMap.flagOf("MW")} MW #$i",
                        settings = ProxySettings.Vless(uuid = "u-$i"),
                    ),
                )
            }
            add(
                node(
                    id = "de-1",
                    name = "${WorldMap.flagOf("DE")} DE #1",
                    settings = ProxySettings.Trojan(password = "p1"),
                ),
            )
            add(
                node(
                    id = "nl-1",
                    name = "${WorldMap.flagOf("NL")} NL #1",
                    settings = ProxySettings.Hysteria2(password = "h1"),
                ),
            )
        }

        renderServers(
            state = ServersUiState(nodes = nodes, subscriptions = listOf(sub())),
            widthDp = 320.dp,
            fontScale = 1.3f,
            localeTag = "ru",
        )

        compose.onNodeWithTag("servers-filter-button").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Фильтры").assertIsDisplayed()
        compose.onNodeWithText("Протокол").assertIsDisplayed()
        compose.onNodeWithText("Страны").assertIsDisplayed()
        compose.onNodeWithText("Сортировка").assertIsDisplayed()

        compose.onNodeWithTag("protocol-chip-VLESS").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("servers-filter-countries-row").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("country-tile-MW").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Сбросить (1)").assertIsDisplayed()

        compose.onNodeWithTag("servers-filter-country-back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("servers-filter-reset-all").assertIsDisplayed()
        compose.onNodeWithTag("servers-filter-apply").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("servers-filter-button-count").assertTextEquals("2")
    }
}
