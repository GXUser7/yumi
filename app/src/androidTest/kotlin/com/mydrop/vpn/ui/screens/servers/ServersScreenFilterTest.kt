package com.mydrop.vpn.ui.screens.servers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mydrop.vpn.core.model.NodeGroup
import com.mydrop.vpn.core.model.Protocol
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.ProxySettings
import com.mydrop.vpn.core.model.Subscription
import com.mydrop.vpn.core.model.WorldMap
import com.mydrop.vpn.ui.ServersUiState
import com.mydrop.vpn.ui.theme.MyDropTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            DeviceConfigurationOverride(override) {
                MyDropTheme(dynamicColor = false) {
                    Box(Modifier.width(widthDp).height(800.dp)) {
                        ServersScreen(
                            state = state,
                            onSelect = {},
                            onPing = {},
                            onRemove = {},
                            onSetTlsInsecure = { _, _ -> },
                            onToggleGroup = {},
                            contentPadding = PaddingValues(0.dp),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun twoSeparateFilterBlocks_bothCollapsedByDefault() {
        val codes = listOf("US", "DE", "NL", "LV", "FR", "GB", "JP", "SG", "FI", "SE", "CA", "AU")
        val nodes = codes.mapIndexed { i, code ->
            node(id = "n-$i", name = "${WorldMap.flagOf(code)} Server $code", subscriptionId = "sub-1")
        } + node(id = "manual-pl", name = "${WorldMap.flagOf("PL")} Manual PL", subscriptionId = null)

        renderServers(
            ServersUiState(
                nodes = nodes,
                subscriptions = listOf(sub()),
            ),
        )

        compose.onNodeWithTag("protocol-filter-block").assertIsDisplayed()
        compose.onNodeWithTag("protocol-filter-header").assertIsDisplayed()
        compose.onNodeWithTag("protocol-chip-VLESS").assertDoesNotExist()
        compose.onNodeWithTag("country-filter-block").assertIsDisplayed()
        compose.onNodeWithTag("country-filter-header").assertIsDisplayed()
        compose.onNodeWithTag("country-tile-DE").assertDoesNotExist()
        compose.onNodeWithTag("country-tile-PL").assertDoesNotExist()
    }

    @Test
    fun expandCollapseProtocolAndCountryBlocks_togglesContent_andExcludesManualCountries() {
        val codes = listOf("US", "DE", "NL", "LV", "FR", "GB", "JP", "SG")
        val nodes = codes.mapIndexed { i, code ->
            node(id = "n-$i", name = "${WorldMap.flagOf(code)} Server $code", subscriptionId = "sub-1")
        } + node(id = "manual-pl", name = "${WorldMap.flagOf("PL")} Manual PL", subscriptionId = null)

        renderServers(
            ServersUiState(
                nodes = nodes,
                subscriptions = listOf(sub()),
            ),
        )

        compose.onNodeWithTag("protocol-filter-header").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("protocol-chip-VLESS").assertIsDisplayed()

        compose.onNodeWithTag("protocol-filter-header").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("protocol-chip-VLESS").assertDoesNotExist()

        compose.onNodeWithTag("country-filter-header").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("country-tile-DE").assertIsDisplayed()
        compose.onNodeWithTag("country-tile-PL").assertDoesNotExist()

        compose.onNodeWithTag("country-filter-header").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("country-tile-DE").assertDoesNotExist()
    }

    @Test
    fun filterHeadersAndGroupHeaderChevrons_alignHorizontallyAndCenterVertically() {
        val nodes = listOf(
            node(
                id = "s1",
                name = "${WorldMap.flagOf("DE")} DE VLESS",
                settings = ProxySettings.Vless(uuid = "u1"),
            ),
            node(
                id = "s2",
                name = "${WorldMap.flagOf("NL")} NL Trojan",
                settings = ProxySettings.Trojan(password = "p1"),
            ),
        )

        renderServers(
            ServersUiState(
                nodes = nodes,
                subscriptions = listOf(sub()),
            ),
            widthDp = 448.dp,
        )

        val protoHeader = compose.onNodeWithTag("protocol-filter-header").getBoundsInRoot()
        val countryHeader = compose.onNodeWithTag("country-filter-header").getBoundsInRoot()
        val groupHeader = compose.onNodeWithTag("group-header-sub-1").getBoundsInRoot()
        val protoChevron = compose.onNodeWithTag("protocol-filter-header-chevron", useUnmergedTree = true)
            .getBoundsInRoot()
        val countryChevron = compose.onNodeWithTag("country-filter-header-chevron", useUnmergedTree = true)
            .getBoundsInRoot()
        val groupChevron = compose.onNodeWithTag("group-header-sub-1-chevron", useUnmergedTree = true)
            .getBoundsInRoot()

        // Chevrons stay flush with the 16.dp inner right edge (not stranded mid-row)
        assertTrue(
            "Protocol chevron should be near right edge of header: chevron=$protoChevron header=$protoHeader",
            protoHeader.right - protoChevron.right in 15.dp..17.dp,
        )
        assertTrue(
            "Protocol, Country, and Group chevrons must share the same horizontal bounds: " +
                "proto=$protoChevron country=$countryChevron group=$groupChevron",
            abs((protoChevron.left - countryChevron.left).value) <= 0.5f &&
                abs((protoChevron.right - countryChevron.right).value) <= 0.5f &&
                abs((countryChevron.left - groupChevron.left).value) <= 0.5f &&
                abs((countryChevron.right - groupChevron.right).value) <= 0.5f,
        )

        // Vertical centering inside collapsed headers
        val protoHeaderCenterY = (protoHeader.top.value + protoHeader.bottom.value) / 2f
        val protoChevronCenterY = (protoChevron.top.value + protoChevron.bottom.value) / 2f
        val countryHeaderCenterY = (countryHeader.top.value + countryHeader.bottom.value) / 2f
        val countryChevronCenterY = (countryChevron.top.value + countryChevron.bottom.value) / 2f
        val groupHeaderCenterY = (groupHeader.top.value + groupHeader.bottom.value) / 2f
        val groupChevronCenterY = (groupChevron.top.value + groupChevron.bottom.value) / 2f
        assertTrue(
            "Protocol chevron not vertically centered: headerCenter=$protoHeaderCenterY chevronCenter=$protoChevronCenterY",
            abs(protoHeaderCenterY - protoChevronCenterY) <= 1f,
        )
        assertTrue(
            "Country chevron not vertically centered: headerCenter=$countryHeaderCenterY chevronCenter=$countryChevronCenterY",
            abs(countryHeaderCenterY - countryChevronCenterY) <= 1f,
        )
        assertTrue(
            "Group chevron not vertically centered: headerCenter=$groupHeaderCenterY chevronCenter=$groupChevronCenterY",
            abs(groupHeaderCenterY - groupChevronCenterY) <= 1f,
        )

        // Expanded protocols + countries keep exact horizontal chevron alignment
        compose.onNodeWithTag("protocol-filter-header").performClick()
        compose.onNodeWithTag("country-filter-header").performClick()
        compose.waitForIdle()

        val protoChevronExpanded = compose.onNodeWithTag("protocol-filter-header-chevron", useUnmergedTree = true)
            .getBoundsInRoot()
        val countryChevronExpanded = compose.onNodeWithTag("country-filter-header-chevron", useUnmergedTree = true)
            .getBoundsInRoot()
        assertTrue(
            "Expanded chevrons shifted horizontally: proto=$protoChevronExpanded country=$countryChevronExpanded",
            abs((protoChevronExpanded.left - protoChevron.left).value) <= 0.5f &&
                abs((countryChevronExpanded.left - countryChevron.left).value) <= 0.5f,
        )

        // Active selections (selectedCount > 0 in both blocks) preserve horizontal chevron alignment
        compose.onNodeWithTag("protocol-chip-VLESS").performClick()
        compose.onNodeWithTag("country-tile-DE").performClick()
        compose.waitForIdle()

        val protoChevronSelected = compose.onNodeWithTag("protocol-filter-header-chevron", useUnmergedTree = true)
            .getBoundsInRoot()
        val countryChevronSelected = compose.onNodeWithTag("country-filter-header-chevron", useUnmergedTree = true)
            .getBoundsInRoot()
        val groupChevronSelected = compose.onNodeWithTag("group-header-sub-1-chevron", useUnmergedTree = true)
            .getBoundsInRoot()
        assertTrue(
            "Selected-state chevrons shifted horizontally: proto=$protoChevronSelected country=$countryChevronSelected group=$groupChevronSelected",
            abs((protoChevronSelected.left - protoChevron.left).value) <= 0.5f &&
                abs((countryChevronSelected.left - countryChevron.left).value) <= 0.5f &&
                abs((groupChevronSelected.left - groupChevron.left).value) <= 0.5f,
        )
    }

    @Test
    fun protocolAndCountryFilters_narrowVisibleServerRows() {
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
        )

        renderServers(
            ServersUiState(
                nodes = nodes,
                subscriptions = listOf(sub()),
            ),
        )

        // Expand protocols and filter by VLESS: matches s1, s3, and s4 (via NodeGroup member), excludes s2
        compose.onNodeWithTag("protocol-filter-header").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("protocol-chip-VLESS").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("server-row-s1").assertExists()
        compose.onNodeWithTag("server-row-s2").assertDoesNotExist()
        compose.onNodeWithTag("server-row-s3").assertExists()
        compose.onNodeWithTag("server-row-s4").assertExists()

        // Collapse protocols: selection summary + Reset action stay visible and filter remains active
        compose.onNodeWithTag("protocol-filter-header").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("protocol-chip-VLESS").assertDoesNotExist()
        compose.onNodeWithTag("protocol-filter-header-clear").assertIsDisplayed()
        compose.onNodeWithTag("server-row-s2").assertDoesNotExist()

        // Expand countries and select DE: narrows to only s1
        compose.onNodeWithTag("country-filter-header").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("country-tile-DE").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("server-row-s1").assertExists()
        compose.onNodeWithTag("server-row-s3").assertDoesNotExist()
        compose.onNodeWithTag("server-row-s4").assertDoesNotExist()

        // Reset both filters via header Reset actions: all 4 rows return
        compose.onNodeWithTag("country-filter-header-clear").performClick()
        compose.onNodeWithTag("protocol-filter-header-clear").performClick()
        compose.waitForIdle()

        listOf("s1", "s2", "s3", "s4").forEach { id ->
            compose.onNodeWithTag("server-row-$id").assertExists()
        }
    }

    @Test
    fun countryGrid_usesThreeColumnsAt320dp_withoutOverlapOrClipping() {
        assertGridAt(widthDp = 320.dp, fontScale = 1.0f, expectedColumns = 3)
    }

    @Test
    fun countryGrid_usesThreeColumnsAt360dp_withoutOverlapOrClipping() {
        assertGridAt(widthDp = 360.dp, fontScale = 1.0f, expectedColumns = 3)
    }

    @Test
    fun countryGrid_usesFourColumnsAt448dp_withoutOverlapOrClipping() {
        assertGridAt(widthDp = 448.dp, fontScale = 1.0f, expectedColumns = 4)
    }

    @Test
    fun countryGrid_dropsToThreeColumnsAt448dpWithEnlargedFontScale() {
        assertGridAt(widthDp = 448.dp, fontScale = 1.3f, expectedColumns = 3)
    }

    @Test
    fun countryGrid_threeDigitCounts_noClipAt320dpWithLargeFontScale_andPartialLastRow() {
        val wideCodesWithCounts = listOf(
            "MM" to 999,
            "MW" to 999,
            "WS" to 999,
            "DE" to 4,
            "NL" to 3,
            "US" to 2,
            "LV" to 1,
        )
        assertGridAt(
            widthDp = 320.dp,
            fontScale = 1.3f,
            expectedColumns = 3,
            codesWithCounts = wideCodesWithCounts,
        )
    }

    @Test
    fun countryGrid_threeDigitCounts_usesThreeColumnsWithoutClipAt380dpWith115FontScale() {
        val wideCodesWithCounts = listOf(
            "MM" to 999,
            "MW" to 999,
            "WS" to 999,
            "DE" to 4,
            "NL" to 3,
            "US" to 2,
            "LV" to 1,
        )
        assertGridAt(
            widthDp = 380.dp,
            fontScale = 1.15f,
            expectedColumns = 3,
            codesWithCounts = wideCodesWithCounts,
        )
    }

    @Test
    fun russianLocale_at320dpLargeFontScale_withActiveSelections_rendersWithoutOverlapOrClipping() {
        val nodes = buildList {
            repeat(999) { i ->
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
            add(
                node(
                    id = "us-1",
                    name = "${WorldMap.flagOf("US")} US #1",
                    settings = ProxySettings.Vless(uuid = "u-us"),
                ),
            )
        }

        renderServers(
            state = ServersUiState(nodes = nodes, subscriptions = listOf(sub())),
            widthDp = 320.dp,
            fontScale = 1.3f,
            localeTag = "ru",
        )

        compose.onNodeWithTag("protocol-filter-header").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("protocol-chip-VLESS").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("protocol-filter-header").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("country-filter-header").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("country-tile-MW").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Протоколы").assertIsDisplayed()
        compose.onNodeWithText("Страны").assertIsDisplayed()
        compose.onAllNodesWithText("Выбрано: 1").assertCountEquals(2)
        compose.onAllNodesWithText("Сбросить").assertCountEquals(2)

        assertSingleLineTextNoClip(compose.onNodeWithText("Протоколы").fetchSemanticsNode(), "Протоколы")
        assertSingleLineTextNoClip(compose.onNodeWithText("Страны").fetchSemanticsNode(), "Страны")
        val selectedNodes = compose.onAllNodesWithText("Выбрано: 1").fetchSemanticsNodes()
        val clearNodes = compose.onAllNodesWithText("Сбросить").fetchSemanticsNodes()
        selectedNodes.forEachIndexed { idx, node -> assertSingleLineTextNoClip(node, "Выбрано: 1 #$idx") }
        clearNodes.forEachIndexed { idx, node -> assertSingleLineTextNoClip(node, "Сбросить #$idx") }

        val protoHeaderBounds = compose.onNodeWithTag("protocol-filter-header").getBoundsInRoot()
        val protoSelectedBounds = compose.onNodeWithTag("protocol-filter-header-selected", useUnmergedTree = true)
            .getBoundsInRoot()
        val protoClearBounds = compose.onNodeWithTag("protocol-filter-header-clear", useUnmergedTree = true)
            .getBoundsInRoot()
        assertTrue(
            "Protocol RU selected badge overlaps Reset: selected=$protoSelectedBounds clear=$protoClearBounds",
            protoSelectedBounds.right <= protoClearBounds.left + 0.5.dp,
        )
        assertTrue(
            "Protocol RU clear button overflows header: clear=$protoClearBounds header=$protoHeaderBounds",
            protoClearBounds.right <= protoHeaderBounds.right + 0.5.dp,
        )

        val countryHeaderBounds = compose.onNodeWithTag("country-filter-header").getBoundsInRoot()
        val countrySelectedBounds = compose.onNodeWithTag("country-filter-header-selected", useUnmergedTree = true)
            .getBoundsInRoot()
        val countryClearBounds = compose.onNodeWithTag("country-filter-header-clear", useUnmergedTree = true)
            .getBoundsInRoot()
        assertTrue(
            "Country RU selected badge overlaps Reset: selected=$countrySelectedBounds clear=$countryClearBounds",
            countrySelectedBounds.right <= countryClearBounds.left + 0.5.dp,
        )
        assertTrue(
            "Country RU clear button overflows header: clear=$countryClearBounds header=$countryHeaderBounds",
            countryClearBounds.right <= countryHeaderBounds.right + 0.5.dp,
        )

        val mwTileNode = compose.onNodeWithTag("country-tile-MW", useUnmergedTree = true).fetchSemanticsNode()
        assertTextChildrenContainedWithoutOverflow("MW", mwTileNode)
    }

    private fun assertGridAt(
        widthDp: Dp,
        fontScale: Float,
        expectedColumns: Int,
        codesWithCounts: List<Pair<String, Int>> = listOf(
            "DE" to 8,
            "NL" to 7,
            "US" to 6,
            "LV" to 5,
            "FR" to 4,
            "GB" to 3,
            "JP" to 2,
            "SG" to 1,
        ),
    ) {
        val codes = codesWithCounts.map { it.first }
        val nodes = buildList {
            for ((code, count) in codesWithCounts) {
                repeat(count) { n ->
                    add(
                        node(
                            id = "$code-$n",
                            name = "${WorldMap.flagOf(code)} $code #$n",
                            subscriptionId = "sub-1",
                        ),
                    )
                }
            }
        }

        renderServers(
            state = ServersUiState(nodes = nodes, subscriptions = listOf(sub())),
            widthDp = widthDp,
            fontScale = fontScale,
        )

        compose.onNodeWithTag("country-filter-header").performClick()
        compose.waitForIdle()

        val blockBounds = compose.onNodeWithTag("country-filter-block").getBoundsInRoot()
        val row0Bounds = compose.onNodeWithTag("country-grid-row-0").getBoundsInRoot()
        val tileBoundsList = codes.map { code ->
            code to compose.onNodeWithTag("country-tile-$code").getBoundsInRoot()
        }

        // Group tiles into visual rows by top coordinate (within 2.dp tolerance)
        val visualRows = mutableListOf<MutableList<Pair<String, DpRect>>>()
        for (entry in tileBoundsList) {
            val matchingRow = visualRows.firstOrNull { row ->
                abs((row.first().second.top - entry.second.top).value) <= 2f
            }
            if (matchingRow != null) {
                matchingRow.add(entry)
            } else {
                visualRows.add(mutableListOf(entry))
            }
        }

        val expectedRowSizes = codes.chunked(expectedColumns).map { it.size }
        assertEquals(
            "Expected row sizes $expectedRowSizes at width=$widthDp fontScale=$fontScale",
            expectedRowSizes,
            visualRows.map { it.size },
        )

        // Zero tile-tile overlap
        for (i in tileBoundsList.indices) {
            val (codeA, a) = tileBoundsList[i]
            for (j in i + 1 until tileBoundsList.size) {
                val (codeB, b) = tileBoundsList[j]
                val separated =
                    a.right <= b.left + 0.5.dp ||
                        b.right <= a.left + 0.5.dp ||
                        a.bottom <= b.top + 0.5.dp ||
                        b.bottom <= a.top + 0.5.dp
                assertTrue("Tiles $codeA and $codeB overlap: $a vs $b", separated)
            }
        }

        // Containment inside block/row bounds and inner text child bounds check (zero clipping/overflow)
        for ((rowIdx, rowEntries) in visualRows.withIndex()) {
            val rowBounds = compose.onNodeWithTag("country-grid-row-$rowIdx").getBoundsInRoot()
            for ((code, tileBounds) in rowEntries) {
                assertTrue("Tile $code width must be > 0: $tileBounds", tileBounds.right > tileBounds.left)
                assertTrue("Tile $code height must be > 0: $tileBounds", tileBounds.bottom > tileBounds.top)
                assertTrue(
                    "Tile $code left (${tileBounds.left}) < block left (${blockBounds.left})",
                    tileBounds.left >= blockBounds.left - 0.5.dp,
                )
                assertTrue(
                    "Tile $code right (${tileBounds.right}) > block right (${blockBounds.right})",
                    tileBounds.right <= blockBounds.right + 0.5.dp,
                )
                assertTrue(
                    "Tile $code left (${tileBounds.left}) < row0 left (${row0Bounds.left})",
                    tileBounds.left >= row0Bounds.left - 0.5.dp,
                )
                assertTrue(
                    "Tile $code right (${tileBounds.right}) > row0 right (${row0Bounds.right})",
                    tileBounds.right <= row0Bounds.right + 0.5.dp,
                )
                assertTrue(
                    "Tile $code outside row $rowIdx vertical bounds: $tileBounds vs $rowBounds",
                    tileBounds.top >= rowBounds.top - 0.5.dp && tileBounds.bottom <= rowBounds.bottom + 0.5.dp,
                )

                val tileNode = compose.onNodeWithTag("country-tile-$code", useUnmergedTree = true)
                    .fetchSemanticsNode()
                assertTextChildrenContainedWithoutOverflow(code, tileNode)
            }
        }
    }

    private fun assertTextChildrenContainedWithoutOverflow(
        code: String,
        tileNode: SemanticsNode,
    ) {
        val tileRect = tileNode.boundsInRoot
        assertTrue("Tile $code should have text children", tileNode.children.isNotEmpty())
        for (child in tileNode.children) {
            val childRect = child.boundsInRoot
            assertTrue(
                "Text child in $code has non-positive size: $childRect",
                childRect.width > 0f && childRect.height > 0f,
            )
            assertTrue(
                "Text child in $code overflows tile bounds: child=$childRect tile=$tileRect",
                childRect.left >= tileRect.left - 1f &&
                    childRect.right <= tileRect.right + 1f &&
                    childRect.top >= tileRect.top - 1f &&
                    childRect.bottom <= tileRect.bottom + 1f,
            )
            val layoutResults = mutableListOf<TextLayoutResult>()
            val gotLayout = child.config.getOrNull(SemanticsActions.GetTextLayoutResult)
                ?.action
                ?.invoke(layoutResults)
            assertTrue(
                "Expected GetTextLayoutResult on text child in $code",
                gotLayout == true && layoutResults.isNotEmpty(),
            )
            val layout = layoutResults.first()
            assertEquals(
                "Text in $code wrapped onto multiple lines: ${layout.layoutInput.text}",
                1,
                layout.lineCount,
            )
            assertFalse(
                "Text in $code was ellipsized: ${layout.layoutInput.text}",
                layout.isLineEllipsized(0),
            )
            assertTrue(
                "Text in $code clipped horizontally: lineRight=${layout.getLineRight(0)} width=${layout.size.width} text=${layout.layoutInput.text}",
                layout.getLineRight(0) <= layout.size.width + 2f,
            )
        }
    }

    private fun assertSingleLineTextNoClip(node: SemanticsNode, label: String) {
        val layoutResults = mutableListOf<TextLayoutResult>()
        val gotLayout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(layoutResults)
        assertTrue(
            "Expected GetTextLayoutResult on $label",
            gotLayout == true && layoutResults.isNotEmpty(),
        )
        val layout = layoutResults.first()
        assertEquals("$label wrapped onto multiple lines", 1, layout.lineCount)
        assertFalse("$label was ellipsized", layout.isLineEllipsized(0))
        assertTrue(
            "$label clipped horizontally: lineRight=${layout.getLineRight(0)} width=${layout.size.width}",
            layout.getLineRight(0) <= layout.size.width + 2f,
        )
    }
}
