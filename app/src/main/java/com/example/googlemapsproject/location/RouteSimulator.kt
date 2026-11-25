package com.example.googlemapsproject.location

import com.example.googlemapsproject.model.GeoMath
import com.example.googlemapsproject.model.GeoPointData
import com.example.googlemapsproject.model.RouteResult
import com.example.googlemapsproject.model.TravelMode
import kotlin.math.max
import kotlin.math.min

class RouteSimulator(private val route: RouteResult) {

    data class SimulationTick(
        val location: GeoPointData,
        val bearingDegrees: Float,
        val speedKmh: Float,
        val finished: Boolean
    )

    var isPaused: Boolean = false
    var speedMultiplier: Float = 1f

    private val totalDistanceMeters: Double =
        GeoMath.totalPolylineDistance(route.points).coerceAtLeast(route.distanceMeters)
    private var traveledMeters: Double = 0.0

    private val baseSpeedMps: Double = when (route.mode) {
        TravelMode.CAR -> 12.5
        TravelMode.BIKE -> 5.5
        TravelMode.FOOT -> 1.5
    }

    fun reset() {
        traveledMeters = 0.0
        isPaused = false
        speedMultiplier = 1f
    }

    fun advance(deltaSeconds: Double): SimulationTick {
        val safeDelta = max(0.0, deltaSeconds)
        val effectiveSpeedMps = if (isPaused) 0.0 else baseSpeedMps * speedMultiplier.toDouble()

        if (!isPaused && safeDelta > 0.0) {
            traveledMeters = min(totalDistanceMeters, traveledMeters + effectiveSpeedMps * safeDelta)
        }

        val (pos, bearing) = GeoMath.interpolateAlongPolyline(route.points, traveledMeters)
        val finished = totalDistanceMeters <= 0.1 || traveledMeters >= totalDistanceMeters - 0.5

        return SimulationTick(
            location = pos,
            bearingDegrees = bearing,
            speedKmh = if (finished || isPaused) 0f else (effectiveSpeedMps * 3.6).toFloat(),
            finished = finished
        )
    }
}
