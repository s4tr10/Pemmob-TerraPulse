package com.example.googlemapsproject.location

import com.example.googlemapsproject.model.GeoMath
import com.example.googlemapsproject.model.GeoPointData
import com.example.googlemapsproject.model.ManeuverType
import com.example.googlemapsproject.model.PlaceItem
import com.example.googlemapsproject.model.RouteOrigin
import com.example.googlemapsproject.model.RouteResult
import com.example.googlemapsproject.model.RouteStep
import com.example.googlemapsproject.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSimulatorTest {

    private fun sampleRoute(mode: TravelMode = TravelMode.CAR): RouteResult {
        val p0 = GeoPointData(-7.280000, 112.790000)
        // ~200m east
        val p1 = GeoPointData(-7.280000, 112.791800)
        val dest = PlaceItem("d", "Tujuan", "Surabaya", p1)
        return RouteResult(
            origin = RouteOrigin.CurrentGps,
            destination = dest,
            mode = mode,
            points = listOf(p0, p1),
            distanceMeters = GeoMath.distanceMeters(p0, p1),
            durationSeconds = 20.0,
            steps = listOf(
                RouteStep("Mulai", "", 200.0, 20.0, ManeuverType.DEPART, p0),
                RouteStep("Tiba", "", 0.0, 0.0, ManeuverType.ARRIVE, p1)
            )
        )
    }

    @Test
    fun simulator_advancesPauseSpeedMultiplierAndCompletesCleanly() {
        val route = sampleRoute(TravelMode.CAR)
        val sim = RouteSimulator(route)

        val tick0 = sim.advance(0.0)
        assertEquals(route.points.first().lat, tick0.location.lat, 0.000001)
        assertEquals(route.points.first().lon, tick0.location.lon, 0.000001)
        assertFalse(tick0.finished)

        val tick1 = sim.advance(1.0)
        val moved1 = GeoMath.distanceMeters(route.points.first(), tick1.location)
        assertTrue("Expected ~12.5m moved in 1s, got $moved1", moved1 in 11.0..14.0)
        assertEquals(90f, tick1.bearingDegrees, 2.0f)
        assertTrue(tick1.speedKmh > 30f)

        sim.isPaused = true
        val tickPaused = sim.advance(2.0)
        assertEquals(tick1.location.lat, tickPaused.location.lat, 0.000001)
        assertEquals(tick1.location.lon, tickPaused.location.lon, 0.000001)
        assertEquals(0f, tickPaused.speedKmh, 0.01f)

        sim.isPaused = false
        sim.speedMultiplier = 2f
        val tick2x = sim.advance(1.0)
        val moved2x = GeoMath.distanceMeters(tick1.location, tick2x.location)
        assertTrue("Expected ~25m moved at 2x in 1s, got $moved2x", moved2x in 22.0..28.0)

        // Advance well past end -> clamps to destination and reports finished
        val tickEnd = sim.advance(60.0)
        assertTrue(tickEnd.finished)
        assertEquals(route.points.last().lat, tickEnd.location.lat, 0.000001)
        assertEquals(route.points.last().lon, tickEnd.location.lon, 0.000001)
    }
}
