package com.mydrop.vpn.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.net.ConnectivityManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.mydrop.vpn.core.model.GeoSample
import com.mydrop.vpn.core.model.GeoTrackingTier
import com.mydrop.vpn.core.model.GeoZoneEngine
import com.mydrop.vpn.core.model.HexCoord
import com.mydrop.vpn.core.model.LatLon
import com.mydrop.vpn.core.model.NetworkTransport
import com.mydrop.vpn.core.model.ObservedCellTower
import com.mydrop.vpn.core.model.VpnState
import com.mydrop.vpn.core.model.WifiAnchor
import com.mydrop.vpn.core.model.ZoneProximity
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.vpn.MyDropVpnService
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import kotlin.coroutines.resume
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Adaptive location & serving cell-tower monitor for geo-zones.
 *
 * Uses event-driven cell changes and the passive location provider as a zero-wakeup baseline on
 * cellular, resolves one-shot fixes only on demand when mapping a drop or tower change, engages
 * low-power balanced updates only when moving near known zones, and bursts to GPS briefly only on
 * immediate drops/approaches or while the map screen is visible.
 */
class GeoLocationMonitor(
    private val context: Context,
    private val settings: SettingsRepository,
    private val tunnel: TunnelController,
    private val geoZones: GeoZoneRepository,
    private val logs: LogRepository,
    private val scope: CoroutineScope,
) {
    private data class TierConfigInputs(
        val enabled: Boolean,
        val preemptive: Boolean,
        val perm: Boolean,
        val mapOpen: Boolean,
        val burst: Boolean,
    )

    private data class TierDynamicInputs(
        val vpnConnected: Boolean,
        val cellular: Boolean,
        val screenOn: Boolean,
        val nearZone: Boolean,
        val moved: Boolean,
    )

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

    private val _hasBackgroundPermission = MutableStateFlow(checkBackgroundLocationPermission())
    val hasBackgroundPermission: StateFlow<Boolean> = _hasBackgroundPermission.asStateFlow()

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
    private val recentlyMoved = MutableStateFlow(false)

    private val burstLock = Any()
    private var burstJob: Job? = null
    private var moveDecayJob: Job? = null
    private var lastBurstStartedMillis: Long = 0L

    private var lastValidSample: GeoSample? = null
    private var previousSample: GeoSample? = null
    private var lastNetworkCoord: LatLon? = null
    private var lastNetworkAtElapsedMillis: Long = 0L
    private var lastSpoofLoggedMillis = 0L
    private var lastCellErrorLoggedMillis = 0L
    private var lastTowerAnchorKey: Long? = null
    private var lastTowerAnchorAtElapsedMillis: Long = 0L

    private var lastSeenTowerId: Long? = null
    private val recentTowerEntries = ArrayDeque<Pair<Long, Long>>()
    private var lastTowerOneShotMillis: Long = 0L
    private var activeOneShotCancel: CancellationSignal? = null
    private var telephonyCallbackApi31: Any? = null
    private var cellListenerBlocked: Boolean = false
    @Suppress("DEPRECATION")
    private var phoneStateListenerLegacy: PhoneStateListener? = null

    private var registeredTier = GeoTrackingTier.Off
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
            settings.settings
                .map { it.geoZonesActive }
                .distinctUntilChanged()
                .collect { MyDropVpnService.refreshForegroundType() }
        }

        scope.launch {
            MyDropVpnService.locationFgsActive.collect { active ->
                if (active && registeredTier != GeoTrackingTier.Off) {
                    applyTier(registeredTier, force = true)
                }
            }
        }

        scope.launch {
            val nearKnownZoneFlow = combine(
                _lastValidLatLon,
                _currentSample.map { it?.cellTowerId }.distinctUntilChanged(),
                geoZones.clusters,
            ) { coord, tower, clusters ->
                GeoZoneEngine.isNearActionableZone(coord, tower, clusters)
            }.distinctUntilChanged()

            val configFlags = combine(
                settings.settings.map { it.geoZonesActive to it.geoPreemptiveSwitch }.distinctUntilChanged(),
                _hasPermission,
                mapActive,
                burstActive,
            ) { (enabled, preemptive), perm, mapOpen, burst ->
                TierConfigInputs(enabled, preemptive, perm, mapOpen, burst)
            }

            val dynamicSignals = combine(
                tunnel.state,
                tunnel.transport,
                tunnel.screenOn,
                nearKnownZoneFlow,
                recentlyMoved,
            ) { vpnState, transport, screenOn, nearZone, moved ->
                TierDynamicInputs(
                    vpnConnected = vpnState is VpnState.Connected,
                    cellular = transport == NetworkTransport.Cellular,
                    screenOn = screenOn,
                    nearZone = nearZone,
                    moved = moved,
                )
            }

            combine(configFlags, dynamicSignals) { cfg, dyn ->
                GeoZoneEngine.selectTrackingTier(
                    enabled = cfg.enabled,
                    hasPermission = cfg.perm,
                    vpnConnected = dyn.vpnConnected,
                    cellular = dyn.cellular,
                    mapOpen = cfg.mapOpen,
                    burstActive = cfg.burst,
                    screenOn = dyn.screenOn,
                    nearKnownZone = dyn.nearZone,
                    recentlyMoved = dyn.moved,
                    preemptiveEnabled = cfg.preemptive,
                )
            }
                .distinctUntilChanged()
                .collect { tier -> applyTier(tier) }
        }

        scope.launch {
            tunnel.transport.collect { transport -> onTransportChanged(transport) }
        }

        scope.launch {
            combine(
                _currentSample,
                _lastValidLatLon,
                geoZones.clusters,
                settings.settings.map { it.geoZonesActive }.distinctUntilChanged(),
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
        val prevBg = _hasBackgroundPermission.value
        val now = checkAnyLocationPermission()
        val nowBg = checkBackgroundLocationPermission()
        _hasPermission.value = now
        _hasBackgroundPermission.value = nowBg
        if (prev != now || prevBg != nowBg) {
            cellListenerBlocked = false
            logs.trace(
                TAG,
                "permission changed: perm=$prev->$now fine=${checkFineLocationPermission()} bg=$prevBg->$nowBg",
            )
        }
        MyDropVpnService.refreshForegroundType()
        if (now && settings.value.geoZonesActive && _lastValidLatLon.value == null) {
            scope.launch(Dispatchers.Main.immediate) {
                seedLastKnownLocation(allowStaleForMap = mapActive.value)
            }
        }
    }

    fun setMapScreenActive(active: Boolean) {
        if (active) refreshPermission()
        if (mapActive.value != active) {
            logs.trace(TAG, "mapScreenActive=$active")
        }
        mapActive.value = active
    }

    fun requestHighAccuracyBurst(
        durationMillis: Long = DEFAULT_BURST_MILLIS,
        force: Boolean = false,
    ) {
        if (!_hasPermission.value || !settings.value.geoZonesActive) return
        val now = SystemClock.elapsedRealtime()
        synchronized(burstLock) {
            if (!force && lastBurstStartedMillis != 0L && (now - lastBurstStartedMillis) < BURST_COOLDOWN_MS) {
                return
            }
            lastBurstStartedMillis = now
            burstJob?.cancel()
            burstActive.value = true
            burstJob = scope.launch {
                delay(durationMillis)
                burstActive.value = false
            }
        }
        logs.trace(TAG, "high-accuracy burst requested (${durationMillis}ms, force=$force)")
        if (freshSampleOrNull() == null) {
            requestOneShotLocation(preferGps = false, reason = "burst-seed")
        }
    }

    fun freshSampleOrNull(maxAgeMillis: Long = GeoZoneEngine.MAX_FIX_AGE_MILLIS): GeoSample? {
        val sample = _currentSample.value ?: return null
        return sample.takeIf { GeoZoneEngine.isFixFresh(it, SystemClock.elapsedRealtime(), maxAgeMillis) }
    }

    suspend fun resolveFreshSample(timeoutMillis: Long = DEFAULT_ONESHOT_TIMEOUT_MILLIS): GeoSample? {
        if (!settings.value.geoZonesActive || !_hasPermission.value) return null
        freshSampleOrNull()?.let { return it }

        val manager = locationManager
        val provider = manager?.let { pickOneShotProvider(it, preferGps = false) }
        if (manager != null && provider != null) {
            val location = requestSingleLocationSuspend(manager, provider, timeoutMillis)
            if (location != null) {
                withContext(Dispatchers.Main.immediate) {
                    handleLocation(location)
                }
                freshSampleOrNull()?.let { return it }
            }
        }

        withContext(Dispatchers.Main.immediate) {
            refreshTowerOnly()
        }
        return freshSampleOrNull()
    }

    private fun applyTier(tier: GeoTrackingTier, force: Boolean = false) {
        if (!force && tier == registeredTier) return
        scope.launch(Dispatchers.Main.immediate) {
            if (!force && tier == registeredTier) return@launch
            val prev = registeredTier
            unregisterLocationUpdates()
            unregisterCellListener()
            if (tier == GeoTrackingTier.Off) {
                activeOneShotCancel?.cancel()
                activeOneShotCancel = null
            }
            registeredTier = tier
            logs.trace(
                TAG,
                "tier=$prev->$tier force=$force perm=${_hasPermission.value} " +
                    "fine=${checkFineLocationPermission()} bg=${_hasBackgroundPermission.value} " +
                    "fgsLoc=${MyDropVpnService.locationFgsActive.value} " +
                    "enabled=${settings.value.geoZonesActive} vpn=${tunnel.state.value::class.simpleName} " +
                    "transport=${tunnel.transport.value} map=${mapActive.value} " +
                    "burst=${burstActive.value} moved=${recentlyMoved.value}",
            )
            when (tier) {
                GeoTrackingTier.Off -> Unit

                GeoTrackingTier.Dormant -> {
                    seedLastKnownLocation(allowStaleForMap = false)
                    registerProvider(LocationManager.PASSIVE_PROVIDER, PASSIVE_INTERVAL_MS, PASSIVE_DISTANCE_M)
                    registerCellListener()
                }

                GeoTrackingTier.Balanced -> {
                    seedLastKnownLocation(allowStaleForMap = false)
                    registerBalancedProvider()
                    registerProvider(LocationManager.PASSIVE_PROVIDER, PASSIVE_INTERVAL_MS, PASSIVE_DISTANCE_M)
                    registerCellListener()
                }

                GeoTrackingTier.HighAccuracy -> {
                    seedLastKnownLocation(allowStaleForMap = mapActive.value)
                    registerProvider(LocationManager.GPS_PROVIDER, ACTIVE_INTERVAL_MS, ACTIVE_DISTANCE_M)
                    registerProvider(
                        LocationManager.NETWORK_PROVIDER,
                        ACTIVE_NETWORK_INTERVAL_MS,
                        ACTIVE_NETWORK_DISTANCE_M,
                    )
                    registerProvider(LocationManager.PASSIVE_PROVIDER, ACTIVE_INTERVAL_MS, ACTIVE_DISTANCE_M)
                    registerCellListener()
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
        }.onFailure { error ->
            logs.trace(TAG, "registerProvider($provider) failed: ${error.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerBalancedProvider() {
        val manager = locationManager ?: return
        if (!checkAnyLocationPermission()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val providers = runCatching { manager.allProviders }.getOrDefault(emptyList())
            val provider = when {
                LocationManager.FUSED_PROVIDER in providers -> LocationManager.FUSED_PROVIDER
                LocationManager.NETWORK_PROVIDER in providers -> LocationManager.NETWORK_PROVIDER
                else -> return
            }
            runCatching {
                registerBalancedProviderApi31(manager, provider)
            }.onFailure { error ->
                logs.trace(TAG, "registerBalancedProvider($provider) failed: ${error.message}")
            }
        } else {
            registerProvider(LocationManager.NETWORK_PROVIDER, BALANCED_INTERVAL_MS, BALANCED_DISTANCE_M)
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerBalancedProviderApi31(manager: LocationManager, provider: String) {
        val request = LocationRequest.Builder(BALANCED_INTERVAL_MS)
            .setQuality(LocationRequest.QUALITY_LOW_POWER)
            .setMinUpdateDistanceMeters(BALANCED_DISTANCE_M)
            .setMinUpdateIntervalMillis(BALANCED_MIN_INTERVAL_MS)
            .build()
        manager.requestLocationUpdates(provider, request, context.mainExecutor, locationListener)
    }

    private fun unregisterLocationUpdates() {
        runCatching { locationManager?.removeUpdates(locationListener) }
    }

    @SuppressLint("MissingPermission")
    private fun registerCellListener() {
        if (cellListenerBlocked || !checkFineLocationPermission()) return
        val manager = telephonyManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (telephonyCallbackApi31 != null) return
            runCatching {
                registerTelephonyCallbackApi31(manager)
            }.onFailure { error ->
                if (error is SecurityException) {
                    cellListenerBlocked = true
                    logs.trace(TAG, "registerTelephonyCallback unavailable (missing permission), using cell polling: ${error.message}")
                } else {
                    logs.trace(TAG, "registerTelephonyCallback failed: ${error.message}")
                }
            }
        } else {
            if (phoneStateListenerLegacy != null) return
            runCatching {
                registerLegacyPhoneStateListener(manager)
            }.onFailure { error ->
                if (error is SecurityException) {
                    cellListenerBlocked = true
                    logs.trace(TAG, "PhoneStateListener unavailable (missing permission), using cell polling: ${error.message}")
                } else {
                    logs.trace(TAG, "PhoneStateListener.listen failed: ${error.message}")
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerTelephonyCallbackApi31(manager: TelephonyManager) {
        val callback = object : TelephonyCallback(), TelephonyCallback.CellInfoListener {
            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                handleCellInfoList(cellInfo)
            }
        }
        manager.registerTelephonyCallback(context.mainExecutor, callback)
        telephonyCallbackApi31 = callback
    }

    @Suppress("DEPRECATION")
    private fun registerLegacyPhoneStateListener(manager: TelephonyManager) {
        val listener = object : PhoneStateListener() {
            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>?) {
                if (cellInfo != null) handleCellInfoList(cellInfo)
            }
        }
        manager.listen(listener, PhoneStateListener.LISTEN_CELL_INFO)
        phoneStateListenerLegacy = listener
    }

    private fun unregisterCellListener() {
        val manager = telephonyManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = telephonyCallbackApi31 as? TelephonyCallback
            if (cb != null) {
                runCatching { manager.unregisterTelephonyCallback(cb) }
                telephonyCallbackApi31 = null
            }
        } else {
            @Suppress("DEPRECATION")
            val listener = phoneStateListenerLegacy
            if (listener != null) {
                @Suppress("DEPRECATION")
                runCatching { manager.listen(listener, PhoneStateListener.LISTEN_NONE) }
                phoneStateListenerLegacy = null
            }
        }
    }

    private fun handleCellInfoList(infos: List<CellInfo>) {
        if (registeredTier == GeoTrackingTier.Off || !settings.value.geoZonesActive || !_hasPermission.value) return
        val observedTowers = extractObservedTowers(infos)
        val towerId = observedTowers.firstOrNull { it.registered }?.towerId ?: return
        if (towerId == lastSeenTowerId) return

        val now = SystemClock.elapsedRealtime()
        val isPingPong = recentTowerEntries.any { (id, ts) ->
            id == towerId && (now - ts) in 0L..TOWER_PINGPONG_WINDOW_MS
        }
        lastSeenTowerId?.let { prevId ->
            if (recentTowerEntries.size >= TOWER_HISTORY_SIZE) {
                recentTowerEntries.removeFirst()
            }
            recentTowerEntries.addLast(prevId to now)
        }
        lastSeenTowerId = towerId

        applyObservedTowers(observedTowers, towerId)
        if (!isPingPong) {
            markRecentlyMoved()
            if (freshSampleOrNull() == null && (now - lastTowerOneShotMillis) >= TOWER_ONESHOT_COOLDOWN_MS) {
                lastTowerOneShotMillis = now
                requestOneShotLocation(preferGps = false, reason = "tower-change")
            }
        }
    }

    private fun markRecentlyMoved() {
        recentlyMoved.value = true
        moveDecayJob?.cancel()
        moveDecayJob = scope.launch {
            delay(MOVEMENT_WINDOW_MILLIS)
            recentlyMoved.value = false
        }
    }

    private fun requestOneShotLocation(preferGps: Boolean, reason: String) {
        if (!_hasPermission.value || !settings.value.geoZonesActive) return
        scope.launch(Dispatchers.Main.immediate) {
            val manager = locationManager ?: return@launch
            val provider = pickOneShotProvider(manager, preferGps) ?: return@launch
            activeOneShotCancel?.cancel()
            val signal = CancellationSignal()
            activeOneShotCancel = signal
            logs.trace(TAG, "one-shot location requested ($reason, provider=$provider)")
            requestSingleLocationCallback(manager, provider, signal) { location ->
                if (activeOneShotCancel === signal) {
                    activeOneShotCancel = null
                }
                if (location != null) {
                    handleLocation(location)
                }
            }
        }
    }

    private fun pickOneShotProvider(manager: LocationManager, preferGps: Boolean): String? {
        val providers = runCatching { manager.allProviders }.getOrDefault(emptyList())
        return when {
            preferGps && checkFineLocationPermission() && LocationManager.GPS_PROVIDER in providers ->
                LocationManager.GPS_PROVIDER
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in providers ->
                LocationManager.FUSED_PROVIDER
            LocationManager.NETWORK_PROVIDER in providers ->
                LocationManager.NETWORK_PROVIDER
            checkFineLocationPermission() && LocationManager.GPS_PROVIDER in providers ->
                LocationManager.GPS_PROVIDER
            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleLocationCallback(
        manager: LocationManager,
        provider: String,
        signal: CancellationSignal,
        onResult: (Location?) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                requestCurrentLocationApi30(manager, provider, signal, onResult)
            }.onFailure { error ->
                logs.trace(TAG, "getCurrentLocation($provider) failed: ${error.message}")
                onResult(null)
            }
        } else {
            val done = AtomicBoolean(false)
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (done.compareAndSet(false, true)) {
                        runCatching { manager.removeUpdates(this) }
                        onResult(location)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            signal.setOnCancelListener {
                if (done.compareAndSet(false, true)) {
                    runCatching { manager.removeUpdates(listener) }
                    onResult(null)
                }
            }
            @Suppress("DEPRECATION")
            runCatching {
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }.onFailure { error ->
                if (done.compareAndSet(false, true)) {
                    logs.trace(TAG, "requestSingleUpdate($provider) failed: ${error.message}")
                    onResult(null)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestCurrentLocationApi30(
        manager: LocationManager,
        provider: String,
        signal: CancellationSignal,
        onResult: (Location?) -> Unit,
    ) {
        manager.getCurrentLocation(
            provider,
            signal,
            context.mainExecutor,
            Consumer { location -> onResult(location) },
        )
    }

    private suspend fun requestSingleLocationSuspend(
        manager: LocationManager,
        provider: String,
        timeoutMillis: Long,
    ): Location? = withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            val resumed = AtomicBoolean(false)
            cont.invokeOnCancellation { signal.cancel() }
            requestSingleLocationCallback(manager, provider, signal) { location ->
                if (resumed.compareAndSet(false, true) && cont.isActive) {
                    cont.resume(location)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnownLocation(allowStaleForMap: Boolean = false) {
        val manager = locationManager ?: return
        if (!checkAnyLocationPermission()) return
        // Seed Network before GPS so cold-start cross-checking has cellular/Wi-Fi reference first.
        val orderedProviders = buildList {
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
            if (checkFineLocationPermission()) add(LocationManager.GPS_PROVIDER)
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        var seededFresh = false
        for (provider in orderedProviders) {
            val fix = runCatching {
                if (provider in manager.allProviders) manager.getLastKnownLocation(provider) else null
            }.getOrNull() ?: continue
            val fixElapsed = runCatching { fix.elapsedRealtimeNanos / 1_000_000L }.getOrNull()
            val isFresh = fixElapsed != null && (nowElapsed - fixElapsed) in 0L..SEED_MAX_AGE_MILLIS
            if (isFresh) {
                handleLocation(fix)
                seededFresh = true
            } else if (allowStaleForMap && _lastValidLatLon.value == null) {
                val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    fix.isMock
                } else {
                    @Suppress("DEPRECATION")
                    fix.isFromMockProvider
                }
                val accuracy = if (fix.hasAccuracy()) fix.accuracy else 100f
                if (!isMock && accuracy <= 1000f) {
                    _lastValidLatLon.value = LatLon(fix.latitude, fix.longitude)
                }
            }
        }
        if (!seededFresh) {
            refreshTowerOnly()
        }
    }

    private fun refreshTowerOnly() {
        val observedTowers = readObservedCellTowers()
        val towerId = observedTowers.firstOrNull { it.registered }?.towerId ?: readCellTowerId() ?: return
        applyObservedTowers(observedTowers, towerId)
    }

    private fun applyObservedTowers(observedTowers: List<ObservedCellTower>, towerId: Long) {
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
        val nowElapsed = SystemClock.elapsedRealtime()
        if (location.provider == LocationManager.NETWORK_PROVIDER) {
            lastNetworkCoord = LatLon(candidate.latitude, candidate.longitude)
            lastNetworkAtElapsedMillis = elapsedMillis
        }

        val prevValid = lastValidSample
        if (prevValid != null) {
            val movedMeters = GeoZoneEngine.haversineMeters(
                prevValid.latitude,
                prevValid.longitude,
                candidate.latitude,
                candidate.longitude,
            )
            if (movedMeters >= MOVEMENT_DISTANCE_METERS) {
                markRecentlyMoved()
            }
        }

        if (towerId != null && candidate.accuracyMeters <= MAX_ANCHOR_ACCURACY_METERS) {
            val towerSamples = anchors[towerId]?.samples ?: 0
            val anchorDue = towerId != lastTowerAnchorKey ||
                (nowElapsed - lastTowerAnchorAtElapsedMillis) !in 0L until TOWER_ANCHOR_RECORD_COOLDOWN_MS ||
                towerSamples < 3
            if (anchorDue) {
                lastTowerAnchorKey = towerId
                lastTowerAnchorAtElapsedMillis = nowElapsed
                geoZones.recordTowerObservation(towerId, candidate.latitude, candidate.longitude)
            }
        }

        if (tunnel.transport.value == NetworkTransport.Wifi &&
            candidate.accuracyMeters <= MAX_ANCHOR_ACCURACY_METERS &&
            (nowElapsed - elapsedMillis) in 0L..WIFI_ANCHOR_FIX_MAX_AGE_MILLIS
        ) {
            recordWifiAnchorAt(candidate.latitude, candidate.longitude)
        }

        previousSample = lastValidSample
        lastValidSample = candidate
        _lastValidLatLon.value = LatLon(candidate.latitude, candidate.longitude)
        _currentSample.value = candidate

        if (burstActive.value &&
            !mapActive.value &&
            candidate.accuracyMeters <= BURST_GOOD_ACCURACY_METERS &&
            (nowElapsed - elapsedMillis) in 0L..DEFAULT_BURST_MILLIS
        ) {
            synchronized(burstLock) {
                burstJob?.cancel()
                burstJob = null
                burstActive.value = false
            }
            logs.trace(TAG, "burst satisfied early acc=${candidate.accuracyMeters.toInt()}m")
        }

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
        val infos = runCatching { manager.allCellInfo }
            .onFailure { logCellReadFailure(it) }
            .getOrNull() ?: return emptyList()
        return extractObservedTowers(infos)
    }

    private fun extractObservedTowers(infos: List<CellInfo>): List<ObservedCellTower> {
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
        val infos = runCatching { manager.allCellInfo }
            .onFailure { logCellReadFailure(it) }
            .getOrNull() ?: return null
        for (info in infos) {
            if (!info.isRegistered) continue
            extractTowerId(info)?.let { return it }
        }
        return null
    }

    private fun logCellReadFailure(error: Throwable) {
        val now = SystemClock.elapsedRealtime()
        if (lastCellErrorLoggedMillis == 0L || (now - lastCellErrorLoggedMillis) >= CELL_ERROR_LOG_COOLDOWN_MS) {
            lastCellErrorLoggedMillis = now
            logs.trace(TAG, "allCellInfo failed: ${error.message}")
        }
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
            if (!settings.value.geoZonesActive) return
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
                settings.value.geoZonesActive &&
                checkAnyLocationPermission()
            ) {
                scope.launch(Dispatchers.Main.immediate) {
                    seedLastKnownLocation(allowStaleForMap = mapActive.value)
                }
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

    private fun checkBackgroundLocationPermission(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            checkAnyLocationPermission()
        } else {
            checkAnyLocationPermission() &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    private companion object {
        const val TAG = "YumiGeo"
        const val PASSIVE_INTERVAL_MS = 15_000L
        const val PASSIVE_DISTANCE_M = 35f
        const val BALANCED_INTERVAL_MS = 45_000L
        const val BALANCED_MIN_INTERVAL_MS = 30_000L
        const val BALANCED_DISTANCE_M = 100f
        const val ACTIVE_INTERVAL_MS = 3_000L
        const val ACTIVE_DISTANCE_M = 5f
        const val ACTIVE_NETWORK_INTERVAL_MS = 5_000L
        const val ACTIVE_NETWORK_DISTANCE_M = 10f
        const val DEFAULT_BURST_MILLIS = 15_000L
        const val BURST_COOLDOWN_MS = 60_000L
        const val BURST_GOOD_ACCURACY_METERS = 80f
        const val DEFAULT_ONESHOT_TIMEOUT_MILLIS = 3_500L
        const val SEED_MAX_AGE_MILLIS = 300_000L
        const val MOVEMENT_WINDOW_MILLIS = 180_000L
        const val MOVEMENT_DISTANCE_METERS = 50.0
        const val TOWER_HISTORY_SIZE = 3
        const val TOWER_PINGPONG_WINDOW_MS = 180_000L
        const val TOWER_ONESHOT_COOLDOWN_MS = 120_000L
        const val TOWER_ANCHOR_RECORD_COOLDOWN_MS = 90_000L
        const val CELL_ERROR_LOG_COOLDOWN_MS = 120_000L
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
