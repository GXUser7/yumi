package com.mydrop.vpn.ui.screens.zones

import android.Manifest
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GpsOff
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.toPath
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrop.vpn.core.model.GeoCellRecord
import com.mydrop.vpn.core.model.GeoSample
import com.mydrop.vpn.core.model.GeoZoneCluster
import com.mydrop.vpn.core.model.GeoZoneEngine
import com.mydrop.vpn.core.model.GeoZoneStoreState
import com.mydrop.vpn.core.model.HexCoord
import com.mydrop.vpn.core.model.LatLon
import com.mydrop.vpn.core.model.ProxyNode
import com.mydrop.vpn.core.model.WifiAnchor
import com.mydrop.vpn.core.model.ZoneKind
import com.mydrop.vpn.core.model.ZoneProximity
import com.mydrop.vpn.data.MapTileStore
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.ui.components.ProtocolBadge
import com.mydrop.vpn.ui.components.ScreenHeader
import com.mydrop.vpn.ui.components.TonalIconButton
import com.mydrop.vpn.ui.theme.LocalSemanticColors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

@Composable
fun ZonesMapScreen(
    zonesState: GeoZoneStoreState,
    clusters: List<GeoZoneCluster>,
    proximity: ZoneProximity,
    locationSample: GeoSample?,
    lastValidCoord: LatLon?,
    hasLocationPermission: Boolean,
    hasMobileNodes: Boolean,
    spoofDetected: Boolean,
    mapTiles: MapTileStore,
    nodes: List<ProxyNode>,
    onSetMapActive: (Boolean) -> Unit,
    onPermissionUpdated: () -> Unit,
    onOpenMobileNodes: () -> Unit,
    onAddManualZone: (latitude: Double, longitude: Double) -> Unit,
    onTogglePinCluster: (String) -> Unit,
    onRemoveCluster: (String) -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(Unit) {
        onSetMapActive(true)
        onDispose { onSetMapActive(false) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        onPermissionUpdated()
    }

    val tileRevision by mapTiles.revision.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val semantic = LocalSemanticColors.current

    val initialCenter = remember {
        lastValidCoord ?: clusters.firstOrNull()?.center ?: DEFAULT_CITY_CENTER
    }
    var centerLat by rememberSaveable { mutableStateOf(initialCenter.latitude) }
    var centerLon by rememberSaveable { mutableStateOf(initialCenter.longitude) }
    var zoom by rememberSaveable { mutableFloatStateOf(DEFAULT_ZOOM) }
    var hasCenteredOnFix by rememberSaveable { mutableStateOf(lastValidCoord != null) }
    var selectedClusterId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(lastValidCoord) {
        if (!hasCenteredOnFix && lastValidCoord != null) {
            centerLat = lastValidCoord.latitude
            centerLon = lastValidCoord.longitude
            hasCenteredOnFix = true
        }
    }

    val selectedCluster = remember(clusters, selectedClusterId) {
        clusters.firstOrNull { it.id == selectedClusterId }
    }
    LaunchedEffect(clusters) {
        if (selectedClusterId != null && selectedCluster == null) {
            selectedClusterId = null
        }
    }

    val subtitle = if (clusters.isEmpty()) {
        stringResource(R.string.zones_map_subtitle_empty)
    } else {
        stringResource(R.string.zones_map_subtitle_count, clusters.size)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        ScreenHeader(
            title = stringResource(R.string.zones_map_title),
            titleStyle = MaterialTheme.typography.headlineLarge,
            subtitle = subtitle,
            modifier = Modifier.padding(bottom = 10.dp),
            actions = {
                if (clusters.isNotEmpty() || zonesState.breadcrumbs.isNotEmpty()) {
                    TonalIconButton(
                        icon = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.zones_action_clear_all),
                        onClick = {
                            selectedClusterId = null
                            onClearAll()
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                TonalIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = onBack,
                )
            },
        )

        AnimatedVisibility(visible = !hasLocationPermission) {
            PermissionBanner(
                onGrant = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        AnimatedVisibility(visible = !hasMobileNodes) {
            MobileNodesMissingBanner(
                onOpenMobileNodes = onOpenMobileNodes,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        if (clusters.isNotEmpty()) {
            ClusterChipStrip(
                clusters = clusters,
                selectedClusterId = selectedClusterId,
                onSelect = { cluster ->
                    if (selectedClusterId == cluster.id) {
                        selectedClusterId = null
                    } else {
                        selectedClusterId = cluster.id
                        centerLat = cluster.center.latitude
                        centerLon = cluster.center.longitude
                        if (zoom < 13.5f) zoom = 14.2f
                    }
                },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(32.dp))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(32.dp),
                ),
        ) {
            InteractiveZonesCanvas(
                centerLat = centerLat,
                centerLon = centerLon,
                zoom = zoom,
                tileRevision = tileRevision,
                mapTiles = mapTiles,
                cells = zonesState.cells.values,
                clusters = clusters,
                wifiAnchors = zonesState.wifiAnchors.values,
                selectedClusterId = selectedClusterId,
                breadcrumbs = zonesState.breadcrumbs,
                locationSample = locationSample,
                lastValidCoord = lastValidCoord,
                onPanZoom = { nextLat, nextLon, nextZoom ->
                    centerLat = nextLat
                    centerLon = nextLon
                    zoom = nextZoom
                },
                onTapCoord = { tapped ->
                    val tappedHex = HexCoord.fromLatLon(tapped.latitude, tapped.longitude)
                    val hit = clusters.firstOrNull { tappedHex in it.hexSet }
                        ?: clusters.firstOrNull { tappedHex in it.approachRing }
                    selectedClusterId = hit?.id
                },
                onLongPressCoord = { pressed ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onAddManualZone(pressed.latitude, pressed.longitude)
                },
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProximityPill(proximity = proximity)
                AnimatedVisibility(visible = spoofDetected) {
                    SpoofWarningPill()
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                MapControlButton(
                    icon = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.zones_action_zoom_in),
                    onClick = { zoom = (zoom + 0.8f).coerceIn(MIN_ZOOM, MAX_ZOOM) },
                )
                MapControlButton(
                    icon = Icons.Rounded.Remove,
                    contentDescription = stringResource(R.string.zones_action_zoom_out),
                    onClick = { zoom = (zoom - 0.8f).coerceIn(MIN_ZOOM, MAX_ZOOM) },
                )
                if (lastValidCoord != null) {
                    MapControlButton(
                        icon = Icons.Rounded.MyLocation,
                        contentDescription = stringResource(R.string.zones_action_my_location),
                        onClick = {
                            centerLat = lastValidCoord.latitude
                            centerLon = lastValidCoord.longitude
                            if (zoom < 14f) zoom = 14.5f
                        },
                    )
                    MapControlButton(
                        icon = Icons.Rounded.AddLocationAlt,
                        contentDescription = stringResource(R.string.zones_action_mark_here),
                        highlighted = true,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAddManualZone(lastValidCoord.latitude, lastValidCoord.longitude)
                        },
                    )
                }
            }

            LegendStrip(
                whitelistColor = semantic.connecting,
                approachColor = semantic.connecting.copy(alpha = 0.55f),
                deadColor = semantic.latencySlow,
                healthyColor = semantic.connected,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
            )
        }

        AnimatedVisibility(
            visible = selectedCluster != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            selectedCluster?.let { cluster ->
                val index = clusters.indexOfFirst { it.id == cluster.id }.coerceAtLeast(0)
                ClusterInspectorCard(
                    cluster = cluster,
                    index = index,
                    nodes = nodes,
                    onTogglePin = { onTogglePinCluster(cluster.id) },
                    onRemove = {
                        onRemoveCluster(cluster.id)
                        selectedClusterId = null
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        if (selectedCluster == null) {
            val hintText = if (lastValidCoord == null) {
                stringResource(R.string.zones_hint_no_fix)
            } else {
                stringResource(R.string.zones_map_subtitle_empty)
            }
            if (clusters.isEmpty() || lastValidCoord == null) {
                Text(
                    text = hintText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            } else {
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun InteractiveZonesCanvas(
    centerLat: Double,
    centerLon: Double,
    zoom: Float,
    tileRevision: Int,
    mapTiles: MapTileStore,
    cells: Collection<GeoCellRecord>,
    clusters: List<GeoZoneCluster>,
    wifiAnchors: Collection<WifiAnchor>,
    selectedClusterId: String?,
    breadcrumbs: List<com.mydrop.vpn.core.model.GeoBreadcrumb>,
    locationSample: GeoSample?,
    lastValidCoord: LatLon?,
    onPanZoom: (Double, Double, Float) -> Unit,
    onTapCoord: (LatLon) -> Unit,
    onLongPressCoord: (LatLon) -> Unit,
) {
    val density = LocalDensity.current
    val tileDisplayPx = remember(density) {
        with(density) { 192.dp.toPx() }
    }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val semantic = LocalSemanticColors.current
    val homeWifiColor = MaterialTheme.colorScheme.primary
    val bgColor = MaterialTheme.colorScheme.surfaceContainerLowest
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)

    val tilePaint = remember(dark) {
        Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
            if (dark) {
                // Dark slate-glacier inversion so OSM street tiles sit comfortably on #090B0F.
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix(
                        floatArrayOf(
                            -0.84f, 0f, 0f, 0f, 225f,
                            0f, -0.82f, 0f, 0f, 225f,
                            0f, 0f, -0.78f, 0f, 228f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            } else {
                alpha = 235
            }
        }
    }

    val markerRotation by rememberInfiniteTransition(label = "map-marker").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 45_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "marker-angle",
    )
    val markerPath = remember { MaterialShapes.Cookie4Sided.toPath() }
    val markerBounds = remember(markerPath) { RectF().also { markerPath.computeBounds(it, true) } }
    val markerScratch = remember { AndroidPath() }
    val markerMatrix = remember { Matrix() }
    val markerPaint = remember { Paint().apply { isAntiAlias = true } }
    val markerShadow = remember { Paint().apply { isAntiAlias = true; color = 0x73000000 } }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val latestCenterLat by rememberUpdatedState(centerLat)
    val latestCenterLon by rememberUpdatedState(centerLon)
    val latestZoom by rememberUpdatedState(zoom)
    val latestSize by rememberUpdatedState(canvasSize)
    val latestOnPanZoom by rememberUpdatedState(onPanZoom)
    val latestOnTap by rememberUpdatedState(onTapCoord)
    val latestOnLongPress by rememberUpdatedState(onLongPressCoord)

    val healthyCells = remember(cells) { cells.filter { !it.isAnomaly() } }
    val homeWifiHexes = remember(wifiAnchors) {
        wifiAnchors
            .asSequence()
            .filter { it.isFrequentHome }
            .mapTo(LinkedHashSet()) { HexCoord.fromLatLon(it.latitude, it.longitude) }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .onSizeChanged { canvasSize = it }
            .pointerInput(tileDisplayPx) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val curSize = latestSize
                    if (curSize.width <= 0 || curSize.height <= 0) return@detectTransformGestures
                    val oldZoom = latestZoom
                    val zoomDelta = if (gestureZoom > 0f && gestureZoom != 1f) {
                        (ln(gestureZoom.toDouble()) / ln(2.0)).toFloat()
                    } else {
                        0f
                    }
                    val newZoom = (oldZoom + zoomDelta).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    val oldProj = MercatorProjection(
                        centerLat = latestCenterLat,
                        centerLon = latestCenterLon,
                        zoom = oldZoom,
                        widthPx = curSize.width.toFloat(),
                        heightPx = curSize.height.toFloat(),
                        tileDisplayPx = tileDisplayPx,
                    )
                    val focalLatLon = oldProj.screenToLatLon(centroid.x, centroid.y)
                    val newWorldSize = tileDisplayPx.toDouble() * 2.0.pow(newZoom.toDouble())
                    val focalWx = MercatorProjection.lonToWorldX(focalLatLon.longitude, newWorldSize)
                    val focalWy = MercatorProjection.latToWorldY(focalLatLon.latitude, newWorldSize)
                    val nextCenterWx = focalWx - (centroid.x - curSize.width / 2.0) - pan.x
                    val nextCenterWy = focalWy - (centroid.y - curSize.height / 2.0) - pan.y
                    val nextLat = MercatorProjection.worldYToLat(nextCenterWy, newWorldSize)
                        .coerceIn(-82.0, 82.0)
                    val nextLon = MercatorProjection.normalizeLon(
                        MercatorProjection.worldXToLon(nextCenterWx, newWorldSize),
                    )
                    latestOnPanZoom(nextLat, nextLon, newZoom)
                }
            }
            .pointerInput(tileDisplayPx) {
                detectTapGestures(
                    onTap = { offset ->
                        val curSize = latestSize
                        if (curSize.width <= 0 || curSize.height <= 0) return@detectTapGestures
                        val proj = MercatorProjection(
                            centerLat = latestCenterLat,
                            centerLon = latestCenterLon,
                            zoom = latestZoom,
                            widthPx = curSize.width.toFloat(),
                            heightPx = curSize.height.toFloat(),
                            tileDisplayPx = tileDisplayPx,
                        )
                        latestOnTap(proj.screenToLatLon(offset.x, offset.y))
                    },
                    onLongPress = { offset ->
                        val curSize = latestSize
                        if (curSize.width <= 0 || curSize.height <= 0) return@detectTapGestures
                        val proj = MercatorProjection(
                            centerLat = latestCenterLat,
                            centerLon = latestCenterLon,
                            zoom = latestZoom,
                            widthPx = curSize.width.toFloat(),
                            heightPx = curSize.height.toFloat(),
                            tileDisplayPx = tileDisplayPx,
                        )
                        latestOnLongPress(proj.screenToLatLon(offset.x, offset.y))
                    },
                )
            },
    ) {
        // Trigger redraw whenever tile cache finishes loading new OSM tiles.
        @Suppress("UNUSED_EXPRESSION")
        tileRevision

        drawRect(bgColor)
        val proj = MercatorProjection(
            centerLat = centerLat,
            centerLon = centerLon,
            zoom = zoom,
            widthPx = size.width,
            heightPx = size.height,
            tileDisplayPx = tileDisplayPx,
        )

        drawSlippyTiles(proj, mapTiles, tilePaint, gridColor)

        // Healthy cells layer (subtle glacier tint).
        for (cell in healthyCells) {
            drawHex(
                proj = proj,
                hex = cell.hex,
                fill = semantic.connected.copy(alpha = 0.10f),
                stroke = semantic.connected.copy(alpha = 0.35f),
                strokeWidthPx = 1.dp.toPx(),
            )
        }

        // Frequent "Home" Wi-Fi safe hexes.
        for (homeHex in homeWifiHexes) {
            drawHex(
                proj = proj,
                hex = homeHex,
                fill = homeWifiColor.copy(alpha = 0.16f),
                stroke = homeWifiColor.copy(alpha = 0.75f),
                strokeWidthPx = 1.6.dp.toPx(),
            )
        }

        // Approach ring buffer around whitelist/suspect clusters.
        val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
        for (cluster in clusters) {
            if (cluster.kind != ZoneKind.Whitelist && cluster.kind != ZoneKind.Suspect) continue
            for (ringHex in cluster.approachRing) {
                drawHex(
                    proj = proj,
                    hex = ringHex,
                    fill = semantic.connecting.copy(alpha = 0.08f),
                    stroke = semantic.connecting.copy(alpha = 0.45f),
                    strokeWidthPx = 1.2.dp.toPx(),
                    pathEffect = dashedEffect,
                )
            }
        }

        // Cluster anomaly hexes.
        for (cluster in clusters) {
            val selected = cluster.id == selectedClusterId
            val baseColor = when (cluster.kind) {
                ZoneKind.Whitelist -> semantic.connecting
                ZoneKind.Suspect -> semantic.connecting.copy(alpha = 0.72f)
                ZoneKind.DeadSpot -> semantic.latencySlow
                ZoneKind.Healthy -> semantic.connected
            }
            val fillAlpha = if (selected) 0.38f else 0.25f
            val strokeWidth = if (selected) 2.6.dp.toPx() else 1.5.dp.toPx()
            for (cell in cluster.cells) {
                drawHex(
                    proj = proj,
                    hex = cell.hex,
                    fill = baseColor.copy(alpha = fillAlpha),
                    stroke = baseColor.copy(alpha = if (selected) 1f else 0.85f),
                    strokeWidthPx = strokeWidth,
                )
            }
        }

        // Breadcrumb track.
        if (breadcrumbs.size >= 2) {
            for (i in 1 until breadcrumbs.size) {
                val a = breadcrumbs[i - 1]
                val b = breadcrumbs[i]
                if (GeoZoneEngine.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude) > 3_000.0) {
                    continue
                }
                val start = proj.latLonToScreen(LatLon(a.latitude, a.longitude))
                val end = proj.latLonToScreen(LatLon(b.latitude, b.longitude))
                val color = when {
                    b.failed -> semantic.latencySlow
                    b.onMobileList -> semantic.connecting
                    else -> semantic.connected
                }
                drawLine(
                    color = color.copy(alpha = 0.85f),
                    start = start,
                    end = end,
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        // User location + velocity look-ahead vector + Cookie4Sided marker.
        val userCoord = lastValidCoord ?: locationSample?.let { LatLon(it.latitude, it.longitude) }
        if (userCoord != null) {
            val pos = proj.latLonToScreen(userCoord)
            val radiusPx = (locationSample?.accuracyMeters ?: 35f).coerceIn(12f, 250f) *
                proj.pixelsPerMeterAt(userCoord.latitude)
            if (radiusPx > 4f && radiusPx < size.minDimension * 0.6f) {
                drawCircle(
                    color = semantic.connected.copy(alpha = 0.14f),
                    radius = radiusPx,
                    center = pos,
                )
                drawCircle(
                    color = semantic.connected.copy(alpha = 0.45f),
                    radius = radiusPx,
                    center = pos,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            if (locationSample != null && locationSample.speedMetersPerSecond > 0.8f) {
                val ahead = GeoZoneEngine.predictLookAhead(null, locationSample)
                val aheadPos = proj.latLonToScreen(ahead)
                drawLine(
                    color = semantic.marker.copy(alpha = 0.75f),
                    start = pos,
                    end = aheadPos,
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = dashedEffect,
                    cap = StrokeCap.Round,
                )
            }

            markerPaint.color = semantic.marker.toArgb()
            drawUserCookie(
                center = pos,
                degrees = markerRotation,
                sidePx = 22.dp.toPx(),
                shape = markerPath,
                bounds = markerBounds,
                scratch = markerScratch,
                matrix = markerMatrix,
                paint = markerPaint,
                shadow = markerShadow,
            )
        }
    }
}

private fun DrawScope.drawSlippyTiles(
    proj: MercatorProjection,
    mapTiles: MapTileStore,
    tilePaint: Paint,
    gridColor: Color,
) {
    val tileZoom = proj.zoom.roundToInt().coerceIn(10, 16)
    val n = 1 shl tileZoom
    val minWx = proj.centerWorldX - size.width / 2.0
    val maxWx = proj.centerWorldX + size.width / 2.0
    val minWy = proj.centerWorldY - size.height / 2.0
    val maxWy = proj.centerWorldY + size.height / 2.0

    val minTx = floor((minWx / proj.worldSizePx) * n).toInt()
    val maxTx = floor((maxWx / proj.worldSizePx) * n).toInt()
    val minTy = floor((minWy / proj.worldSizePx) * n).toInt().coerceIn(0, n - 1)
    val maxTy = floor((maxWy / proj.worldSizePx) * n).toInt().coerceIn(0, n - 1)

    val dest = RectF()
    val stepX = (proj.worldSizePx / n).toFloat()
    val stepY = (proj.worldSizePx / n).toFloat()

    // Fallback subtle grid so uncached tiles still read cleanly.
    val gridStep = 48.dp.toPx()
    var gx = ((-proj.centerWorldX % gridStep) + gridStep).toFloat() % gridStep
    while (gx < size.width) {
        drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
        gx += gridStep
    }
    var gy = ((-proj.centerWorldY % gridStep) + gridStep).toFloat() % gridStep
    while (gy < size.height) {
        drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        gy += gridStep
    }

    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        for (ty in minTy..maxTy) {
            val top = (size.height / 2.0 + (ty.toDouble() / n) * proj.worldSizePx - proj.centerWorldY).toFloat()
            for (tx in minTx..maxTx) {
                val bitmap = mapTiles.getTile(tileZoom, tx, ty) ?: continue
                val left = (size.width / 2.0 + (tx.toDouble() / n) * proj.worldSizePx - proj.centerWorldX).toFloat()
                dest.set(left, top, left + stepX + 0.75f, top + stepY + 0.75f)
                native.drawBitmap(bitmap, null, dest, tilePaint)
            }
        }
    }
}

private fun DrawScope.drawHex(
    proj: MercatorProjection,
    hex: HexCoord,
    fill: Color,
    stroke: Color,
    strokeWidthPx: Float,
    pathEffect: PathEffect? = null,
) {
    val vertices = hex.vertices()
    if (vertices.isEmpty()) return
    val first = proj.latLonToScreen(vertices[0])
    // Quick visibility cull to skip off-screen hexes.
    var anyInside = false
    val pts = ArrayList<Offset>(vertices.size)
    pts += first
    val margin = 160f
    if (first.x in -margin..(size.width + margin) && first.y in -margin..(size.height + margin)) {
        anyInside = true
    }
    for (i in 1 until vertices.size) {
        val pt = proj.latLonToScreen(vertices[i])
        pts += pt
        if (!anyInside && pt.x in -margin..(size.width + margin) && pt.y in -margin..(size.height + margin)) {
            anyInside = true
        }
    }
    if (!anyInside) return

    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        close()
    }
    drawPath(path = path, color = fill)
    drawPath(
        path = path,
        color = stroke,
        style = Stroke(width = strokeWidthPx, pathEffect = pathEffect),
    )
}

private fun DrawScope.drawUserCookie(
    center: Offset,
    degrees: Float,
    sidePx: Float,
    shape: AndroidPath,
    bounds: RectF,
    scratch: AndroidPath,
    matrix: Matrix,
    paint: Paint,
    shadow: Paint,
) {
    if (bounds.width() <= 0f || bounds.height() <= 0f) return
    matrix.setScale(sidePx / bounds.width(), sidePx / bounds.height())
    matrix.postTranslate(
        center.x - bounds.left * sidePx / bounds.width() - sidePx / 2f,
        center.y - bounds.top * sidePx / bounds.height() - sidePx / 2f,
    )
    matrix.postRotate(degrees, center.x, center.y)
    shape.transform(matrix, scratch)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val drop = sidePx * 0.11f
        native.save()
        native.translate(drop, drop)
        native.drawPath(scratch, shadow)
        native.restore()
        native.drawPath(scratch, paint)
    }
}

private class MercatorProjection(
    centerLat: Double,
    centerLon: Double,
    val zoom: Float,
    val widthPx: Float,
    val heightPx: Float,
    tileDisplayPx: Float,
) {
    val worldSizePx: Double = tileDisplayPx.toDouble() * 2.0.pow(zoom.toDouble())
    val centerWorldX: Double = lonToWorldX(centerLon, worldSizePx)
    val centerWorldY: Double = latToWorldY(centerLat, worldSizePx)

    fun latLonToScreen(point: LatLon): Offset {
        val wx = lonToWorldX(point.longitude, worldSizePx)
        val wy = latToWorldY(point.latitude, worldSizePx)
        return Offset(
            x = (widthPx / 2.0 + (wx - centerWorldX)).toFloat(),
            y = (heightPx / 2.0 + (wy - centerWorldY)).toFloat(),
        )
    }

    fun screenToLatLon(x: Float, y: Float): LatLon {
        val wx = centerWorldX + (x - widthPx / 2.0)
        val wy = centerWorldY + (y - heightPx / 2.0)
        return LatLon(
            latitude = worldYToLat(wy, worldSizePx),
            longitude = normalizeLon(worldXToLon(wx, worldSizePx)),
        )
    }

    fun pixelsPerMeterAt(latitude: Double): Float {
        val cosLat = cos(Math.toRadians(latitude.coerceIn(-85.0, 85.0))).coerceAtLeast(0.1)
        val circumferenceAtLat = 40_075_016.686 * cosLat
        return (worldSizePx / circumferenceAtLat).toFloat()
    }

    companion object {
        fun lonToWorldX(lon: Double, worldSizePx: Double): Double =
            ((lon + 180.0) / 360.0) * worldSizePx

        fun latToWorldY(lat: Double, worldSizePx: Double): Double {
            val rad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
            val mercN = ln(tan(PI / 4.0 + rad / 2.0))
            return (1.0 - mercN / PI) * 0.5 * worldSizePx
        }

        fun worldXToLon(wx: Double, worldSizePx: Double): Double =
            (wx / worldSizePx) * 360.0 - 180.0

        fun worldYToLat(wy: Double, worldSizePx: Double): Double {
            val n = PI * (1.0 - 2.0 * (wy / worldSizePx))
            return Math.toDegrees(atan(sinh(n)))
        }

        fun normalizeLon(lon: Double): Double =
            ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    }
}

@Composable
private fun PermissionBanner(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.GpsOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.zones_permission_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.zones_permission_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                )
            }
            Button(onClick = onGrant) {
                Text(stringResource(R.string.zones_permission_grant))
            }
        }
    }
}

@Composable
private fun MobileNodesMissingBanner(
    onOpenMobileNodes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_geozones_mobile_missing_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_geozones_mobile_missing_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.88f),
                )
            }
            Button(
                onClick = onOpenMobileNodes,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.zones_mobile_missing_action))
            }
        }
    }
}

@Composable
private fun ClusterChipStrip(
    clusters: List<GeoZoneCluster>,
    selectedClusterId: String?,
    onSelect: (GeoZoneCluster) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        clusters.forEachIndexed { index, cluster ->
            val label = cluster.customName
                ?: stringResource(R.string.zones_default_name, "#${index + 1}")
            FilterChip(
                selected = cluster.id == selectedClusterId,
                onClick = { onSelect(cluster) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(kindColor(cluster.kind)),
                    )
                },
                label = {
                    Text(
                        text = "$label · ~${cluster.approximateRadiusMeters}м",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun ProximityPill(proximity: ZoneProximity) {
    val semantic = LocalSemanticColors.current
    val (text, tint, icon) = when (proximity) {
        ZoneProximity.Outside -> Triple(
            stringResource(R.string.zones_status_outside),
            semantic.connected,
            Icons.Rounded.Shield,
        )

        is ZoneProximity.Approaching -> Triple(
            stringResource(R.string.zones_status_approaching, proximity.distanceMeters),
            semantic.connecting,
            Icons.Rounded.WarningAmber,
        )

        is ZoneProximity.Inside -> if (proximity.matchedByCellTower) {
            Triple(
                stringResource(R.string.zones_status_tower),
                semantic.connecting,
                Icons.Rounded.CellTower,
            )
        } else {
            Triple(
                stringResource(R.string.zones_status_inside),
                semantic.connecting,
                Icons.Rounded.WarningAmber,
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SpoofWarningPill() {
    val semantic = LocalSemanticColors.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.GpsOff,
                contentDescription = null,
                tint = semantic.latencySlow,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = stringResource(R.string.zones_status_spoof),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun LegendStrip(
    whitelistColor: Color,
    approachColor: Color,
    deadColor: Color,
    healthyColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendDot(whitelistColor, stringResource(R.string.zones_legend_whitelist))
            LegendDot(approachColor, stringResource(R.string.zones_legend_approach))
            LegendDot(deadColor, stringResource(R.string.zones_legend_dead))
            LegendDot(healthyColor, stringResource(R.string.zones_legend_healthy))
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MapControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(42.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
            },
            contentColor = if (highlighted) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ClusterInspectorCard(
    cluster: GeoZoneCluster,
    index: Int,
    nodes: List<ProxyNode>,
    onTogglePin: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nodeMap = remember(nodes) { nodes.associateBy { it.id } }
    val kindLabel = when (cluster.kind) {
        ZoneKind.Whitelist -> stringResource(R.string.zones_kind_whitelist)
        ZoneKind.Suspect -> stringResource(R.string.zones_kind_suspect)
        ZoneKind.DeadSpot -> stringResource(R.string.zones_kind_dead)
        ZoneKind.Healthy -> stringResource(R.string.zones_kind_healthy)
    }
    val badgeColor = kindColor(cluster.kind)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(badgeColor),
                )
                Text(
                    text = cluster.customName ?: stringResource(R.string.zones_default_name, "#${index + 1}"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = kindLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                if (cluster.pinned) {
                    Text(
                        text = stringResource(R.string.zones_badge_pinned),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.zones_metrics, cluster.cells.size, cluster.approximateRadiusMeters) +
                    " · " +
                    stringResource(
                        R.string.zones_stats_row,
                        cluster.ordinaryFailures,
                        cluster.mobileSuccesses,
                        cluster.cellTowerIds.size,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.zones_working_servers),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            if (cluster.workingNodeIds.isEmpty()) {
                Text(
                    text = stringResource(R.string.zones_working_servers_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    cluster.workingNodeIds.take(3).forEach { nodeId ->
                        val node = nodeMap[nodeId]
                        val ms = cluster.bestLatencies[nodeId]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = node?.name ?: nodeId,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (node != null) ProtocolBadge(node.protocol)
                            if (ms != null) {
                                Text(
                                    text = stringResource(R.string.servers_latency_value, ms),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = LocalSemanticColors.current.latencyFast,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onRemove) {
                    Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.zones_action_delete))
                }
                Spacer(Modifier.width(6.dp))
                FilledTonalButton(onClick = onTogglePin) {
                    Icon(Icons.Rounded.PushPin, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(
                            if (cluster.pinned) R.string.zones_action_unpin else R.string.zones_action_pin,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun kindColor(kind: ZoneKind): Color {
    val semantic = LocalSemanticColors.current
    return when (kind) {
        ZoneKind.Whitelist -> semantic.connecting
        ZoneKind.Suspect -> semantic.connecting.copy(alpha = 0.72f)
        ZoneKind.DeadSpot -> semantic.latencySlow
        ZoneKind.Healthy -> semantic.connected
    }
}

private val DEFAULT_CITY_CENTER = LatLon(55.7512, 37.6184)
private const val DEFAULT_ZOOM = 13.2f
private const val MIN_ZOOM = 10f
private const val MAX_ZOOM = 16.5f
