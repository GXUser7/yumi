package com.mydrop.vpn.vpn

import android.app.PendingIntent
import android.view.View
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.shared.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class TunnelWidgetProviderTest {

    @Test
    fun `widgetSpecFor maps all 5 VpnStates and all Connecting phases distinctly`() {
        val disconnected = widgetSpecFor(VpnState.Disconnected)
        assertEquals(R.drawable.widget_bg_off, disconnected.backgroundRes)
        assertEquals(R.drawable.ic_widget_yumi_off, disconnected.markRes)
        assertEquals(R.drawable.ic_widget_cue_off, disconnected.cueRes)
        assertFalse(disconnected.showConnectingBorder)
        assertEquals(R.string.tile_off, disconnected.stateLabelRes)

        val connectingSpecs = VpnState.Connecting.Phase.entries.map { phase ->
            widgetSpecFor(VpnState.Connecting(nodeId = "n1", phase = phase))
        }
        connectingSpecs.forEach { spec ->
            assertEquals(R.drawable.widget_bg_connecting, spec.backgroundRes)
            assertEquals(R.drawable.ic_widget_yumi_connecting, spec.markRes)
            assertEquals(R.drawable.ic_widget_cue_connecting, spec.cueRes)
            assertTrue(spec.showConnectingBorder)
            assertEquals(R.string.tile_connecting, spec.stateLabelRes)
        }

        val disconnecting = widgetSpecFor(VpnState.Disconnecting)
        assertEquals(R.drawable.widget_bg_connecting, disconnecting.backgroundRes)
        assertEquals(R.drawable.ic_widget_yumi_connecting, disconnecting.markRes)
        assertEquals(R.drawable.ic_widget_cue_connecting, disconnecting.cueRes)
        assertTrue(disconnecting.showConnectingBorder)
        assertEquals(R.string.tile_disconnecting, disconnecting.stateLabelRes)

        val connected = widgetSpecFor(VpnState.Connected(nodeId = "n1", connectedAtEpochMillis = 42L))
        assertEquals(R.drawable.widget_bg_on, connected.backgroundRes)
        assertEquals(R.drawable.ic_widget_yumi_on, connected.markRes)
        assertEquals(R.drawable.ic_widget_cue_on, connected.cueRes)
        assertFalse(connected.showConnectingBorder)
        assertEquals(R.string.tile_connected, connected.stateLabelRes)

        val failed = widgetSpecFor(VpnState.Failed(nodeId = "n1", message = "boom"))
        assertEquals(R.drawable.widget_bg_failed, failed.backgroundRes)
        assertEquals(R.drawable.ic_widget_yumi_failed, failed.markRes)
        assertEquals(R.drawable.ic_widget_cue_failed, failed.cueRes)
        assertFalse(failed.showConnectingBorder)
        assertEquals(R.string.tile_error, failed.stateLabelRes)

        val distinctStateLabels = setOf(
            disconnected.stateLabelRes,
            connectingSpecs.first().stateLabelRes,
            disconnecting.stateLabelRes,
            connected.stateLabelRes,
            failed.stateLabelRes,
        )
        assertEquals(5, distinctStateLabels.size)
    }

    @Test
    fun `toggleActionFor handles Disconnected, Failed, Connecting, Connected, and Disconnecting`() {
        assertEquals(ToggleAction.Connect, toggleActionFor(VpnState.Disconnected))
        assertEquals(ToggleAction.Connect, toggleActionFor(VpnState.Failed(nodeId = null, message = "err")))
        VpnState.Connecting.Phase.entries.forEach { phase ->
            assertEquals(
                ToggleAction.Disconnect,
                toggleActionFor(VpnState.Connecting(nodeId = "n1", phase = phase)),
            )
        }
        assertEquals(
            ToggleAction.Disconnect,
            toggleActionFor(VpnState.Connected(nodeId = "n1", connectedAtEpochMillis = 1L)),
        )
        assertEquals(ToggleAction.Ignore, toggleActionFor(VpnState.Disconnecting))
    }

    @Test
    fun `bindWidget applies background, border visibility, mark, cue, description, and click target`() {
        val states = listOf(
            VpnState.Disconnected to "Yumi: Off",
            VpnState.Connecting("n1", VpnState.Connecting.Phase.Handshaking) to "Yumi: Connecting…",
            VpnState.Disconnecting to "Yumi: Disconnecting…",
            VpnState.Connected("n1", 100L) to "Yumi: Connected",
            VpnState.Failed("n1", "fail") to "Yumi: Error",
        )

        states.forEach { (state, label) ->
            val spec = widgetSpecFor(state)
            val target = RecordingWidgetTarget()
            bindWidget(spec, label, pendingIntent = null, target = target)

            assertEquals(spec.backgroundRes, target.backgrounds[android.R.id.background])
            assertEquals(
                if (spec.showConnectingBorder) View.VISIBLE else View.GONE,
                target.visibilities[R.id.widget_border_overlay],
            )
            assertEquals(spec.markRes, target.images[R.id.widget_mark])
            assertEquals(spec.cueRes, target.images[R.id.widget_status_cue])
            assertEquals(label, target.descriptions[android.R.id.background]?.toString())
            assertTrue(target.clickTargets.contains(android.R.id.background))
        }
    }

    @Test
    fun `formatWidgetContentDescription formats EN and RU templates cleanly`() {
        assertEquals(
            "Yumi: Connected",
            formatWidgetContentDescription("%1\$s: %2\$s", "Yumi", "Connected"),
        )
        assertEquals(
            "Yumi: Подключен",
            formatWidgetContentDescription("%1\$s: %2\$s", "Yumi", "Подключен"),
        )
    }

    @Test
    fun `widget XML layout, color qualifiers, dimens, widget info, and strings obey API and RemoteViews contracts`() {
        val resDir = locateSharedResDir()

        // 1. widget_tunnel.xml: only @RemoteView tags, 0dp outer padding/margin, importantForAccessibility="no" on inner views
        val layoutDoc = parseXml(File(resDir, "layout/widget_tunnel.xml"))
        val root = layoutDoc.documentElement
        assertEquals("FrameLayout", root.tagName)
        assertEquals("@android:id/background", root.getAttribute("android:id"))
        assertFalse(root.hasAttribute("android:padding"))
        assertFalse(root.hasAttribute("android:layout_margin"))

        val allowedRemoteViewTags = setOf("FrameLayout", "LinearLayout", "ImageView", "RelativeLayout")
        val allElements = mutableListOf<Element>()
        fun collect(el: Element) {
            allElements += el
            val children = el.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node is Element) collect(node)
            }
        }
        collect(root)
        allElements.forEach { el ->
            assertTrue("Unsupported RemoteViews tag: ${el.tagName}", el.tagName in allowedRemoteViewTags)
            if (el !== root) {
                assertEquals(
                    "Non-root element ${el.tagName} (${el.getAttribute("android:id")}) should set importantForAccessibility=no",
                    "no",
                    el.getAttribute("android:importantForAccessibility"),
                )
            }
        }

        // 2. tunnel_widget_info.xml preserves 1x1 cell sizing and preview/initial layout
        val infoDoc = parseXml(File(resDir, "xml/tunnel_widget_info.xml")).documentElement
        assertEquals("1", infoDoc.getAttribute("android:targetCellWidth"))
        assertEquals("1", infoDoc.getAttribute("android:targetCellHeight"))
        assertEquals("40dp", infoDoc.getAttribute("android:minWidth"))
        assertEquals("40dp", infoDoc.getAttribute("android:minHeight"))
        assertEquals("none", infoDoc.getAttribute("android:resizeMode"))
        assertEquals("@layout/widget_tunnel", infoDoc.getAttribute("android:initialLayout"))
        assertEquals("@layout/widget_tunnel", infoDoc.getAttribute("android:previewLayout"))

        // 3. Color token parity & API-level isolation across values/, values-v31/, values-night-v31/, values-v34/, values-night-v34/
        val baseColors = readResourceMap(File(resDir, "values/colors.xml"), "color")
            .filterKeys { it.startsWith("widget_") }
        assertTrue(baseColors.isNotEmpty())
        baseColors.values.forEach { value ->
            assertFalse("values/colors.xml must not reference system_* tokens: $value", value.contains("@android:color/system_"))
        }

        val v31Colors = readResourceMap(File(resDir, "values-v31/colors.xml"), "color")
        val nightV31Colors = readResourceMap(File(resDir, "values-night-v31/colors.xml"), "color")
        val v34Colors = readResourceMap(File(resDir, "values-v34/colors.xml"), "color")
        val nightV34Colors = readResourceMap(File(resDir, "values-night-v34/colors.xml"), "color")

        assertEquals(baseColors.keys, v31Colors.keys)
        assertEquals(baseColors.keys, nightV31Colors.keys)
        assertEquals(baseColors.keys, v34Colors.keys)
        assertEquals(baseColors.keys, nightV34Colors.keys)

        val api34OnlyHints = listOf("primary_", "surface_", "outline_", "error_")
        (v31Colors.values + nightV31Colors.values).forEach { value ->
            if (value.startsWith("@android:color/system_")) {
                api34OnlyHints.forEach { hint ->
                    assertFalse("values-v31 must not use API 34+ token $value", value.contains(hint))
                }
            }
        }
        (v34Colors.values + nightV34Colors.values).forEach { value ->
            assertFalse(
                "Must not reference system_on_error_container_dark (absent in API 34): $value",
                value.contains("system_on_error_container_dark"),
            )
        }

        // 4. Dimens parity & API-level isolation
        val baseDimens = readResourceMap(File(resDir, "values/dimens.xml"), "dimen")
        val v31Dimens = readResourceMap(File(resDir, "values-v31/dimens.xml"), "dimen")
        assertEquals(setOf("widget_outer_radius"), baseDimens.keys)
        assertEquals(baseDimens.keys, v31Dimens.keys)
        assertFalse(baseDimens.getValue("widget_outer_radius").contains("@android:dimen/system_"))
        assertEquals("@android:dimen/system_app_widget_background_radius", v31Dimens.getValue("widget_outer_radius"))

        // 5. EN and RU widget strings parity
        val enStrings = readResourceMap(File(resDir, "values/strings.xml"), "string")
        val ruStrings = readResourceMap(File(resDir, "values-ru/strings.xml"), "string")
        assertTrue(enStrings.containsKey("widget_content_description"))
        assertTrue(ruStrings.containsKey("widget_content_description"))
        assertTrue(enStrings.getValue("widget_content_description").contains("%1\$s"))
        assertTrue(enStrings.getValue("widget_content_description").contains("%2\$s"))
        assertTrue(ruStrings.getValue("widget_content_description").contains("%1\$s"))
        assertTrue(ruStrings.getValue("widget_content_description").contains("%2\$s"))
        assertTrue(enStrings.containsKey("widget_description"))
        assertTrue(ruStrings.containsKey("widget_description"))
    }

    private fun locateSharedResDir(): File {
        val candidates = listOf(
            File("src/main/res"),
            File("shared/src/main/res"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Unable to locate shared/src/main/res from ${File(".").absolutePath}")
    }

    private fun parseXml(file: File) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private fun readResourceMap(file: File, tag: String): Map<String, String> {
        val doc = parseXml(file)
        val nodes = doc.getElementsByTagName(tag)
        val out = linkedMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            out[el.getAttribute("name")] = el.textContent.trim()
        }
        return out
    }

    private class RecordingWidgetTarget : WidgetRemoteTarget {
        val backgrounds = mutableMapOf<Int, Int>()
        val images = mutableMapOf<Int, Int>()
        val visibilities = mutableMapOf<Int, Int>()
        val descriptions = mutableMapOf<Int, CharSequence>()
        val clickTargets = mutableSetOf<Int>()

        override fun setBackgroundResource(viewId: Int, resId: Int) {
            backgrounds[viewId] = resId
        }

        override fun setImageResource(viewId: Int, resId: Int) {
            images[viewId] = resId
        }

        override fun setViewVisibility(viewId: Int, visibility: Int) {
            visibilities[viewId] = visibility
        }

        override fun setContentDescription(viewId: Int, description: CharSequence) {
            descriptions[viewId] = description
        }

        override fun setOnClickPendingIntent(viewId: Int, pendingIntent: PendingIntent?) {
            clickTargets += viewId
        }
    }
}
