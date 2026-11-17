package com.mydrop.vpn.vpn

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.shared.R as SharedR
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TunnelWidgetRemoteViewsTest {

    @Test
    fun allVpnStatesInflateCleanlyWithPixelProportionsAndAccessibility() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        val cellWidthPx = (89.33f * density).roundToInt()
        val cellHeightPx = (104.44f * density).roundToInt()

        val cases = listOf(
            VpnState.Disconnected to Pair(View.GONE, context.getString(SharedR.string.tile_off)),
            VpnState.Connecting("n1", VpnState.Connecting.Phase.Starting) to
                Pair(View.VISIBLE, context.getString(SharedR.string.tile_connecting)),
            VpnState.Disconnecting to
                Pair(View.VISIBLE, context.getString(SharedR.string.tile_disconnecting)),
            VpnState.Connected("n1", 1234L) to
                Pair(View.GONE, context.getString(SharedR.string.tile_connected)),
            VpnState.Failed("n1", "timeout") to
                Pair(View.GONE, context.getString(SharedR.string.tile_error)),
        )

        val renderedSurfaceColors = mutableMapOf<String, Int>()
        val renderedMarkColors = mutableMapOf<String, Int>()

        cases.forEach { (state, expected) ->
            val (expectedBorderVisibility, expectedStateLabel) = expected
            val parent = FrameLayout(context)
            val remoteViews = TunnelWidgetProvider.createRemoteViews(context, state)
            val inflated = remoteViews.apply(context, parent)

            inflated.measure(
                MeasureSpec.makeMeasureSpec(cellWidthPx, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cellHeightPx, MeasureSpec.EXACTLY),
            )
            inflated.layout(0, 0, cellWidthPx, cellHeightPx)

            val background = inflated.findViewById<View>(android.R.id.background)
            assertNotNull("Missing @android:id/background for $state", background)
            assertEquals(0, background.paddingLeft)
            assertEquals(0, background.paddingTop)
            assertEquals(0, background.paddingRight)
            assertEquals(0, background.paddingBottom)
            assertNotNull(background.background)

            val desc = background.contentDescription?.toString().orEmpty()
            assertTrue("Expected app name in description '$desc'", desc.contains(context.getString(SharedR.string.app_name)))
            assertTrue("Expected '$expectedStateLabel' in description '$desc'", desc.contains(expectedStateLabel))

            val borderOverlay = inflated.findViewById<ImageView>(SharedR.id.widget_border_overlay)
            assertNotNull(borderOverlay)
            assertEquals(expectedBorderVisibility, borderOverlay.visibility)

            val mark = inflated.findViewById<ImageView>(SharedR.id.widget_mark)
            assertNotNull(mark)
            assertTrue("Expected positive mark width for $state", mark.width > 0)
            assertTrue(
                "Expected mark height >= 38dp (${(38 * density).toInt()}px), got ${mark.height}px for $state",
                mark.height >= (38 * density).toInt(),
            )

            val cue = inflated.findViewById<ImageView>(SharedR.id.widget_status_cue)
            assertNotNull(cue)
            assertTrue("Expected positive cue width for $state", cue.width > 0)
            assertTrue("Expected positive cue height for $state", cue.height > 0)
            assertTrue("Expected cue below mark without overlap for $state", cue.top >= mark.bottom)

            // Render to a real Bitmap and verify surface, mark, and cue actually draw non-transparent pixels.
            val bitmap = Bitmap.createBitmap(cellWidthPx, cellHeightPx, Bitmap.Config.ARGB_8888)
            inflated.draw(Canvas(bitmap))
            val surfacePixel = bitmap.getPixel(cellWidthPx / 2, (12 * density).roundToInt())
            assertTrue("Expected opaque surface pixel for $state", (surfacePixel ushr 24) == 0xFF)
            renderedSurfaceColors[state::class.simpleName.orEmpty()] = surfacePixel

            // Sample inside top-left square of the Yumi mark (relative to mark bounds)
            val markSampleX = mark.left + (mark.width * 0.35f).roundToInt()
            val markSampleY = mark.top + (mark.height * 0.25f).roundToInt()
            val markPixel = bitmap.getPixel(markSampleX, markSampleY)
            assertNotEquals("Expected mark pixel distinct from surface for $state", surfacePixel, markPixel)
            renderedMarkColors[state::class.simpleName.orEmpty()] = markPixel

            // Sample across the status cue's horizontal centerline (handles single pills, 3 dots, and broken alert pill)
            val cueCenterY = (cue.top + cue.bottom) / 2
            val hasVisibleCuePixel = (cue.left until cue.right).any { x ->
                bitmap.getPixel(x, cueCenterY) != surfacePixel
            }
            assertTrue("Expected status cue pixels distinct from surface for $state", hasVisibleCuePixel)

            bitmap.recycle()
        }

        // Off, Connecting, Connected, and Failed must render visually distinct Material You surfaces.
        val distinctSurfaces = setOf(
            renderedSurfaceColors.getValue("Disconnected"),
            renderedSurfaceColors.getValue("Connecting"),
            renderedSurfaceColors.getValue("Connected"),
            renderedSurfaceColors.getValue("Failed"),
        )
        assertEquals(4, distinctSurfaces.size)
    }
}
