package com.mydrop.vpn.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.mydrop.vpn.core.model.GeoSample
import com.mydrop.vpn.core.model.GeoZoneEngine
import com.mydrop.vpn.core.model.HexCoord
import com.mydrop.vpn.core.model.LatLon
import com.mydrop.vpn.core.model.NetworkTransport
import com.mydrop.vpn.core.model.ObservedCellTower
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.core.model.WifiAnchor
import com.mydrop.vpn.core.model.ZoneProximity
import com.mydrop.vpn.shared.R
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Adaptive location & serving cell-tower monitor for geo-zones.
 *
 * Keeps radio wakeups at zero on Wi-Fi when the map is closed, listens passively/low-power on
 * cellular while VPN is active, boosts to GPS when a drop is suspected or the map is visible,
 * cross-checks satellite fixes against cellular/Wi-Fi network location and anchored cell towers,
 * and falls back to multi-tower signal-weighted triangulation under GPS spoofing/jamming.
 */
class GeoLocationMonitor(
    private val context: Context,
    private val settings: SettingsRepository,
    private val tunnel: TunnelController,
    private val geoZones: GeoZoneRepository,
    private val logs: LogRepository,
    private val scope: CoroutineScope,
) {
    private enum class TrackingMode { Off, Passive, Active }

    private val locationManager: LocationManager? =
        context.getSystemService(LocationManager::class.java)
    private val telephonyManager: TelephonyManager? =
        context.getSystemService(TelephonyManager::class.java)
    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(WifiManager::class.java)

    private val _hasPermission = MutableStateFlow(checkAnyLocationPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    private val _currentSample = MutableStateFlow<GeoSample?>(null)
    val currentSample: StateFlow<GeoSample?> = _currentSample.asStateFlow()

    private val _lastValidLatLon = MutableStateFlow<LatLon?>(null)
    val lastValidLatLon: StateFlow<LatLon?> = _lastValidLatLon.asStateFlow()

    private val _spoofDetected = MutableStateFlow(false)
    val spoofDetected: StateFlow<Boolean> = _spoofDetected.asStateFlow()

    private val _proximity = MutableStateFlow<ZoneProximity>(ZoneProximity.Outside)
    val proximity: StateFlow<ZoneProximity> = _proximity.asStateFlow()

    private val mapActive = MutableStateFlow(false)
    private val burstActive = MutableStateFlow(false)

    private var burstJob: Job? = null
    private var towerPollJob: Job? = null
    private var lastValidSample: GeoSample? = null
    private var previousSample: GeoSample? = null
    private var lastNetworkCoord: LatLon? = null
    private var lastNetworkAtElapsedMillis: Long = 0L
    private var lastSpoofLoggedMillis = 0L
    private var registeredMode = TrackingMode.Off
    private var lastReportedProximity: ZoneProximity = ZoneProximity.Outside
    private var currentWifiSessionKey: String? = null
    private var currentWifiSessionRecorded: Boolean = false
    private var lastActiveWifiAnchor: WifiAnchor? = null
    private var lastWifiSeenAtElapsedMillis: Long = 0L
    private var wifiSessionStartedElapsedMillis: Long = 0L

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    fun start() {
        scope.launch {
            combine(
                settings.settings.map { it.geoZonesEnabled }.distinctUntilChanged(),
                tunnel.state,
                tunnel.transport,
                mapActive,
                combine(burstActive, _hasPermission) { burst, perm -> burst to perm },
            ) { enabled, vpnState, transport, mapOpen, (burst, perm) ->
                if (!enabled || !perm) {
                    TrackingMode.Off
                } else if (mapOpen || (burst && vpnState is VpnState.Connected)) {
                    TrackingMode.Active
                } else if (vpnState is VpnState.Connected && transport == NetworkTransport.Cellular) {
                    TrackingMode.Passive
                } else {
                    TrackingMode.Off
                }
            }
                .distinctUntilChanged()
                .collect { mode -> applyMode(mode) }
        }

        scope.launch {
            tunnel.transport.collect { transport -> onTransportChanged(transport) }
        }

        scope.launch {
            combine(
                _currentSample,
                _lastValidLatLon,
                geoZones.clusters,
                settings.settings.map { it.geoZonesEnabled }.distinctUntilChanged(),
            ) { sample, lastValid, clusters, enabled ->
                if (!enabled) {
                    ZoneProximity.Outside
                } else {
                    GeoZoneEngine.evaluateProximity(
                        fix = sample,
                        lastValidCoord = lastValid,
                        clusters = clusters,
                        previousFix = previousSample,
                    )
                }
            }.collect { next ->
                val prev = lastReportedProximity
                _proximity.value = next
                if (next != prev) {
                    lastReportedProximity = next
                    logs.trace(TAG, "proximity: ${describeProximity(prev)} -> ${describeProximity(next)}")
                }
            }
        }
    }

    fun refreshPermission() {
        val prev = _hasPermission.value
        val now = checkAnyLocationPermission()
        _hasPermission.value = now
        if (prev != now) {
            logs.trace(TAG, "permission changed: $prev -> $now (fine=${checkFineLocationPermission()})")
        }
        if (now && settings.value.geoZonesEnabled && _lastValidLatLon.value == null) {
            seedLastKnownLocation()
        }
    }

    fun setMapScreenActive(active: Boolean) {
        if (active) refreshPermission()
        if (mapActive.value != active) {
            logs.trace(TAG, "mapScreenActive=$active")
        }
        mapActive.value = active
    }

    fun requestHighAccuracyBurst(durationMillis: Long = DEFAULT_BURST_MILLIS) {
        if (!_hasPermission.value || !settings.value.geoZonesEnabled) return
        logs.trace(TAG, "high-accuracy burst requested (${durationMillis}ms)")
        burstJob?.cancel()
        burstActive.value = true
        burstJob = scope.launch {
            delay(durationMillis)
            burstActive.value = false
        }
    }

    private fun applyMode(mode: TrackingMode) {
        if (mode == registeredMode) return
        scope.launch(Dispatchers.Main.immediate) {
            val prev = registeredMode
            unregisterLocationUpdates()
            registeredMode = mode
            logs.trace(
                TAG,
                "mode=$prev->$mode perm=${_hasPermission.value} fine=${checkFineLocationPermission()} " +
                    "enabled=${settings.value.geoZonesEnabled} vpn=${tunnel.state.value::class.simpleName} " +
                    "transport=${tunnel.transport.value} map=${mapActive.value} burst=${burstActive.value}",
            )
            when (mode) {
                TrackingMode.Off -> {
                    towerPollJob?.cancel()
                    towerPollJob = null
                }

                TrackingMode.Passive -> {
                    seedLastKnownLocation()
                    registerProvider(LocationManager.NETWORK_PROVIDER, PASSIVE_INTERVAL_MS, PASSIVE_DISTANCE_M)
                    registerProvider(LocationManager.PASSIVE_PROVIDER, PASSIVE_FAST_INTERVAL_MS, PASSIVE_FAST_DISTANCE_M)
                    startTowerPolling()
                }

                TrackingMode.Active -> {
                    seedLastKnownLocation()
                    registerProvider(LocationManager.GPS_PROVIDER, ACTIVE_INTERVAL_MS, ACTIVE_DISTANCE_M)
                    registerProvider(LocationManager.NETWORK_PROVIDER, ACTIVE_NETWORK_INTERVAL_MS, ACTIVE_NETWORK_DISTANCE_M)
                    registerProvider(LocationManager.PASSIVE_PROVIDER, ACTIVE_INTERVAL_MS, ACTIVE_DISTANCE_M)
                    startTowerPolling()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerProvider(provider: String, minTimeMs: Long, minDistanceM: Float) {
        val manager = locationManager ?: return
        if (!checkAnyLocationPermission()) return
        if (provider == LocationManager.GPS_PROVIDER && !checkFineLocationPermission()) return
        runCatching {
            if (provider in manager.allProviders) {
                manager.requestLocationUpdates(
                    provider,
                    minTimeMs,
                    minDistanceM,
                    locationListener,
                    Looper.getMainLooper(),
                )
            }
        }
    }

    private fun unregisterLocationUpdates() {
        runCatching { locationManager?.removeUpdates(locationListener) }
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnownLocation() {
        val manager = locationManager ?: return
        if (!checkAnyLocationPermission()) return
        // Seed Network before GPS so cold-start cross-checking has cellular/Wi-Fi reference first.
        val orderedProviders = buildList {
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
            if (checkFineLocationPermission()) add(LocationManager.GPS_PROVIDER)
        }
        var seededAny = false
        for (provider in orderedProviders) {
            val fix = runCatching {
                if (provider in manager.allProviders) manager.getLastKnownLocation(provider) else null
            }.getOrNull() ?: continue
            handleLocation(fix)
            seededAny = true
        }
        if (!seededAny) {
            refreshTowerOnly()
        }
    }

    private fun startTowerPolling() {
        if (towerPollJob?.isActive == true) return
        towerPollJob = scope.launch {
            while (isActive) {
                delay(TOWER_POLL_INTERVAL_MS)
                refreshTowerOnly()
            }
        }
    }

    private fun refreshTowerOnly() {
        val observedTowers = readObservedCellTowers()
        val towerId = observedTowers.firstOrNull { it.registered }?.towerId ?: readCellTowerId() ?: return
        val current = _currentSample.value
        val nowElapsed = SystemClock.elapsedRealtime()

        if (_spoofDetected.value || _lastValidLatLon.value == null) {
            val triangulated = GeoZoneEngine.triangulateByTowers(observedTowers, geoZones.towerAnchors())
            if (triangulated != null) {
                val hex = HexCoord.fromLatLon(triangulated.latitude, triangulated.longitude)
                logs.trace(TAG, "tower-triangulated position -> hex=${hex.key} towers=${observedTowers.size}")
                _lastValidLatLon.value = triangulated
                val anchorSample = GeoSample(
                    latitude = triangulated.latitude,
                    longitude = triangulated.longitude,
                    accuracyMeters = 250f,
                    speedMetersPerSecond = 0f,
                    bearingDegrees = 0f,
                    elapsedRealtimeMillis = nowElapsed,
                    isMock = false,
                    cellTowerId = towerId,
                )
                lastValidSample = anchorSample
                _currentSample.value = anchorSample
                return
            }
        }

        val fallbackCoord = _lastValidLatLon.value
        if (current != null) {
            if (current.cellTowerId != towerId) {
                logs.trace(TAG, "tower changed: ${current.cellTowerId ?: "none"} -> $towerId")
                _currentSample.value = current.copy(cellTowerId = towerId)
            }
        } else if (fallbackCoord != null) {
            logs.trace(TAG, "tower seeded -> $towerId")
            _currentSample.value = GeoSample(
                latitude = fallbackCoord.latitude,
                longitude = fallbackCoord.longitude,
                accuracyMeters = 500f,
                speedMetersPerSecond = 0f,
                bearingDegrees = 0f,
                elapsedRealtimeMillis = nowElapsed,
                cellTowerId = towerId,
            )
        }
    }

    private fun handleLocation(location: Location) {
        val elapsedMillis = runCatching { location.elapsedRealtimeNanos / 1_000_000L }
            .getOrElse { SystemClock.elapsedRealtime() }
        val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
        val isSatellite = location.provider == LocationManager.GPS_PROVIDER
        val observedTowers = readObservedCellTowers()
        val towerId = observedTowers.firstOrNull { it.registered }?.towerId ?: readCellTowerId()
        val candidate = GeoSample(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else 100f,
            speedMetersPerSecond = if (location.hasSpeed()) location.speed else 0f,
            bearingDegrees = if (location.hasBearing()) location.bearing else 0f,
            elapsedRealtimeMillis = elapsedMillis,
            isMock = isMock,
            cellTowerId = towerId,
        )

        val anchors = geoZones.towerAnchors()
        val activeWifiAnchor = resolveActiveOrGraceWifiAnchor(elapsedMillis)
        val jumped = GeoZoneEngine.isSpoofedOrJumped(lastValidSample, candidate)
        val diverged = GeoZoneEngine.isSpoofedAgainstNetworkOrTower(
            candidate = candidate,
            isSatelliteProvider = isSatellite,
            recentNetworkCoord = lastNetworkCoord,
            networkAgeMillis = elapsedMillis - lastNetworkAtElapsedMillis,
            towerAnchors = anchors,
            activeWifiAnchor = activeWifiAnchor,
        )
        val spoofed = jumped || diverged

        if (spoofed) {
            _spoofDetected.value = true
            val wifiCoord = activeWifiAnchor?.let { LatLon(it.latitude, it.longitude) }
            val towerTriangulated = GeoZoneEngine.triangulateByTowers(observedTowers, anchors)
            val freshNet = lastNetworkCoord?.takeIf {
                (elapsedMillis - lastNetworkAtElapsedMillis) in 0L..GeoZoneEngine.NETWORK_FIX_MAX_AGE_MILLIS
            }
            val safeCoord = wifiCoord ?: towerTriangulated ?: freshNet ?: _lastValidLatLon.value
            val fakeHex = HexCoord.fromLatLon(candidate.latitude, candidate.longitude).key
            val safeHex = safeCoord?.let { HexCoord.fromLatLon(it.latitude, it.longitude).key } ?: "none"
            val reason = when {
                isMock -> "mock"
                jumped -> "jump"
                wifiCoord != null -> "wifi-anchor-divergence"
                else -> "tower/net-divergence"
            }
            logs.trace(
                TAG,
                "spoof rejected provider=${location.provider} reason=$reason " +
                    "fakeHex=$fakeHex safeHex=$safeHex acc=${candidate.accuracyMeters.toInt()}m " +
                    "tower=${towerId ?: "none"} wifi=${activeWifiAnchor?.networkKey ?: "none"}",
            )
            val now = SystemClock.elapsedRealtime()
            if (now - lastSpoofLoggedMillis >= SPOOF_LOG_COOLDOWN_MS) {
                lastSpoofLoggedMillis = now
                logs.warn(R.string.log_geozone_spoof_detected)
            }
            if (safeCoord != null) {
                _lastValidLatLon.value = safeCoord
                val safeSample = GeoSample(
                    latitude = safeCoord.latitude,
                    longitude = safeCoord.longitude,
                    accuracyMeters = 250f,
                    speedMetersPerSecond = 0f,
                    bearingDegrees = 0f,
                    elapsedRealtimeMillis = elapsedMillis,
                    isMock = false,
                    cellTowerId = towerId ?: _currentSample.value?.cellTowerId,
                )
                if (wifiCoord != null || towerTriangulated != null || freshNet != null) {
                    lastValidSample = safeSample
                }
                _currentSample.value = safeSample
            }
            return
        }

        _spoofDetected.value = false
        if (location.provider == LocationManager.NETWORK_PROVIDER) {
            lastNetworkCoord = LatLon(candidate.latitude, candidate.longitude)
            lastNetworkAtElapsedMillis = elapsedMillis
        }
        if (towerId != null && candidate.accuracyMeters <= MAX_ANCHOR_ACCURACY_METERS) {
            geoZones.recordTowerObservation(towerId, candidate.latitude, candidate.longitude)
        }
        if (tunnel.transport.value == NetworkTransport.Wifi &&
            candidate.accuracyMeters <= MAX_ANCHOR_ACCURACY_METERS &&
            (SystemClock.elapsedRealtime() - elapsedMillis) in 0L..WIFI_ANCHOR_FIX_MAX_AGE_MILLIS
        ) {
            recordWifiAnchorAt(candidate.latitude, candidate.longitude)
        }

        previousSample = lastValidSample
        lastValidSample = candidate
        _lastValidLatLon.value = LatLon(candidate.latitude, candidate.longitude)
        _currentSample.value = candidate
        val hex = HexCoord.fromLatLon(candidate.latitude, candidate.longitude)
        logs.trace(
            TAG,
            "fix provider=${location.provider} hex=${hex.key} " +
                "acc=${candidate.accuracyMeters.toInt()}m " +
                "speed=${String.format(Locale.US, "%.1f", candidate.speedMetersPerSecond)}m/s " +
                "tower=${towerId ?: "none"}",
        )
    }

    @SuppressLint("MissingPermission")
    fun readObservedCellTowers(): List<ObservedCellTower> {
        if (!checkFineLocationPermission()) return emptyList()
        val manager = telephonyManager ?: return emptyList()
        val infos = runCatching { manager.allCellInfo }.getOrNull() ?: return emptyList()
        val result = ArrayList<ObservedCellTower>(infos.size)
        for (info in infos) {
            val id = extractTowerId(info) ?: continue
            val rawDbm = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> info.cellSignalStrength.dbm
                info is CellInfoLte -> info.cellSignalStrength.dbm
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr ->
                    info.cellSignalStrength.dbm
                else -> CellInfo.UNAVAILABLE
            }
            val dbm = if (rawDbm == CellInfo.UNAVAILABLE || rawDbm > 0) FALLBACK_DBM else rawDbm
            result += ObservedCellTower(
                towerId = id,
                registered = info.isRegistered,
                dbm = dbm,
            )
        }
        return result
    }

    @SuppressLint("MissingPermission")
    fun readCellTowerId(): Long? {
        if (!checkFineLocationPermission()) return null
        val manager = telephonyManager ?: return null
        val infos = runCatching { manager.allCellInfo }.getOrNull() ?: return null
        for (info in infos) {
            if (!info.isRegistered) continue
            extractTowerId(info)?.let { return it }
        }
        return null
    }

    private fun extractTowerId(info: CellInfo): Long? = when (info) {
        is CellInfoLte -> {
            val id: CellIdentityLte = info.cellIdentity
            val tac = id.tac
            val ci = id.ci
            if (tac != CellInfo.UNAVAILABLE && ci != CellInfo.UNAVAILABLE && tac >= 0 && ci >= 0) {
                (tac.toLong() shl 40) or (ci.toLong() and 0xFF_FFFF_FFFFL)
            } else {
                null
            }
        }

        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr) {
            val id = info.cellIdentity as? CellIdentityNr
            if (id != null) {
                val tac = id.tac
                val nci = id.nci
                if (tac != CellInfo.UNAVAILABLE && nci != CellInfo.UNAVAILABLE_LONG && tac >= 0 && nci >= 0L) {
                    NR_TOWER_BIT or (tac.toLong() shl 40) xor (nci and 0xFF_FFFF_FFFFL)
                } else {
                    null
                }
            } else {
                null
            }
        } else {
            null
        }
    }

    private fun onTransportChanged(transport: NetworkTransport) {
        if (transport == NetworkTransport.Wifi) {
            if (!settings.value.geoZonesEnabled) return
            wifiSessionStartedElapsedMillis = SystemClock.elapsedRealtime()
            val identity = readCurrentWifiIdentity(fallbackCoord = null)
            if (identity != null) {
                val (key, ssid) = identity
                if (currentWifiSessionKey != key) {
                    currentWifiSessionKey = key
                    currentWifiSessionRecorded = false
                }
                val existing = geoZones.wifiAnchors()[key]
                if (existing != null) {
                    val updated = geoZones.recordWifiConnection(
                        networkKey = key,
                        ssidLabel = ssid,
                        latitude = existing.latitude,
                        longitude = existing.longitude,
                        incrementConnect = !currentWifiSessionRecorded,
                    )
                    currentWifiSessionRecorded = true
                    lastActiveWifiAnchor = updated
                    lastWifiSeenAtElapsedMillis = SystemClock.elapsedRealtime()
                    if (_lastValidLatLon.value == null) {
                        _lastValidLatLon.value = LatLon(updated.latitude, updated.longitude)
                    }
                }
            }
            if ((!currentWifiSessionRecorded || _lastValidLatLon.value == null) &&
                settings.value.geoZonesEnabled &&
                checkAnyLocationPermission()
            ) {
                seedLastKnownLocation()
            }
        } else {
            if (currentWifiSessionKey != null) {
                lastWifiSeenAtElapsedMillis = SystemClock.elapsedRealtime()
            }
            currentWifiSessionKey = null
            currentWifiSessionRecorded = false
        }
    }

    private fun resolveActiveOrGraceWifiAnchor(nowElapsedMillis: Long): WifiAnchor? {
        val anchor = lastActiveWifiAnchor ?: return null
        if (tunnel.transport.value == NetworkTransport.Wifi) return anchor
        val age = nowElapsedMillis - lastWifiSeenAtElapsedMillis
        return anchor.takeIf { age in 0L..GeoZoneEngine.HOME_WIFI_HANDOVER_GRACE_MILLIS }
    }

    private fun recordWifiAnchorAt(latitude: Double, longitude: Double) {
        if (tunnel.transport.value != NetworkTransport.Wifi) return
        val (key, ssid) = readCurrentWifiIdentity(LatLon(latitude, longitude)) ?: return
        val existing = geoZones.wifiAnchors()[key]
        if (existing != null) {
            val drift = GeoZoneEngine.haversineMeters(
                existing.latitude,
                existing.longitude,
                latitude,
                longitude,
            )
            if (drift > GeoZoneEngine.MAX_GPS_WIFI_ANCHOR_DIVERGENCE_METERS) return
        }
        val shouldIncrement = !currentWifiSessionRecorded || currentWifiSessionKey != key
        val anchor = geoZones.recordWifiConnection(
            networkKey = key,
            ssidLabel = ssid,
            latitude = latitude,
            longitude = longitude,
            incrementConnect = shouldIncrement,
        )
        currentWifiSessionKey = key
        currentWifiSessionRecorded = true
        lastActiveWifiAnchor = anchor
        lastWifiSeenAtElapsedMillis = SystemClock.elapsedRealtime()
    }

    @SuppressLint("MissingPermission")
    private fun readCurrentWifiIdentity(fallbackCoord: LatLon?): Pair<String, String?>? {
        var info: WifiInfo? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val caps = runCatching {
                connectivityManager?.activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
            }.getOrNull()
            info = caps?.transportInfo as? WifiInfo
        }
        if (info == null) {
            @Suppress("DEPRECATION")
            info = runCatching { wifiManager?.connectionInfo }.getOrNull()
        }

        val rawBssid = runCatching { info?.bssid }.getOrNull()?.trim()
        val validBssid = rawBssid?.takeIf {
            it.isNotEmpty() && it != MASKED_BSSID && it != ZERO_BSSID
        }

        val rawSsid = runCatching { info?.ssid }.getOrNull()?.trim()?.removeSurrounding("\"")
        val validSsid = rawSsid?.takeIf {
            it.isNotEmpty() && !it.equals(UNKNOWN_SSID, ignoreCase = true)
        }

        val key = when {
            validBssid != null -> "bssid:${validBssid.lowercase(Locale.ROOT)}"
            validSsid != null -> "ssid:$validSsid"
            fallbackCoord != null -> "wifi-hex:${HexCoord.fromLatLon(fallbackCoord.latitude, fallbackCoord.longitude).key}"
            else -> return null
        }
        return key to validSsid
    }

    private fun describeProximity(prox: ZoneProximity): String = when (prox) {
        ZoneProximity.Outside -> "Outside"
        is ZoneProximity.Approaching ->
            "Approaching(${prox.cluster.id},dist=${prox.distanceMeters}m,vel=${prox.predictedByVelocity})"
        is ZoneProximity.Inside ->
            "Inside(${prox.cluster.id},${prox.cluster.kind},tower=${prox.matchedByCellTower})"
    }

    private fun checkAnyLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun checkFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "YumiGeo"
        const val PASSIVE_INTERVAL_MS = 15_000L
        const val PASSIVE_DISTANCE_M = 35f
        const val PASSIVE_FAST_INTERVAL_MS = 10_000L
        const val PASSIVE_FAST_DISTANCE_M = 25f
        const val ACTIVE_INTERVAL_MS = 3_000L
        const val ACTIVE_DISTANCE_M = 5f
        const val ACTIVE_NETWORK_INTERVAL_MS = 5_000L
        const val ACTIVE_NETWORK_DISTANCE_M = 10f
        const val DEFAULT_BURST_MILLIS = 45_000L
        const val TOWER_POLL_INTERVAL_MS = 20_000L
        const val SPOOF_LOG_COOLDOWN_MS = 120_000L
        const val MAX_ANCHOR_ACCURACY_METERS = 300f
        const val WIFI_ANCHOR_FIX_MAX_AGE_MILLIS = 300_000L
        const val MASKED_BSSID = "02:00:00:00:00:00"
        const val ZERO_BSSID = "00:00:00:00:00:00"
        const val UNKNOWN_SSID = "<unknown ssid>"
        const val FALLBACK_DBM = -105
        const val NR_TOWER_BIT = 1L shl 62
    }
}
