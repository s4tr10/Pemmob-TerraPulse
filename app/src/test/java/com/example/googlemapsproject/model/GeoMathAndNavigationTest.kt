package com.example.googlemapsproject.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathAndNavigationTest {

    private val its = GeoPointData(-7.282356, 112.794925)
    private val tuguPahlawan = GeoPointData(-7.245875, 112.737846)

    @Test
    fun distanceMeters_itsToTuguPahlawan_isAround7Point5Km() {
        val dist = GeoMath.distanceMeters(its, tuguPahlawan)
        assertTrue("Expected ~7500m, got $dist", dist in 7000.0..8000.0)
    }

    @Test
    fun bearingDegrees_cardinalDirections_areAccurate() {
        val origin = GeoPointData(0.0, 0.0)
        val north = GeoPointData(0.01, 0.0)
        val east = GeoPointData(0.0, 0.01)
        val south = GeoPointData(-0.01, 0.0)
        val west = GeoPointData(0.0, -0.01)

        assertEquals(0f, GeoMath.bearingDegrees(origin, north), 1.0f)
        assertEquals(90f, GeoMath.bearingDegrees(origin, east), 1.0f)
        assertEquals(180f, GeoMath.bearingDegrees(origin, south), 1.0f)
        assertEquals(270f, GeoMath.bearingDegrees(origin, west), 1.0f)
    }

    @Test
    fun distanceToPolylineMeters_onSegmentVsOffRoute_calculatesAccurately() {
        val line = listOf(
            GeoPointData(-7.280000, 112.790000),
            GeoPointData(-7.280000, 112.795000)
        )
        val onLine = GeoPointData(-7.280000, 112.792500)
        // ~0.00054 deg latitude is ~60 meters north
        val offLine = GeoPointData(-7.279460, 112.792500)

        assertTrue(GeoMath.distanceToPolylineMeters(onLine, line) < 1.0)
        val offDist = GeoMath.distanceToPolylineMeters(offLine, line)
        assertTrue("Expected ~60m, got $offDist", offDist in 55.0..65.0)
    }

    @Test
    fun interpolateAlongPolyline_movesProportionallyAndClampsAtEnd() {
        val start = GeoPointData(-7.280000, 112.790000)
        val mid = GeoPointData(-7.280000, 112.792000)
        val end = GeoPointData(-7.278000, 112.792000)
        val polyline = listOf(start, mid, end)

        val seg1Len = GeoMath.distanceMeters(start, mid)
        val totalLen = seg1Len + GeoMath.distanceMeters(mid, end)

        val (halfFirstPos, halfFirstBearing) = GeoMath.interpolateAlongPolyline(polyline, seg1Len / 2.0)
        assertEquals(-7.280000, halfFirstPos.lat, 0.0001)
        assertEquals(112.791000, halfFirstPos.lon, 0.0001)
        assertEquals(90f, halfFirstBearing, 2.0f)

        val (endPos, _) = GeoMath.interpolateAlongPolyline(polyline, totalLen + 500.0)
        assertEquals(end.lat, endPos.lat, 0.000001)
        assertEquals(end.lon, endPos.lon, 0.000001)
    }

    @Test
    fun formattingHelpers_produceExpectedIndonesianStrings() {
        assertEquals("85 m", GeoMath.formatDistanceId(85.4))
        assertEquals("4,2 km", GeoMath.formatDistanceId(4200.0))
        assertEquals("< 1 mnt", GeoMath.formatDurationId(40.0))
        assertEquals("14 mnt", GeoMath.formatDurationId(840.0))
        assertEquals("1 j 15 mnt", GeoMath.formatDurationId(4500.0))
        assertEquals("U", GeoMath.cardinalDirectionId(5f))
        assertEquals("T", GeoMath.cardinalDirectionId(92f))
    }

    private fun buildSampleRoute(): RouteResult {
        val p0 = GeoPointData(-7.280000, 112.790000)
        // ~200m east
        val p1 = GeoPointData(-7.280000, 112.791800)
        // ~200m north
        val p2 = GeoPointData(-7.278200, 112.791800)
        val destPlace = PlaceItem("d1", "Tujuan Demo", "Jl. Demo", p2)
        val steps = listOf(
            RouteStep("Mulai perjalanan menuju Jl. Raya", "Jl. Raya", 200.0, 20.0, ManeuverType.DEPART, p0),
            RouteStep("Belok kiri ke Jl. Mawar", "Jl. Mawar", 200.0, 20.0, ManeuverType.LEFT, p1),
            RouteStep("Anda telah tiba di tujuan", "", 0.0, 0.0, ManeuverType.ARRIVE, p2)
        )
        return RouteResult(
            origin = RouteOrigin.CurrentGps,
            destination = destPlace,
            mode = TravelMode.CAR,
            points = listOf(p0, p1, p2),
            distanceMeters = 400.0,
            durationSeconds = 40.0,
            steps = steps
        )
    }

    @Test
    fun navigationEngine_stepAdvancementTtsOneShotAndArrival() {
        val route = buildSampleRoute()
        val engine = NavigationEngine(route)

        // Start near p0 -> upcoming maneuver should be step 1 (LEFT at p1), distance ~200m (>150m, no speech)
        val startEval = engine.evaluate(route.points[0], isSimulated = false, nowMs = 1000L)
        assertEquals(ManeuverType.LEFT, startEval.progress.currentStep.maneuverType)
        assertNull(startEval.progress.speechCue)
        assertFalse(startEval.progress.hasArrived)

        // Move ~100m east -> within 150m of p1 -> approach TTS fires once
        val at100mFromTurn = GeoPointData(-7.280000, 112.790900)
        val approach1 = engine.evaluate(at100mFromTurn, isSimulated = false, nowMs = 2000L)
        assertNotNull(approach1.progress.speechCue)
        assertTrue(approach1.progress.speechCue!!.contains("150 meter"))

        // Second tick still between 35m and 150m -> no duplicate approach TTS
        val at80mFromTurn = GeoPointData(-7.280000, 112.791100)
        val approach2 = engine.evaluate(at80mFromTurn, isSimulated = false, nowMs = 3000L)
        assertNull(approach2.progress.speechCue)

        // Within 35m of p1 (but >25m so not yet advanced) -> immediate TTS fires once
        val at30mFromTurn = GeoPointData(-7.280000, 112.791530)
        val immediate = engine.evaluate(at30mFromTurn, isSimulated = false, nowMs = 4000L)
        assertEquals("Belok kiri ke Jl. Mawar", immediate.progress.speechCue)

        // Pass within <25m of p1 -> advances to ARRIVE step at p2
        val atTurn = GeoPointData(-7.280000, 112.791750)
        val afterTurn = engine.evaluate(atTurn, isSimulated = false, nowMs = 5000L)
        assertEquals(ManeuverType.ARRIVE, afterTurn.progress.currentStep.maneuverType)

        // Reach within <25m of destination p2 -> arrived
        val atDest = GeoPointData(-7.278250, 112.791800)
        val arrival = engine.evaluate(atDest, isSimulated = false, nowMs = 6000L)
        assertTrue(arrival.progress.hasArrived)
        assertEquals(0.0, arrival.progress.remainingDistanceMeters, 0.01)
        assertTrue(arrival.progress.speechCue!!.contains("tiba"))
    }

    @Test
    fun navigationEngine_offRouteAndRerouteCooldown_realVsSimulated() {
        val route = buildSampleRoute()
        val engine = NavigationEngine(route)
        val offRoutePoint = GeoPointData(-7.279400, 112.790900) // ~66m away from segment

        // Simulation never triggers off-route or reroute
        val simEval = engine.evaluate(offRoutePoint, isSimulated = true, nowMs = 1000L)
        assertFalse(simEval.progress.isOffRoute)
        assertFalse(simEval.shouldTriggerReroute)

        // Real GPS triggers reroute immediately, then respects 10s cooldown
        val real1 = engine.evaluate(offRoutePoint, isSimulated = false, nowMs = 10_000L)
        assertTrue(real1.progress.isOffRoute)
        assertTrue(real1.shouldTriggerReroute)

        val realCooldown = engine.evaluate(offRoutePoint, isSimulated = false, nowMs = 15_000L)
        assertTrue(realCooldown.progress.isOffRoute)
        assertFalse(realCooldown.shouldTriggerReroute)

        val realAfterCooldown = engine.evaluate(offRoutePoint, isSimulated = false, nowMs = 20_000L)
        assertTrue(realAfterCooldown.shouldTriggerReroute)
    }
}
