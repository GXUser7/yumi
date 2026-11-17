package com.mydrop.vpn.data

import com.mydrop.vpn.core.model.GeoBreadcrumb
import com.mydrop.vpn.core.model.GeoCellRecord
import com.mydrop.vpn.core.model.GeoZoneCluster
import com.mydrop.vpn.core.model.GeoZoneEngine
import com.mydrop.vpn.core.model.GeoZoneStoreState
import com.mydrop.vpn.core.model.HexCoord
import com.mydrop.vpn.core.model.TowerAnchor
import com.mydrop.vpn.core.model.WifiAnchor
import com.mydrop.vpn.core.model.ZoneKind
import com.mydrop.vpn.shared.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists hex-grid drop/whitelist statistics, proven mobile relays per zone, and breadcrumbs.
 */
class GeoZoneRepository(
    filesDir: File,
    scope: CoroutineScope,
    onWriteFailure: (Throwable) -> Unit = {},
    private val logs: LogRepository? = null,
) {
    private val store = JsonStore(
        file = File(filesDir, "geo_zones.json"),
        serializer = GeoZoneStoreState.serializer(),
        defaultValue = GeoZoneStoreState(),
        scope = scope,
        onWriteFailure = onWriteFailure,
    )

    private val clusterLock = Any()

    val state: StateFlow<GeoZoneStoreState> = store.state

    private val _clusters = MutableStateFlow(GeoZoneEngine.clusterCells(store.value.cells.values))
    val clusters: StateFlow<List<GeoZoneCluster>> = _clusters.asStateFlow()

    fun towerAnchors(): Map<Long, TowerAnchor> = store.value.towerAnchors

    fun wifiAnchors(): Map<String, WifiAnchor> = store.value.wifiAnchors

    fun recordWifiConnection(
        networkKey: String,
        ssidLabel: String?,
        latitude: Double,
        longitude: Double,
        incrementConnect: Boolean,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): WifiAnchor {
        var wasFrequent = false
        var updatedAnchor: WifiAnchor? = null
        mutate { current ->
            val existing = current.wifiAnchors[networkKey]
            wasFrequent = existing?.isFrequentHome == true
            val next = existing?.updateWith(
                newLat = latitude,
                newLon = longitude,
                newSsidLabel = ssidLabel,
                incrementConnect = incrementConnect,
                nowEpochMillis = nowEpochMillis,
            ) ?: WifiAnchor(
                networkKey = networkKey,
                ssidLabel = ssidLabel?.takeIf { it.isNotBlank() },
                latitude = latitude,
                longitude = longitude,
                connectCount = 1,
                lastConnectedEpochMillis = nowEpochMillis,
            )
            updatedAnchor = next
            current.copy(wifiAnchors = trimWifiAnchors(current.wifiAnchors + (networkKey to next)))
        }
        val anchor = updatedAnchor!!
        val hexKey = HexCoord.fromLatLon(anchor.latitude, anchor.longitude).key
        logs?.trace(
            TAG,
            "wifiAnchor key=$networkKey ssid=${anchor.ssidLabel ?: "-"} " +
                "connects=${anchor.connectCount} home=${anchor.isFrequentHome} hex=$hexKey",
        )
        if (!wasFrequent && anchor.isFrequentHome) {
            logs?.info(
                R.string.log_geozone_home_wifi_learned,
                anchor.ssidLabel ?: networkKey,
                hexKey,
            )
        }
        return anchor
    }

    fun recordTowerObservation(
        towerId: Long,
        latitude: Double,
        longitude: Double,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        var updatedAnchor: TowerAnchor? = null
        mutate { current ->
            val existing = current.towerAnchors[towerId]
            val next = existing?.updateWith(latitude, longitude, nowEpochMillis)
                ?: TowerAnchor(
                    towerId = towerId,
                    latitude = latitude,
                    longitude = longitude,
                    samples = 1,
                    lastUpdatedEpochMillis = nowEpochMillis,
                )
            updatedAnchor = next
            current.copy(towerAnchors = trimTowerAnchors(current.towerAnchors + (towerId to next)))
        }
        val anchor = updatedAnchor ?: return
        if (anchor.samples == 1 || anchor.samples % 5 == 0) {
            logs?.trace(
                TAG,
                "towerAnchor tower=$towerId hex=${HexCoord.fromLatLon(anchor.latitude, anchor.longitude).key} " +
                    "samples=${anchor.samples}",
            )
        }
    }

    fun recordOrdinaryOutcome(
        hex: HexCoord,
        cellTowerId: Long?,
        passed: Boolean,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        var transition: Pair<ZoneKind, ZoneKind>? = null
        var snapshotRecord: GeoCellRecord? = null
        var ignoredByHomeWifi = false
        mutate { current ->
            val key = hex.key
            val existing = current.cells[key] ?: GeoCellRecord(hex = hex)
            if (!passed &&
                !existing.pinned &&
                GeoZoneEngine.isProtectedByHomeWifi(hex, current.wifiAnchors.values)
            ) {
                ignoredByHomeWifi = true
                return@mutate current
            }
            val oldKind = existing.kind()
            val updated = if (passed) {
                val streak = existing.consecutiveOrdinarySuccesses + 1
                val healed = !existing.pinned && streak >= GeoZoneEngine.HEAL_ORDINARY_STREAK
                existing.copy(
                    ordinarySuccesses = existing.ordinarySuccesses + 1,
                    consecutiveOrdinarySuccesses = streak,
                    ordinaryFailures = if (healed) 0 else existing.ordinaryFailures,
                    totalBlackouts = if (healed) 0 else existing.totalBlackouts,
                    lastUpdatedEpochMillis = nowEpochMillis,
                )
            } else {
                existing.copy(
                    ordinaryFailures = existing.ordinaryFailures + 1,
                    consecutiveOrdinarySuccesses = 0,
                    cellTowerIds = if (cellTowerId != null) {
                        existing.cellTowerIds + cellTowerId
                    } else {
                        existing.cellTowerIds
                    },
                    lastUpdatedEpochMillis = nowEpochMillis,
                )
            }
            val newKind = updated.kind()
            transition = oldKind to newKind
            snapshotRecord = updated
            current.copy(cells = trimHealthyCells(current.cells + (key to updated)))
        }

        if (ignoredByHomeWifi) {
            logs?.trace(TAG, "ordinaryOutcome ignored at home-wifi hex=${hex.key}")
            return
        }
        val rec = snapshotRecord ?: return
        val (oldKind, newKind) = transition ?: return
        logs?.trace(
            TAG,
            "ordinaryOutcome hex=${hex.key} passed=$passed kind=$oldKind->$newKind " +
                "ordFail=${rec.ordinaryFailures} ordOk=${rec.ordinarySuccesses} " +
                "streak=${rec.consecutiveOrdinarySuccesses} mobOk=${rec.mobileSuccesses} " +
                "blackout=${rec.totalBlackouts} tower=${cellTowerId ?: "none"} pinned=${rec.pinned}",
        )
        if (oldKind != newKind) {
            if (newKind == ZoneKind.Whitelist && !rec.pinned) {
                logs?.info(R.string.log_geozone_learned, hex.key, rec.ordinaryFailures, rec.mobileSuccesses)
            } else if (oldKind != ZoneKind.Healthy && newKind == ZoneKind.Healthy) {
                logs?.info(R.string.log_geozone_healed, hex.key)
            }
        }
    }

    fun recordMobileOutcome(
        hex: HexCoord,
        cellTowerId: Long?,
        nodeId: String,
        millis: Int?,
        passed: Boolean,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        if (!passed) return
        var transition: Pair<ZoneKind, ZoneKind>? = null
        var snapshotRecord: GeoCellRecord? = null
        mutate { current ->
            val key = hex.key
            val existing = current.cells[key] ?: GeoCellRecord(hex = hex)
            val oldKind = existing.kind()
            val latency = (millis ?: DEFAULT_FALLBACK_LATENCY_MS).coerceAtLeast(1)
            val updatedNodes = existing.workingMobileNodeIds.toMutableMap()
            val prev = updatedNodes[nodeId]
            updatedNodes[nodeId] = if (prev == null) latency else minOf(prev, latency)

            val updated = existing.copy(
                mobileSuccesses = existing.mobileSuccesses + 1,
                workingMobileNodeIds = updatedNodes,
                cellTowerIds = if (cellTowerId != null) {
                    existing.cellTowerIds + cellTowerId
                } else {
                    existing.cellTowerIds
                },
                lastUpdatedEpochMillis = nowEpochMillis,
            )
            val newKind = updated.kind()
            transition = oldKind to newKind
            snapshotRecord = updated
            current.copy(cells = trimHealthyCells(current.cells + (key to updated)))
        }

        val rec = snapshotRecord ?: return
        val (oldKind, newKind) = transition ?: return
        logs?.trace(
            TAG,
            "mobileOutcome hex=${hex.key} node=$nodeId ms=${millis ?: DEFAULT_FALLBACK_LATENCY_MS} " +
                "kind=$oldKind->$newKind ordFail=${rec.ordinaryFailures} mobOk=${rec.mobileSuccesses} " +
                "tower=${cellTowerId ?: "none"} working=${rec.workingMobileNodeIds.keys.joinToString(",")}",
        )
        if (oldKind != newKind && newKind == ZoneKind.Whitelist && !rec.pinned) {
            logs?.info(R.string.log_geozone_learned, hex.key, rec.ordinaryFailures, rec.mobileSuccesses)
        }
    }

    fun recordBlackout(
        hex: HexCoord,
        cellTowerId: Long?,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        var transition: Pair<ZoneKind, ZoneKind>? = null
        var snapshotRecord: GeoCellRecord? = null
        mutate { current ->
            val key = hex.key
            val existing = current.cells[key] ?: GeoCellRecord(hex = hex)
            val oldKind = existing.kind()
            val updated = existing.copy(
                totalBlackouts = existing.totalBlackouts + 1,
                consecutiveOrdinarySuccesses = 0,
                cellTowerIds = if (cellTowerId != null) {
                    existing.cellTowerIds + cellTowerId
                } else {
                    existing.cellTowerIds
                },
                lastUpdatedEpochMillis = nowEpochMillis,
            )
            val newKind = updated.kind()
            transition = oldKind to newKind
            snapshotRecord = updated
            current.copy(cells = trimHealthyCells(current.cells + (key to updated)))
        }

        val rec = snapshotRecord ?: return
        val (oldKind, newKind) = transition ?: return
        logs?.trace(
            TAG,
            "blackout hex=${hex.key} kind=$oldKind->$newKind blackout=${rec.totalBlackouts} " +
                "ordFail=${rec.ordinaryFailures} mobOk=${rec.mobileSuccesses} tower=${cellTowerId ?: "none"}",
        )
        if (oldKind != newKind && newKind == ZoneKind.DeadSpot) {
            logs?.warn(R.string.log_geozone_deadspot, hex.key)
        }
    }

    fun recordBreadcrumb(
        latitude: Double,
        longitude: Double,
        onMobileList: Boolean,
        failed: Boolean = false,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        mutate { current ->
            val last = current.breadcrumbs.lastOrNull()
            if (last != null) {
                val dist = GeoZoneEngine.haversineMeters(
                    last.latitude,
                    last.longitude,
                    latitude,
                    longitude,
                )
                if (dist < MIN_BREADCRUMB_DISTANCE_METERS && (!failed || last.failed)) {
                    return@mutate current
                }
            }
            val next = (current.breadcrumbs + GeoBreadcrumb(
                latitude = latitude,
                longitude = longitude,
                timestampMillis = timestampMillis,
                onMobileList = onMobileList,
                failed = failed,
            )).takeLast(MAX_BREADCRUMBS)
            current.copy(breadcrumbs = next)
        }
    }

    fun addManualZone(
        latitude: Double,
        longitude: Double,
        radiusRings: Int = 1,
        name: String? = null,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val center = HexCoord.fromLatLon(latitude, longitude)
        val targets = center.ring(radiusRings)
        mutate { current ->
            val updatedCells = current.cells.toMutableMap()
            for (hex in targets) {
                val key = hex.key
                val existing = updatedCells[key] ?: GeoCellRecord(hex = hex)
                updatedCells[key] = existing.copy(
                    pinned = true,
                    ordinaryFailures = maxOf(2, existing.ordinaryFailures),
                    customName = name?.takeIf(String::isNotBlank) ?: existing.customName,
                    lastUpdatedEpochMillis = nowEpochMillis,
                )
            }
            current.copy(cells = trimHealthyCells(updatedCells))
        }
        logs?.trace(TAG, "manualZoneAdded center=${center.key} cells=${targets.size} name=${name ?: "-"}")
        logs?.info(R.string.log_geozone_manual_added, center.key, targets.size)
    }

    fun togglePinCluster(clusterId: String) {
        var newPinned: Boolean? = null
        var cellCount = 0
        mutate { current ->
            val cluster = _clusters.value.firstOrNull { it.id == clusterId } ?: return@mutate current
            val nextPinned = !cluster.pinned
            newPinned = nextPinned
            cellCount = cluster.hexSet.size
            val updatedCells = current.cells.toMutableMap()
            for (hex in cluster.hexSet) {
                val key = hex.key
                val existing = updatedCells[key] ?: continue
                updatedCells[key] = existing.copy(pinned = nextPinned)
            }
            current.copy(cells = updatedCells)
        }
        if (newPinned != null) {
            logs?.trace(TAG, "clusterPinToggled id=$clusterId pinned=$newPinned cells=$cellCount")
        }
    }

    fun removeCluster(clusterId: String) {
        var removedCount = 0
        mutate { current ->
            val cluster = _clusters.value.firstOrNull { it.id == clusterId } ?: return@mutate current
            val keysToRemove = cluster.hexSet.mapTo(HashSet(cluster.hexSet.size)) { it.key }
            removedCount = keysToRemove.size
            current.copy(cells = current.cells - keysToRemove)
        }
        if (removedCount > 0) {
            logs?.trace(TAG, "clusterRemoved id=$clusterId cells=$removedCount")
        }
    }

    fun clearAll() {
        mutate { GeoZoneStoreState() }
        logs?.trace(TAG, "zonesCleared")
    }

    private fun mutate(transform: (GeoZoneStoreState) -> GeoZoneStoreState) {
        synchronized(clusterLock) {
            store.update(transform)
            _clusters.value = GeoZoneEngine.clusterCells(store.value.cells.values)
        }
    }

    private fun trimHealthyCells(cells: Map<String, GeoCellRecord>): Map<String, GeoCellRecord> {
        if (cells.size <= MAX_CELLS) return cells
        val anomalies = cells.filterValues { it.isAnomaly() }
        val budgetForHealthy = (MAX_CELLS - anomalies.size).coerceAtLeast(0)
        if (budgetForHealthy == 0) return anomalies
        val newestHealthy = cells.entries
            .asSequence()
            .filter { !it.value.isAnomaly() }
            .sortedByDescending { it.value.lastUpdatedEpochMillis }
            .take(budgetForHealthy)
            .associate { it.key to it.value }
        return anomalies + newestHealthy
    }

    private fun trimTowerAnchors(anchors: Map<Long, TowerAnchor>): Map<Long, TowerAnchor> {
        if (anchors.size <= MAX_TOWER_ANCHORS) return anchors
        return anchors.entries
            .asSequence()
            .sortedByDescending { it.value.lastUpdatedEpochMillis }
            .take(MAX_TOWER_ANCHORS)
            .associate { it.key to it.value }
    }

    private fun trimWifiAnchors(anchors: Map<String, WifiAnchor>): Map<String, WifiAnchor> {
        if (anchors.size <= MAX_WIFI_ANCHORS) return anchors
        return anchors.entries
            .asSequence()
            .sortedByDescending { it.value.lastConnectedEpochMillis }
            .take(MAX_WIFI_ANCHORS)
            .associate { it.key to it.value }
    }

    private companion object {
        const val TAG = "YumiGeo"
        const val MAX_BREADCRUMBS = 240
        const val MAX_CELLS = 2_000
        const val MAX_TOWER_ANCHORS = 3_000
        const val MAX_WIFI_ANCHORS = 200
        const val MIN_BREADCRUMB_DISTANCE_METERS = 35.0
        const val DEFAULT_FALLBACK_LATENCY_MS = 250
    }
}
