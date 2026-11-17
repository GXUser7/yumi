package com.mydrop.vpn.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlinx.serialization.Serializable

/** Geographic point in WGS-84 degrees. */
@Serializable
data class LatLon(
    val latitude: Double,
    val longitude: Double,
)

/**
 * Axial coordinate `(q, r)` on a pointy-top hexagonal grid projected in Web Mercator meters.
 *
 * Projecting in Web Mercator avoids longitudinal row shearing at eastern longitudes while keeping
 * every cell roughly 160–235 m in radius across temperate latitudes and aligning cleanly with
 * slippy map tiles.
 */
@Serializable
data class HexCoord(
    val q: Int,
    val r: Int,
) {
    val key: String get() = "$q:$r"

    fun distanceTo(other: HexCoord): Int {
        val dq = abs(q - other.q)
        val dr = abs(r - other.r)
        val ds = abs((q + r) - (other.q + other.r))
        return (dq + dr + ds) / 2
    }

    fun neighbors(): List<HexCoord> = NEIGHBOR_OFFSETS.map { (dq, dr) ->
        HexCoord(q + dq, r + dr)
    }

    fun ring(radius: Int): Set<HexCoord> {
        if (radius <= 0) return setOf(this)
        val result = LinkedHashSet<HexCoord>()
        for (dq in -radius..radius) {
            val minR = maxOf(-radius, -dq - radius)
            val maxR = minOf(radius, -dq + radius)
            for (dr in minR..maxR) {
                result += HexCoord(q + dq, r + dr)
            }
        }
        return result
    }

    fun center(): LatLon {
        val (cx, cy) = centerMercator()
        return mercatorToLatLon(cx, cy)
    }

    fun vertices(): List<LatLon> {
        val (cx, cy) = centerMercator()
        return List(6) { index ->
            val angle = Math.toRadians(60.0 * index - 30.0)
            val vx = cx + HEX_MERCATOR_RADIUS * cos(angle)
            val vy = cy + HEX_MERCATOR_RADIUS * sin(angle)
            mercatorToLatLon(vx, vy)
        }
    }

    private fun centerMercator(): Pair<Double, Double> {
        val cx = HEX_MERCATOR_RADIUS * SQRT_3 * (q + r / 2.0)
        val cy = HEX_MERCATOR_RADIUS * 1.5 * r
        return cx to cy
    }

    companion object {
        /** Mercator meters; ~180 m real radius at 55°N (Moscow / Novosibirsk). */
        const val HEX_MERCATOR_RADIUS = 320.0
        private const val EARTH_RADIUS_METERS = 6_378_137.0
        private const val MAX_LAT = 85.0
        private val SQRT_3 = sqrt(3.0)

        private val NEIGHBOR_OFFSETS = listOf(
            1 to 0,
            1 to -1,
            0 to -1,
            -1 to 0,
            -1 to 1,
            0 to 1,
        )

        fun localRadiusMeters(latitude: Double): Double =
            HEX_MERCATOR_RADIUS * cos(Math.toRadians(latitude.coerceIn(-MAX_LAT, MAX_LAT)))

        fun fromLatLon(latitude: Double, longitude: Double): HexCoord {
            val (mx, my) = latLonToMercator(latitude, longitude)
            val qf = ((SQRT_3 / 3.0) * mx - (1.0 / 3.0) * my) / HEX_MERCATOR_RADIUS
            val rf = ((2.0 / 3.0) * my) / HEX_MERCATOR_RADIUS
            return axialRound(qf, rf)
        }

        fun parseKey(key: String): HexCoord? {
            val colon = key.indexOf(':')
            if (colon <= 0 || colon >= key.lastIndex) return null
            val q = key.substring(0, colon).toIntOrNull() ?: return null
            val r = key.substring(colon + 1).toIntOrNull() ?: return null
            return HexCoord(q, r)
        }

        private fun latLonToMercator(latitude: Double, longitude: Double): Pair<Double, Double> {
            val lat = latitude.coerceIn(-MAX_LAT, MAX_LAT)
            val lon = normalizeLongitude(longitude)
            val mx = EARTH_RADIUS_METERS * Math.toRadians(lon)
            val my = EARTH_RADIUS_METERS * ln(tan(PI / 4.0 + Math.toRadians(lat) / 2.0))
            return mx to my
        }

        private fun mercatorToLatLon(mx: Double, my: Double): LatLon {
            val lon = normalizeLongitude(Math.toDegrees(mx / EARTH_RADIUS_METERS))
            val lat = Math.toDegrees(2.0 * atan(exp(my / EARTH_RADIUS_METERS)) - PI / 2.0)
                .coerceIn(-MAX_LAT, MAX_LAT)
            return LatLon(lat, lon)
        }

        private fun normalizeLongitude(longitude: Double): Double {
            var normalized = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            if (normalized == -180.0 && longitude > 0.0) normalized = 180.0
            return normalized
        }

        private fun axialRound(qf: Double, rf: Double): HexCoord {
            val sf = -qf - rf
            var rq = round(qf)
            var rr = round(rf)
            val rs = round(sf)

            val qDiff = abs(rq - qf)
            val rDiff = abs(rr - rf)
            val sDiff = abs(rs - sf)

            if (qDiff > rDiff && qDiff > sDiff) {
                rq = -rr - rs
            } else if (rDiff > sDiff) {
                rr = -rq - rs
            }
            return HexCoord(rq.toInt(), rr.toInt())
        }
    }
}

@Serializable
enum class ZoneKind {
    /** Ordinary servers fail here while mobile-list relays carry traffic. */
    Whitelist,

    /** First ordinary failure seen; being watched before promoting to Whitelist. */
    Suspect,

    /** Neither ordinary nor mobile servers pass traffic (radio blackout / heavy jamming). */
    DeadSpot,

    /** Ordinary cellular servers work normally here. */
    Healthy,
}

@Serializable
data class GeoCellRecord(
    val hex: HexCoord,
    val ordinaryFailures: Int = 0,
    val ordinarySuccesses: Int = 0,
    val mobileSuccesses: Int = 0,
    val totalBlackouts: Int = 0,
    val consecutiveOrdinarySuccesses: Int = 0,
    /** Node id -> best measured latency (ms) while inside this cell. */
    val workingMobileNodeIds: Map<String, Int> = emptyMap(),
    /** Cellular base station identifiers (TAC+CI packed) observed in this cell. */
    val cellTowerIds: Set<Long> = emptySet(),
    val pinned: Boolean = false,
    val customName: String? = null,
    val lastUpdatedEpochMillis: Long = 0L,
) {
    fun kind(): ZoneKind = when {
        pinned -> ZoneKind.Whitelist
        ordinaryFailures >= 1 && mobileSuccesses >= 1 -> ZoneKind.Whitelist
        ordinaryFailures >= 2 && ordinaryFailures > ordinarySuccesses -> ZoneKind.Whitelist
        totalBlackouts >= 2 && mobileSuccesses == 0 && ordinarySuccesses == 0 -> ZoneKind.DeadSpot
        ordinaryFailures == 1 -> ZoneKind.Suspect
        else -> ZoneKind.Healthy
    }

    fun isAnomaly(): Boolean = kind() != ZoneKind.Healthy
}

@Serializable
data class GeoBreadcrumb(
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long,
    val onMobileList: Boolean,
    val failed: Boolean = false,
)

@Serializable
data class TowerAnchor(
    val towerId: Long,
    val latitude: Double,
    val longitude: Double,
    val samples: Int = 1,
    val lastUpdatedEpochMillis: Long = 0L,
) {
    fun updateWith(newLat: Double, newLon: Double, nowEpochMillis: Long): TowerAnchor {
        val weight = samples.coerceIn(1, MAX_RUNNING_SAMPLES)
        val nextSamples = (samples + 1).coerceAtMost(1_000)
        val blendedLat = (latitude * weight + newLat) / (weight + 1)
        val blendedLon = (longitude * weight + newLon) / (weight + 1)
        return copy(
            latitude = blendedLat,
            longitude = blendedLon,
            samples = nextSamples,
            lastUpdatedEpochMillis = nowEpochMillis,
        )
    }

    companion object {
        private const val MAX_RUNNING_SAMPLES = 20
    }
}

data class ObservedCellTower(
    val towerId: Long,
    val registered: Boolean,
    val dbm: Int,
)

@Serializable
data class WifiAnchor(
    val networkKey: String,
    val ssidLabel: String? = null,
    val latitude: Double,
    val longitude: Double,
    val connectCount: Int = 1,
    val lastConnectedEpochMillis: Long = 0L,
) {
    val isFrequentHome: Boolean
        get() = connectCount >= GeoZoneEngine.HOME_WIFI_MIN_CONNECTS

    fun updateWith(
        newLat: Double?,
        newLon: Double?,
        newSsidLabel: String?,
        incrementConnect: Boolean,
        nowEpochMillis: Long,
    ): WifiAnchor {
        val nextCount = if (incrementConnect) (connectCount + 1).coerceAtMost(10_000) else connectCount
        val weight = connectCount.coerceIn(1, MAX_RUNNING_SAMPLES)
        val blendedLat = if (newLat != null) (latitude * weight + newLat) / (weight + 1) else latitude
        val blendedLon = if (newLon != null) (longitude * weight + newLon) / (weight + 1) else longitude
        return copy(
            ssidLabel = newSsidLabel?.takeIf { it.isNotBlank() } ?: ssidLabel,
            latitude = blendedLat,
            longitude = blendedLon,
            connectCount = nextCount,
            lastConnectedEpochMillis = nowEpochMillis,
        )
    }

    companion object {
        private const val MAX_RUNNING_SAMPLES = 20
    }
}

@Serializable
data class GeoZoneStoreState(
    val cells: Map<String, GeoCellRecord> = emptyMap(),
    val breadcrumbs: List<GeoBreadcrumb> = emptyList(),
    val towerAnchors: Map<Long, TowerAnchor> = emptyMap(),
    val wifiAnchors: Map<String, WifiAnchor> = emptyMap(),
)

data class GeoZoneCluster(
    val id: String,
    val kind: ZoneKind,
    val cells: List<GeoCellRecord>,
    val hexSet: Set<HexCoord>,
    val approachRing: Set<HexCoord>,
    val center: LatLon,
    val approximateRadiusMeters: Int,
    /** Mobile node ids proven inside this cluster, best latency first. */
    val workingNodeIds: List<String>,
    val bestLatencies: Map<String, Int>,
    val cellTowerIds: Set<Long>,
    val ordinaryFailures: Int,
    val mobileSuccesses: Int,
    val pinned: Boolean,
    val customName: String?,
    val lastUpdatedEpochMillis: Long,
)

data class GeoSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val speedMetersPerSecond: Float,
    val bearingDegrees: Float,
    val elapsedRealtimeMillis: Long,
    val isMock: Boolean = false,
    val cellTowerId: Long? = null,
)

sealed interface ZoneProximity {
    data object Outside : ZoneProximity

    data class Approaching(
        val cluster: GeoZoneCluster,
        val distanceMeters: Int,
        val predictedByVelocity: Boolean,
    ) : ZoneProximity

    data class Inside(
        val cluster: GeoZoneCluster,
        val matchedByCellTower: Boolean,
    ) : ZoneProximity
}

/**
 * Pure clustering, anti-spoofing, and proximity rules for geo-zones.
 */
object GeoZoneEngine {

    /** Consecutive ordinary successes in an unpinned cell required to heal an old anomaly. */
    const val HEAL_ORDINARY_STREAK = 3

    /** ~200 km/h speed limit above which a sudden teleport jump is flagged as GPS/EW spoofing. */
    const val MAX_PLAUSIBLE_SPEED_MPS = 55.0

    /** Minimum displacement before speed-based anti-spoofing rejects a jump. */
    const val SPOOF_JUMP_METERS = 1_500.0

    /** Look-ahead horizon used to detect approaching a zone from velocity vector. */
    const val LOOKAHEAD_SECONDS = 22.0

    /** Maximum allowed distance between satellite GPS and cellular/Wi-Fi network location. */
    const val MAX_GPS_NETWORK_DIVERGENCE_METERS = 2_500.0

    /** Maximum allowed distance between satellite GPS and anchored serving cell tower. */
    const val MAX_GPS_TOWER_DIVERGENCE_METERS = 3_500.0

    /** How long a network-provider fix stays trusted for cross-checking satellite GPS. */
    const val NETWORK_FIX_MAX_AGE_MILLIS = 180_000L

    /** Connections to the same Wi-Fi network needed to classify it as a frequent home anchor. */
    const val HOME_WIFI_MIN_CONNECTS = 3

    /** Maximum allowed distance between satellite GPS and connected/just-left Wi-Fi anchor. */
    const val MAX_GPS_WIFI_ANCHOR_DIVERGENCE_METERS = 1_800.0

    /** Grace window after disconnecting from Wi-Fi where handover fixes are still anchored. */
    const val HOME_WIFI_HANDOVER_GRACE_MILLIS = 90_000L

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val MAX_PREDICTION_SPEED_MPS = 35.0

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val sinHalfPhi = sin(dPhi / 2.0)
        val sinHalfLambda = sin(dLambda / 2.0)
        val a = sinHalfPhi * sinHalfPhi + cos(phi1) * cos(phi2) * sinHalfLambda * sinHalfLambda
        val c = 2.0 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
        return EARTH_RADIUS_METERS * c
    }

    fun isSpoofedOrJumped(previous: GeoSample?, current: GeoSample): Boolean {
        if (current.isMock) return true
        if (current.accuracyMeters > 1000f) return true
        if (previous == null) return false

        val dtSeconds = (current.elapsedRealtimeMillis - previous.elapsedRealtimeMillis) / 1000.0
        if (dtSeconds !in 0.5..180.0) return false
        val distance = haversineMeters(
            previous.latitude,
            previous.longitude,
            current.latitude,
            current.longitude,
        )
        return distance > SPOOF_JUMP_METERS && (distance / dtSeconds) > MAX_PLAUSIBLE_SPEED_MPS
    }

    /**
     * Detects GPS/EW spoofing even on cold start by comparing satellite GPS fixes against the
     * recent cellular/Wi-Fi network fix, active/recent Wi-Fi anchor, and the stored location of the
     * serving cell tower.
     */
    fun isSpoofedAgainstNetworkOrTower(
        candidate: GeoSample,
        isSatelliteProvider: Boolean,
        recentNetworkCoord: LatLon?,
        networkAgeMillis: Long,
        towerAnchors: Map<Long, TowerAnchor>,
        activeWifiAnchor: WifiAnchor? = null,
    ): Boolean {
        if (candidate.isMock || candidate.accuracyMeters > 1000f) return true
        if (!isSatelliteProvider) return false

        if (activeWifiAnchor != null) {
            val wifiDist = haversineMeters(
                candidate.latitude,
                candidate.longitude,
                activeWifiAnchor.latitude,
                activeWifiAnchor.longitude,
            )
            if (wifiDist > MAX_GPS_WIFI_ANCHOR_DIVERGENCE_METERS) return true
        }

        if (recentNetworkCoord != null && networkAgeMillis in 0L..NETWORK_FIX_MAX_AGE_MILLIS) {
            val divergence = haversineMeters(
                candidate.latitude,
                candidate.longitude,
                recentNetworkCoord.latitude,
                recentNetworkCoord.longitude,
            )
            if (divergence > MAX_GPS_NETWORK_DIVERGENCE_METERS) return true
        }

        val towerId = candidate.cellTowerId ?: return false
        val anchor = towerAnchors[towerId] ?: return false
        val towerDist = haversineMeters(
            candidate.latitude,
            candidate.longitude,
            anchor.latitude,
            anchor.longitude,
        )
        return towerDist > MAX_GPS_TOWER_DIVERGENCE_METERS
    }

    /**
     * Returns true when [hex] hosts a learned frequent ("home") Wi-Fi anchor so transient doorway
     * or indoor cellular handshakes do not falsely paint the user's home as a drop zone.
     */
    fun isProtectedByHomeWifi(hex: HexCoord, wifiAnchors: Collection<WifiAnchor>): Boolean =
        wifiAnchors.any { anchor ->
            anchor.isFrequentHome && HexCoord.fromLatLon(anchor.latitude, anchor.longitude) == hex
        }

    /**
     * Estimates device location from visible cell towers weighted by registration and dBm signal
     * strength when satellite GPS is jammed or spoofed.
     */
    fun triangulateByTowers(
        observedTowers: List<ObservedCellTower>,
        towerAnchors: Map<Long, TowerAnchor>,
    ): LatLon? {
        if (observedTowers.isEmpty() || towerAnchors.isEmpty()) return null
        var sumLat = 0.0
        var sumLon = 0.0
        var sumWeight = 0.0
        for (observed in observedTowers) {
            val anchor = towerAnchors[observed.towerId] ?: continue
            val clampedDbm = observed.dbm.coerceIn(-120, -50)
            val signalWeight = 1.0 + (clampedDbm + 120).toDouble() / 15.0
            val regBonus = if (observed.registered) 2.5 else 1.0
            val weight = signalWeight * regBonus
            sumLat += anchor.latitude * weight
            sumLon += anchor.longitude * weight
            sumWeight += weight
        }
        if (sumWeight <= 0.0) return null
        return LatLon(sumLat / sumWeight, sumLon / sumWeight)
    }

    fun predictLookAhead(
        previous: GeoSample?,
        current: GeoSample,
        horizonSeconds: Double = LOOKAHEAD_SECONDS,
    ): LatLon {
        if (horizonSeconds <= 0.0) return LatLon(current.latitude, current.longitude)

        var speed = 0.0
        var bearingRad = 0.0

        if (current.speedMetersPerSecond > 0.8f && current.bearingDegrees.isFinite()) {
            speed = current.speedMetersPerSecond.toDouble()
            bearingRad = Math.toRadians(current.bearingDegrees.toDouble())
        } else if (previous != null) {
            val dt = (current.elapsedRealtimeMillis - previous.elapsedRealtimeMillis) / 1000.0
            if (dt in 0.5..120.0) {
                val dist = haversineMeters(
                    previous.latitude,
                    previous.longitude,
                    current.latitude,
                    current.longitude,
                )
                val derivedSpeed = dist / dt
                if (derivedSpeed in 0.8..MAX_PLAUSIBLE_SPEED_MPS) {
                    speed = derivedSpeed
                    bearingRad = bearingRadians(
                        previous.latitude,
                        previous.longitude,
                        current.latitude,
                        current.longitude,
                    )
                }
            }
        }

        val clampedSpeed = speed.coerceIn(0.0, MAX_PREDICTION_SPEED_MPS)
        if (clampedSpeed <= 0.0) return LatLon(current.latitude, current.longitude)

        val distance = clampedSpeed * horizonSeconds
        val angularDistance = distance / EARTH_RADIUS_METERS
        val lat1 = Math.toRadians(current.latitude)
        val lon1 = Math.toRadians(current.longitude)

        val lat2 = asin(
            (sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearingRad))
                .coerceIn(-1.0, 1.0),
        )
        val lon2 = lon1 + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(lat1),
            cos(angularDistance) - sin(lat1) * sin(lat2),
        )
        return LatLon(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    fun clusterCells(cells: Collection<GeoCellRecord>): List<GeoZoneCluster> {
        val anomalies = cells.filter { it.isAnomaly() }.associateBy { it.hex }
        if (anomalies.isEmpty()) return emptyList()

        val visited = HashSet<HexCoord>(anomalies.size)
        val clusters = ArrayList<GeoZoneCluster>()

        for (startHex in anomalies.keys) {
            if (!visited.add(startHex)) continue

            val queue = ArrayDeque<HexCoord>()
            val component = ArrayList<GeoCellRecord>()
            queue.addLast(startHex)

            while (queue.isNotEmpty()) {
                val hex = queue.removeFirst()
                val record = anomalies[hex] ?: continue
                component += record
                for (neighbor in hex.neighbors()) {
                    if (neighbor in anomalies && visited.add(neighbor)) {
                        queue.addLast(neighbor)
                    }
                }
            }

            clusters += buildCluster(component)
        }

        return clusters.sortedWith(
            compareBy<GeoZoneCluster>(
                { kindPriority(it.kind) },
                { -it.lastUpdatedEpochMillis },
                { it.id },
            ),
        )
    }

    fun evaluateProximity(
        fix: GeoSample?,
        lastValidCoord: LatLon?,
        clusters: List<GeoZoneCluster>,
        previousFix: GeoSample? = null,
    ): ZoneProximity {
        if (clusters.isEmpty()) return ZoneProximity.Outside

        val activeCoord = when {
            fix != null && !isSpoofedOrJumped(previousFix, fix) -> LatLon(fix.latitude, fix.longitude)
            else -> lastValidCoord
        }
        val currentHex = activeCoord?.let { HexCoord.fromLatLon(it.latitude, it.longitude) }

        if (currentHex != null) {
            val insideByHex = clusters
                .filter { currentHex in it.hexSet }
                .minByOrNull { kindPriority(it.kind) }
            if (insideByHex != null) {
                return ZoneProximity.Inside(insideByHex, matchedByCellTower = false)
            }
        }

        val towerId = fix?.cellTowerId
        if (towerId != null) {
            val towerMatched = clusters
                .filter { (it.kind == ZoneKind.Whitelist || it.kind == ZoneKind.Suspect) && towerId in it.cellTowerIds }
                .minWithOrNull(
                    compareBy<GeoZoneCluster>({ kindPriority(it.kind) }).thenBy { cluster ->
                        if (activeCoord == null) 0.0
                        else haversineMeters(
                            activeCoord.latitude,
                            activeCoord.longitude,
                            cluster.center.latitude,
                            cluster.center.longitude,
                        )
                    },
                )
            if (towerMatched != null) {
                return ZoneProximity.Inside(towerMatched, matchedByCellTower = true)
            }
        }

        val coord = activeCoord ?: return ZoneProximity.Outside
        val hex = currentHex ?: return ZoneProximity.Outside

        val actionable = clusters.filter { it.kind == ZoneKind.Whitelist || it.kind == ZoneKind.Suspect }
        if (actionable.isEmpty()) return ZoneProximity.Outside

        val ringCandidate = actionable
            .filter { hex in it.approachRing }
            .minWithOrNull(
                compareBy<GeoZoneCluster>({ kindPriority(it.kind) }).thenBy {
                    haversineMeters(coord.latitude, coord.longitude, it.center.latitude, it.center.longitude)
                },
            )
        if (ringCandidate != null) {
            val dist = distanceToClusterEdge(coord, ringCandidate)
            return ZoneProximity.Approaching(ringCandidate, dist, predictedByVelocity = false)
        }

        if (fix != null && !isSpoofedOrJumped(previousFix, fix)) {
            val midAhead = predictLookAhead(previousFix, fix, LOOKAHEAD_SECONDS * 0.5)
            val farAhead = predictLookAhead(previousFix, fix, LOOKAHEAD_SECONDS)
            val midHex = HexCoord.fromLatLon(midAhead.latitude, midAhead.longitude)
            val farHex = HexCoord.fromLatLon(farAhead.latitude, farAhead.longitude)
            val candidateHexes = listOf(midHex, farHex).filter { it != hex }
            if (candidateHexes.isNotEmpty()) {
                val predicted = actionable
                    .filter { cluster ->
                        candidateHexes.any { candidate ->
                            candidate in cluster.hexSet || candidate in cluster.approachRing
                        }
                    }
                    .minWithOrNull(
                        compareBy<GeoZoneCluster>({ kindPriority(it.kind) }).thenBy {
                            haversineMeters(coord.latitude, coord.longitude, it.center.latitude, it.center.longitude)
                        },
                    )
                if (predicted != null) {
                    val dist = distanceToClusterEdge(coord, predicted)
                    return ZoneProximity.Approaching(predicted, dist, predictedByVelocity = true)
                }
            }
        }

        return ZoneProximity.Outside
    }

    private fun buildCluster(component: List<GeoCellRecord>): GeoZoneCluster {
        val hexSet = component.mapTo(LinkedHashSet(component.size)) { it.hex }
        val approachRing = LinkedHashSet<HexCoord>()
        for (hex in hexSet) {
            for (neighbor in hex.neighbors()) {
                if (neighbor !in hexSet) approachRing += neighbor
            }
        }

        val kind = when {
            component.any { it.kind() == ZoneKind.Whitelist } -> ZoneKind.Whitelist
            component.any { it.kind() == ZoneKind.DeadSpot } -> ZoneKind.DeadSpot
            else -> ZoneKind.Suspect
        }

        val centers = component.map { it.hex.center() }
        val centerLat = centers.sumOf { it.latitude } / centers.size
        val centerLon = centers.sumOf { it.longitude } / centers.size
        val center = LatLon(centerLat, centerLon)

        val cellRadius = HexCoord.localRadiusMeters(centerLat)
        val maxDist = centers.maxOfOrNull { haversineMeters(centerLat, centerLon, it.latitude, it.longitude) } ?: 0.0
        val approximateRadiusMeters = (maxDist + cellRadius).roundToInt()

        val bestLatencies = LinkedHashMap<String, Int>()
        val towerIds = LinkedHashSet<Long>()
        var ordinaryFailures = 0
        var mobileSuccesses = 0
        var pinned = false
        var customName: String? = null
        var lastUpdated = 0L

        for (cell in component) {
            ordinaryFailures += cell.ordinaryFailures
            mobileSuccesses += cell.mobileSuccesses
            pinned = pinned || cell.pinned
            if (customName == null && !cell.customName.isNullOrBlank()) customName = cell.customName
            if (cell.lastUpdatedEpochMillis > lastUpdated) lastUpdated = cell.lastUpdatedEpochMillis
            towerIds.addAll(cell.cellTowerIds)
            for ((nodeId, millis) in cell.workingMobileNodeIds) {
                val existing = bestLatencies[nodeId]
                if (existing == null || millis < existing) {
                    bestLatencies[nodeId] = millis
                }
            }
        }

        val sortedWorking = bestLatencies.entries
            .sortedBy { it.value }
            .map { it.key }

        val anchorHex = component.minWith(compareBy<GeoCellRecord>({ it.hex.q }, { it.hex.r })).hex
        return GeoZoneCluster(
            id = "zone-${anchorHex.key}",
            kind = kind,
            cells = component,
            hexSet = hexSet,
            approachRing = approachRing,
            center = center,
            approximateRadiusMeters = approximateRadiusMeters,
            workingNodeIds = sortedWorking,
            bestLatencies = bestLatencies,
            cellTowerIds = towerIds,
            ordinaryFailures = ordinaryFailures,
            mobileSuccesses = mobileSuccesses,
            pinned = pinned,
            customName = customName,
            lastUpdatedEpochMillis = lastUpdated,
        )
    }

    private fun distanceToClusterEdge(coord: LatLon, cluster: GeoZoneCluster): Int {
        val nearestCellCenter = cluster.cells
            .map { it.hex.center() }
            .minOfOrNull { haversineMeters(coord.latitude, coord.longitude, it.latitude, it.longitude) }
            ?: return 0
        val cellRadius = HexCoord.localRadiusMeters(coord.latitude)
        return (nearestCellCenter - cellRadius).coerceAtLeast(0.0).roundToInt()
    }

    private fun kindPriority(kind: ZoneKind): Int = when (kind) {
        ZoneKind.Whitelist -> 0
        ZoneKind.Suspect -> 1
        ZoneKind.DeadSpot -> 2
        ZoneKind.Healthy -> 3
    }

    private fun bearingRadians(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return atan2(y, x)
    }
}
