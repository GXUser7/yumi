package com.mydrop.vpn.core.model

import com.mydrop.vpn.data.GeoZoneRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeoZonesTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `hex round-trip and neighbor geometry hold across longitudes`() {
        val cities = listOf(
            LatLon(55.7512, 37.6184), // Moscow
            LatLon(59.9343, 30.3351), // Saint Petersburg
            LatLon(55.0084, 82.9357), // Novosibirsk
            LatLon(43.1155, 131.8855), // Vladivostok
        )

        for (city in cities) {
            val hex = HexCoord.fromLatLon(city.latitude, city.longitude)
            val center = hex.center()
            val roundTrip = HexCoord.fromLatLon(center.latitude, center.longitude)
            assertEquals("center round-trip failed for $city", hex, roundTrip)
            assertEquals("parseKey failed for ${hex.key}", hex, HexCoord.parseKey(hex.key))

            val distFromPointToCenter = GeoZoneEngine.haversineMeters(
                city.latitude,
                city.longitude,
                center.latitude,
                center.longitude,
            )
            assertTrue(
                "center too far ($distFromPointToCenter m) from $city",
                distFromPointToCenter <= HexCoord.localRadiusMeters(city.latitude) * 1.05,
            )

            val neighbors = hex.neighbors()
            assertEquals(6, neighbors.size)
            for (neighbor in neighbors) {
                assertEquals(1, hex.distanceTo(neighbor))
                val neighborDist = GeoZoneEngine.haversineMeters(
                    center.latitude,
                    center.longitude,
                    neighbor.center().latitude,
                    neighbor.center().longitude,
                )
                // Adjacent pointy-top hex centers sit sqrt(3) * localRadius apart.
                val expectedCenterDist = kotlin.math.sqrt(3.0) * HexCoord.localRadiusMeters(city.latitude)
                assertTrue(
                    "neighbor spacing distorted at $city: actual=$neighborDist expected=$expectedCenterDist",
                    kotlin.math.abs(neighborDist - expectedCenterDist) < expectedCenterDist * 0.08,
                )
            }

            assertEquals(6, hex.vertices().size)
            assertEquals(7, hex.ring(1).size)
        }
    }

    @Test
    fun `anti-spoofing rejects mock, coarse, and EW jump fixes while allowing driving`() {
        val t0 = 100_000L
        val moscowCenter = GeoSample(
            latitude = 55.7512,
            longitude = 37.6184,
            accuracyMeters = 15f,
            speedMetersPerSecond = 0f,
            bearingDegrees = 0f,
            elapsedRealtimeMillis = t0,
        )

        // Normal city drive: ~600m north in 30s (20 m/s = 72 km/h) -> valid.
        val normalDrive = GeoSample(
            latitude = 55.7566,
            longitude = 37.6184,
            accuracyMeters = 18f,
            speedMetersPerSecond = 20f,
            bearingDegrees = 0f,
            elapsedRealtimeMillis = t0 + 30_000L,
        )
        assertFalse(GeoZoneEngine.isSpoofedOrJumped(moscowCenter, normalDrive))

        // EW teleport jump to Sheremetyevo (~28 km away) in 5s -> rejected.
        val spoofedJump = GeoSample(
            latitude = 55.9726,
            longitude = 37.4146,
            accuracyMeters = 12f,
            speedMetersPerSecond = 0f,
            bearingDegrees = 0f,
            elapsedRealtimeMillis = t0 + 5_000L,
        )
        assertTrue(GeoZoneEngine.isSpoofedOrJumped(moscowCenter, spoofedJump))

        // Mock provider or terrible accuracy (> 1000m) -> rejected.
        assertTrue(GeoZoneEngine.isSpoofedOrJumped(moscowCenter, normalDrive.copy(isMock = true)))
        assertTrue(GeoZoneEngine.isSpoofedOrJumped(moscowCenter, normalDrive.copy(accuracyMeters = 1500f)))
    }

    @Test
    fun `clustering merges adjacent anomaly hexes, builds approach ring, and sorts nodes`() {
        val hexA = HexCoord.fromLatLon(55.7512, 37.6184)
        val hexB = hexA.neighbors().first()
        val hexSeparate = HexCoord(hexA.q + 10, hexA.r + 10)
        val hexHealthy = HexCoord(hexA.q - 5, hexA.r)

        val cellA = GeoCellRecord(
            hex = hexA,
            ordinaryFailures = 2,
            mobileSuccesses = 3,
            workingMobileNodeIds = mapOf("node-slow" to 240, "node-fast" to 65),
            cellTowerIds = setOf(1001L),
            lastUpdatedEpochMillis = 1000L,
        )
        val cellB = GeoCellRecord(
            hex = hexB,
            ordinaryFailures = 1,
            mobileSuccesses = 1,
            workingMobileNodeIds = mapOf("node-fast" to 52, "node-mid" to 110),
            cellTowerIds = setOf(1002L),
            lastUpdatedEpochMillis = 2000L,
        )
        val cellSeparate = GeoCellRecord(
            hex = hexSeparate,
            totalBlackouts = 3,
            lastUpdatedEpochMillis = 500L,
        )
        val cellHealthy = GeoCellRecord(
            hex = hexHealthy,
            ordinarySuccesses = 5,
            consecutiveOrdinarySuccesses = 5,
        )

        val clusters = GeoZoneEngine.clusterCells(listOf(cellA, cellB, cellSeparate, cellHealthy))
        assertEquals(2, clusters.size)

        val merged = clusters.first { it.kind == ZoneKind.Whitelist }
        assertEquals(setOf(hexA, hexB), merged.hexSet)
        assertEquals(setOf(1001L, 1002L), merged.cellTowerIds)
        assertEquals(listOf("node-fast", "node-mid", "node-slow"), merged.workingNodeIds)
        assertEquals(52, merged.bestLatencies["node-fast"])
        // Two touching hexes share 2 neighbors -> 6 + 6 - 2 (shared) - 2 (each other) = 8 ring hexes.
        assertEquals(8, merged.approachRing.size)
        assertFalse(hexA in merged.approachRing)
        assertFalse(hexB in merged.approachRing)

        val dead = clusters.first { it.kind == ZoneKind.DeadSpot }
        assertEquals(setOf(hexSeparate), dead.hexSet)
    }

    @Test
    fun `proximity detects inside by hex, inside by cell tower under spoofing, approaching by ring and velocity, and outside`() {
        val zoneHex = HexCoord.fromLatLon(55.7512, 37.6184)
        val zoneCenter = zoneHex.center()
        val towerId = 987654321L
        val cluster = GeoZoneEngine.clusterCells(
            listOf(
                GeoCellRecord(
                    hex = zoneHex,
                    ordinaryFailures = 2,
                    mobileSuccesses = 2,
                    cellTowerIds = setOf(towerId),
                ),
            ),
        ).single()

        // 1. Inside by GPS hex
        val insideFix = GeoSample(zoneCenter.latitude, zoneCenter.longitude, 12f, 0f, 0f, 10_000L)
        val inside = GeoZoneEngine.evaluateProximity(insideFix, zoneCenter, listOf(cluster))
        assertTrue(inside is ZoneProximity.Inside && !inside.matchedByCellTower)

        // 2. GPS spoofed to airport 25km away, but serving cell tower matches the whitelist cluster
        val farSpoofFix = GeoSample(
            latitude = zoneCenter.latitude + 0.25,
            longitude = zoneCenter.longitude + 0.25,
            accuracyMeters = 10f,
            speedMetersPerSecond = 0f,
            bearingDegrees = 0f,
            elapsedRealtimeMillis = 15_000L,
            cellTowerId = towerId,
        )
        val outsideValidAnchor = zoneHex.neighbors().first().neighbors().first().center()
        val towerMatched = GeoZoneEngine.evaluateProximity(
            fix = farSpoofFix,
            lastValidCoord = outsideValidAnchor,
            clusters = listOf(cluster),
            previousFix = GeoSample(outsideValidAnchor.latitude, outsideValidAnchor.longitude, 10f, 0f, 0f, 10_000L),
        )
        assertTrue(towerMatched is ZoneProximity.Inside && towerMatched.matchedByCellTower)

        // 3. Approaching via 1-ring buffer
        val ringHex = cluster.approachRing.first()
        val ringCoord = ringHex.center()
        val ringFix = GeoSample(ringCoord.latitude, ringCoord.longitude, 15f, 0f, 0f, 20_000L)
        val approachingRing = GeoZoneEngine.evaluateProximity(ringFix, ringCoord, listOf(cluster))
        assertTrue(approachingRing is ZoneProximity.Approaching && !approachingRing.predictedByVelocity)

        // 4. Approaching via velocity vector from ~700m south (2+ rings away) moving north at 25 m/s
        val southCoord = LatLon(zoneCenter.latitude - 0.0063, zoneCenter.longitude)
        val northHeadingFix = GeoSample(
            latitude = southCoord.latitude,
            longitude = southCoord.longitude,
            accuracyMeters = 10f,
            speedMetersPerSecond = 25f,
            bearingDegrees = 0f,
            elapsedRealtimeMillis = 25_000L,
        )
        val approachingVel = GeoZoneEngine.evaluateProximity(northHeadingFix, southCoord, listOf(cluster))
        assertTrue(
            "expected velocity approach from $southCoord toward $zoneCenter, got $approachingVel",
            approachingVel is ZoneProximity.Approaching && approachingVel.predictedByVelocity,
        )

        // 5. Completely outside and stationary
        val farCleanCoord = LatLon(zoneCenter.latitude - 0.05, zoneCenter.longitude - 0.05)
        val farFix = GeoSample(farCleanCoord.latitude, farCleanCoord.longitude, 10f, 0f, 0f, 30_000L)
        val outside = GeoZoneEngine.evaluateProximity(farFix, farCleanCoord, listOf(cluster))
        assertEquals(ZoneProximity.Outside, outside)
    }

    @Test
    fun `repository persists zones, heals after ordinary streak, and keeps pinned zones`() {
        val dir = folder.newFolder("georepo")
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val hex = HexCoord.fromLatLon(55.7512, 37.6184)
        try {
            val repo = GeoZoneRepository(dir, scope1)
            repo.recordOrdinaryOutcome(hex, cellTowerId = 42L, passed = false)
            repo.recordMobileOutcome(hex, cellTowerId = 42L, nodeId = "lte-1", millis = 90, passed = true)

            assertEquals(1, repo.clusters.value.size)
            assertEquals(ZoneKind.Whitelist, repo.clusters.value.single().kind)
            assertEquals(listOf("lte-1"), repo.clusters.value.single().workingNodeIds)

            // Self-heal after HEAL_ORDINARY_STREAK consecutive ordinary successes
            repeat(GeoZoneEngine.HEAL_ORDINARY_STREAK - 1) {
                repo.recordOrdinaryOutcome(hex, cellTowerId = 42L, passed = true)
                assertEquals(1, repo.clusters.value.size)
            }
            repo.recordOrdinaryOutcome(hex, cellTowerId = 42L, passed = true)
            assertTrue("expected zone to heal after streak", repo.clusters.value.isEmpty())

            // Manual pinned zone survives ordinary successes and persists across reopen
            repo.addManualZone(55.7512, 37.6184, radiusRings = 1, name = "Центр")
            assertEquals(1, repo.clusters.value.size)
            assertTrue(repo.clusters.value.single().pinned)
            repo.recordOrdinaryOutcome(hex, cellTowerId = null, passed = true)
            repo.recordOrdinaryOutcome(hex, cellTowerId = null, passed = true)
            repo.recordOrdinaryOutcome(hex, cellTowerId = null, passed = true)
            assertEquals(1, repo.clusters.value.size)

            awaitFileContains(File(dir, "geo_zones.json"), "Центр")
        } finally {
            scope1.cancel()
        }

        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = GeoZoneRepository(dir, scope2)
            assertEquals(1, reopened.clusters.value.size)
            val cluster = reopened.clusters.value.single()
            assertEquals("Центр", cluster.customName)
            assertTrue(cluster.pinned)

            reopened.togglePinCluster(cluster.id)
            assertFalse(reopened.clusters.value.single().pinned)

            reopened.removeCluster(cluster.id)
            assertTrue(reopened.clusters.value.isEmpty())
        } finally {
            scope2.cancel()
        }
    }

    @Test
    fun `cell tower anchors reject cold-start fake GPS and triangulate real city position`() {
        val dir = folder.newFolder("toweranchors")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = GeoZoneRepository(dir, scope)
            val towerA = 10001L
            val towerB = 10002L
            repo.recordTowerObservation(towerA, 55.7512, 37.6184, nowEpochMillis = 1_000L)
            repo.recordTowerObservation(towerA, 55.7520, 37.6190, nowEpochMillis = 2_000L)
            repo.recordTowerObservation(towerB, 55.7560, 37.6240, nowEpochMillis = 3_000L)

            val anchors = repo.towerAnchors()
            assertEquals(2, anchors.size)
            assertEquals(2, anchors[towerA]?.samples)

            // Cold-start fake GPS 25 km away in Sheremetyevo, caught by network fix and by tower anchor
            val fakeGps = GeoSample(
                latitude = 55.9726,
                longitude = 37.4146,
                accuracyMeters = 5f,
                speedMetersPerSecond = 0f,
                bearingDegrees = 0f,
                elapsedRealtimeMillis = 50_000L,
                isMock = false,
                cellTowerId = towerA,
            )
            assertTrue(
                GeoZoneEngine.isSpoofedAgainstNetworkOrTower(
                    candidate = fakeGps,
                    isSatelliteProvider = true,
                    recentNetworkCoord = LatLon(55.7515, 37.6188),
                    networkAgeMillis = 10_000L,
                    towerAnchors = emptyMap(),
                ),
            )
            assertTrue(
                GeoZoneEngine.isSpoofedAgainstNetworkOrTower(
                    candidate = fakeGps,
                    isSatelliteProvider = true,
                    recentNetworkCoord = null,
                    networkAgeMillis = 0L,
                    towerAnchors = anchors,
                ),
            )
            // Legitimate GPS fix near the serving tower passes
            val realGps = fakeGps.copy(latitude = 55.7518, longitude = 37.6189)
            assertFalse(
                GeoZoneEngine.isSpoofedAgainstNetworkOrTower(
                    candidate = realGps,
                    isSatelliteProvider = true,
                    recentNetworkCoord = LatLon(55.7515, 37.6188),
                    networkAgeMillis = 10_000L,
                    towerAnchors = anchors,
                ),
            )

            // Signal-weighted triangulation leans strongly toward the registered strong towerA
            val triangulated = GeoZoneEngine.triangulateByTowers(
                observedTowers = listOf(
                    ObservedCellTower(towerA, registered = true, dbm = -72),
                    ObservedCellTower(towerB, registered = false, dbm = -108),
                ),
                towerAnchors = anchors,
            )
            assertNotNull(triangulated)
            val distToA = GeoZoneEngine.haversineMeters(
                triangulated!!.latitude,
                triangulated.longitude,
                anchors[towerA]!!.latitude,
                anchors[towerA]!!.longitude,
            )
            val distToB = GeoZoneEngine.haversineMeters(
                triangulated.latitude,
                triangulated.longitude,
                anchors[towerB]!!.latitude,
                anchors[towerB]!!.longitude,
            )
            assertTrue("expected triangulation closer to stronger registered towerA", distToA < distToB)

            awaitFileContains(File(dir, "geo_zones.json"), "\"10002\":")
        } finally {
            scope.cancel()
        }

        val reopenScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = GeoZoneRepository(dir, reopenScope)
            assertEquals(2, reopened.towerAnchors().size)
        } finally {
            reopenScope.cancel()
        }
    }

    @Test
    fun `frequent wifi anchor learns home after 3 connects, rejects spoofed gps, and prevents false home drop zone`() {
        val dir = folder.newFolder("wifianchors")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val homeLat = 55.7512
        val homeLon = 37.6184
        val homeHex = HexCoord.fromLatLon(homeLat, homeLon)
        try {
            val repo = GeoZoneRepository(dir, scope)
            val key = "bssid:aa:bb:cc:dd:ee:ff"

            val first = repo.recordWifiConnection(key, "HomeNet", homeLat, homeLon, incrementConnect = true, nowEpochMillis = 1_000L)
            assertEquals(1, first.connectCount)
            assertFalse(first.isFrequentHome)

            val second = repo.recordWifiConnection(key, "HomeNet", homeLat, homeLon, incrementConnect = true, nowEpochMillis = 2_000L)
            assertEquals(2, second.connectCount)
            assertFalse(second.isFrequentHome)

            val third = repo.recordWifiConnection(key, "HomeNet", homeLat, homeLon, incrementConnect = true, nowEpochMillis = 3_000L)
            assertEquals(3, third.connectCount)
            assertTrue(third.isFrequentHome)
            assertTrue(GeoZoneEngine.isProtectedByHomeWifi(homeHex, repo.wifiAnchors().values))

            // Satellite GPS spoofed 25 km away while on or right after leaving home Wi-Fi -> rejected.
            val fakeGps = GeoSample(
                latitude = 55.9726,
                longitude = 37.4146,
                accuracyMeters = 8f,
                speedMetersPerSecond = 0f,
                bearingDegrees = 0f,
                elapsedRealtimeMillis = 50_000L,
            )
            assertTrue(
                GeoZoneEngine.isSpoofedAgainstNetworkOrTower(
                    candidate = fakeGps,
                    isSatelliteProvider = true,
                    recentNetworkCoord = null,
                    networkAgeMillis = 0L,
                    towerAnchors = emptyMap(),
                    activeWifiAnchor = third,
                ),
            )

            // Valid fix near home Wi-Fi -> accepted.
            val realGps = fakeGps.copy(latitude = homeLat + 0.0005, longitude = homeLon + 0.0005)
            assertFalse(
                GeoZoneEngine.isSpoofedAgainstNetworkOrTower(
                    candidate = realGps,
                    isSatelliteProvider = true,
                    recentNetworkCoord = null,
                    networkAgeMillis = 0L,
                    towerAnchors = emptyMap(),
                    activeWifiAnchor = third,
                ),
            )

            // Transient ordinary failure right at learned home Wi-Fi hex must not mark home as a drop zone.
            repo.recordOrdinaryOutcome(homeHex, cellTowerId = 777L, passed = false)
            repo.recordOrdinaryOutcome(homeHex, cellTowerId = 777L, passed = false)
            assertTrue("expected home hex to remain free of automatic anomaly clusters", repo.clusters.value.isEmpty())

            awaitFileContains(File(dir, "geo_zones.json"), "\"connectCount\": 3")
        } finally {
            scope.cancel()
        }

        val reopenScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = GeoZoneRepository(dir, reopenScope)
            val anchor = reopened.wifiAnchors()["bssid:aa:bb:cc:dd:ee:ff"]
            assertNotNull(anchor)
            assertTrue(anchor!!.isFrequentHome)
            assertEquals("HomeNet", anchor.ssidLabel)
        } finally {
            reopenScope.cancel()
        }
    }

    @Test
    fun `isFixFresh accepts fresh fixes and rejects stale or future-skewed samples`() {
        val base = GeoSample(55.7512, 37.6184, 15f, 0f, 0f, elapsedRealtimeMillis = 50_000L)
        assertFalse(GeoZoneEngine.isFixFresh(null, nowElapsedMillis = 60_000L))
        assertTrue(GeoZoneEngine.isFixFresh(base, nowElapsedMillis = 50_000L))
        assertTrue(GeoZoneEngine.isFixFresh(base, nowElapsedMillis = 50_000L + GeoZoneEngine.MAX_FIX_AGE_MILLIS))
        assertFalse(GeoZoneEngine.isFixFresh(base, nowElapsedMillis = 50_000L + GeoZoneEngine.MAX_FIX_AGE_MILLIS + 1L))
        assertFalse(GeoZoneEngine.isFixFresh(base, nowElapsedMillis = 49_999L))
    }

    @Test
    fun `isNearActionableZone ignores dead spots and matches radius or cell tower`() {
        val zoneHex = HexCoord.fromLatLon(55.7512, 37.6184)
        val zoneCenter = zoneHex.center()
        val towerId = 555_001L
        val deadSpot = GeoZoneEngine.clusterCells(
            listOf(GeoCellRecord(hex = zoneHex, totalBlackouts = 3)),
        )
        assertFalse(GeoZoneEngine.isNearActionableZone(zoneCenter, towerId, deadSpot))

        val whitelist = GeoZoneEngine.clusterCells(
            listOf(
                GeoCellRecord(
                    hex = zoneHex,
                    ordinaryFailures = 2,
                    mobileSuccesses = 1,
                    cellTowerIds = setOf(towerId),
                ),
            ),
        )
        assertFalse(GeoZoneEngine.isNearActionableZone(zoneCenter, null, emptyList()))

        // ~1.5 km north -> inside PROXIMITY_WATCH_RADIUS_METERS (2.5 km)
        val nearby = LatLon(zoneCenter.latitude + 0.0135, zoneCenter.longitude)
        assertTrue(GeoZoneEngine.isNearActionableZone(nearby, null, whitelist))

        // ~10 km north -> outside radius, unless matched by serving tower
        val far = LatLon(zoneCenter.latitude + 0.09, zoneCenter.longitude)
        assertFalse(GeoZoneEngine.isNearActionableZone(far, null, whitelist))
        assertTrue(GeoZoneEngine.isNearActionableZone(far, towerId, whitelist))
        assertTrue(GeoZoneEngine.isNearActionableZone(null, towerId, whitelist))
        assertFalse(GeoZoneEngine.isNearActionableZone(null, null, whitelist))
    }

    @Test
    fun `selectTrackingTier picks expected power tier across states`() {
        assertEquals(
            GeoTrackingTier.Off,
            GeoZoneEngine.selectTrackingTier(
                enabled = false,
                hasPermission = true,
                vpnConnected = true,
                cellular = true,
                mapOpen = true,
                burstActive = true,
                screenOn = true,
                nearKnownZone = true,
                recentlyMoved = true,
                preemptiveEnabled = true,
            ),
        )
        assertEquals(
            GeoTrackingTier.Off,
            GeoZoneEngine.selectTrackingTier(
                enabled = true,
                hasPermission = false,
                vpnConnected = true,
                cellular = true,
                mapOpen = true,
                burstActive = false,
                screenOn = true,
                nearKnownZone = true,
                recentlyMoved = true,
                preemptiveEnabled = true,
            ),
        )
        // Map screen stays HighAccuracy even on Wi-Fi / disconnected.
        assertEquals(
            GeoTrackingTier.HighAccuracy,
            GeoZoneEngine.selectTrackingTier(
                enabled = true,
                hasPermission = true,
                vpnConnected = false,
                cellular = false,
                mapOpen = true,
                burstActive = false,
                screenOn = true,
                nearKnownZone = false,
                recentlyMoved = false,
                preemptiveEnabled = false,
            ),
        )
        // Disconnected or Wi-Fi with closed map is Off.
        assertEquals(
            GeoTrackingTier.Off,
            GeoZoneEngine.selectTrackingTier(
                enabled = true,
                hasPermission = true,
                vpnConnected = true,
                cellular = false,
                mapOpen = false,
                burstActive = true,
                screenOn = true,
                nearKnownZone = true,
                recentlyMoved = true,
                preemptiveEnabled = true,
            ),
        )
        // Burst on connected cellular -> HighAccuracy.
        assertEquals(
            GeoTrackingTier.HighAccuracy,
            GeoZoneEngine.selectTrackingTier(
                enabled = true,
                hasPermission = true,
                vpnConnected = true,
                cellular = true,
                mapOpen = false,
                burstActive = true,
                screenOn = false,
                nearKnownZone = false,
                recentlyMoved = false,
                preemptiveEnabled = false,
            ),
        )
        // Balanced only when near known zone, preemptive enabled, and screenOn or recentlyMoved.
        assertEquals(
            GeoTrackingTier.Balanced,
            GeoZoneEngine.selectTrackingTier(
                enabled = true,
                hasPermission = true,
                vpnConnected = true,
                cellular = true,
                mapOpen = false,
                burstActive = false,
                screenOn = true,
                nearKnownZone = true,
                recentlyMoved = false,
                preemptiveEnabled = true,
            ),
        )
        assertEquals(
            GeoTrackingTier.Balanced,
            GeoZoneEngine.selectTrackingTier(
                enabled = true,
                hasPermission = true,
                vpnConnected = true,
                cellular = true,
                mapOpen = false,
                burstActive = false,
                screenOn = false,
                nearKnownZone = true,
                recentlyMoved = true,
                preemptiveEnabled = true,
            ),
        )
        // Stationary with screen off near zone, far from zone, or preemptive off -> Dormant.
        assertEquals(
            GeoTrackingTier.Dormant,
            GeoZoneEngine.selectTrackingTier(
                enabled = true,
                hasPermission = true,
                vpnConnected = true,
                cellular = true,
                mapOpen = false,
                burstActive = false,
                screenOn = false,
                nearKnownZone = true,
                recentlyMoved = false,
                preemptiveEnabled = true,
            ),
        )
        assertEquals(
            GeoTrackingTier.Dormant,
            GeoZoneEngine.selectTrackingTier(
                enabled = true,
                hasPermission = true,
                vpnConnected = true,
                cellular = true,
                mapOpen = false,
                burstActive = false,
                screenOn = true,
                nearKnownZone = false,
                recentlyMoved = true,
                preemptiveEnabled = true,
            ),
        )
        assertEquals(
            GeoTrackingTier.Dormant,
            GeoZoneEngine.selectTrackingTier(
                enabled = true,
                hasPermission = true,
                vpnConnected = true,
                cellular = true,
                mapOpen = false,
                burstActive = false,
                screenOn = true,
                nearKnownZone = true,
                recentlyMoved = true,
                preemptiveEnabled = false,
            ),
        )
    }

    @Test
    fun `repository throttles repeated healthy touches while healing anomalies on consecutive passes`() {
        val dir = folder.newFolder("healthythrottle")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val healthyHex = HexCoord.fromLatLon(55.7512, 37.6184)
        val anomalyHex = healthyHex.neighbors().first()
        try {
            val repo = GeoZoneRepository(dir, scope)
            repo.recordOrdinaryOutcome(healthyHex, cellTowerId = 1L, passed = true, nowEpochMillis = 10_000L)
            assertEquals(1, repo.state.value.cells[healthyHex.key]?.ordinarySuccesses)

            // Repeated healthy pass within HEALTHY_TOUCH_INTERVAL_MS is ignored.
            repo.recordOrdinaryOutcome(
                healthyHex,
                cellTowerId = 1L,
                passed = true,
                nowEpochMillis = 10_000L + GeoZoneRepository.HEALTHY_TOUCH_INTERVAL_MS - 1L,
            )
            assertEquals(1, repo.state.value.cells[healthyHex.key]?.ordinarySuccesses)

            // Pass at or past interval updates normally.
            repo.recordOrdinaryOutcome(
                healthyHex,
                cellTowerId = 1L,
                passed = true,
                nowEpochMillis = 10_000L + GeoZoneRepository.HEALTHY_TOUCH_INTERVAL_MS,
            )
            assertEquals(2, repo.state.value.cells[healthyHex.key]?.ordinarySuccesses)

            // Anomaly cells ignore the healthy throttle so healing streak advances immediately.
            repo.recordOrdinaryOutcome(anomalyHex, cellTowerId = 2L, passed = false, nowEpochMillis = 20_000L)
            repo.recordMobileOutcome(anomalyHex, cellTowerId = 2L, nodeId = "mob-1", millis = 50, passed = true, nowEpochMillis = 20_500L)
            assertEquals(1, repo.clusters.value.size)

            repeat(GeoZoneEngine.HEAL_ORDINARY_STREAK) { idx ->
                repo.recordOrdinaryOutcome(
                    anomalyHex,
                    cellTowerId = 2L,
                    passed = true,
                    nowEpochMillis = 21_000L + idx * 1_000L,
                )
            }
            assertTrue("anomaly cell should heal even with 1s steps", repo.clusters.value.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `debounced repository coalesces routine writes and flushes urgently on zone creation`() {
        val dir = folder.newFolder("debouncedrepo")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(dir, "geo_zones.json")
        try {
            val repo = GeoZoneRepository(dir, scope, saveDebounceMillis = 60_000L)
            repo.recordBreadcrumb(55.7512, 37.6184, onMobileList = false, failed = false, timestampMillis = 1_000L)
            repo.recordTowerObservation(999L, 55.7512, 37.6184, nowEpochMillis = 2_000L)
            Thread.sleep(100)
            assertFalse("routine updates should stay debounced", file.exists())

            repo.addManualZone(55.7512, 37.6184, radiusRings = 1, name = "UrgentZone", nowEpochMillis = 3_000L)
            awaitFileContains(file, "UrgentZone")
            assertTrue(file.readText().contains("\"999\":"))
        } finally {
            scope.cancel()
        }
    }

    private fun awaitFileContains(file: File, snippet: String) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val text = runCatching { if (file.exists()) file.readText() else null }.getOrNull()
            if (text != null && text.contains(snippet)) return
            Thread.sleep(20)
        }
        assertNotNull("file ${file.absolutePath} never persisted $snippet", null)
    }
}
