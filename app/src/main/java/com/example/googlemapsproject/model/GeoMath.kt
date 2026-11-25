package com.example.googlemapsproject.model

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private val ID_LOCALE = Locale.forLanguageTag("id-ID")

    fun distanceMeters(a: GeoPointData, b: GeoPointData): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)

        val sinLat = sin(dLat / 2.0)
        val sinLon = sin(dLon / 2.0)
        val h = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
        val c = 2.0 * atan2(sqrt(h), sqrt(max(0.0, 1.0 - h)))
        return EARTH_RADIUS_METERS * c
    }

    fun bearingDegrees(from: GeoPointData, to: GeoPointData): Float {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLon = Math.toRadians(to.lon - from.lon)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x)).toFloat()
        return ((deg % 360f) + 360f) % 360f
    }

    fun distanceToPolylineMeters(point: GeoPointData, polyline: List<GeoPointData>): Double {
        if (polyline.isEmpty()) return Double.MAX_VALUE
        if (polyline.size == 1) return distanceMeters(point, polyline[0])

        var minDist = Double.MAX_VALUE
        for (i in 0 until polyline.lastIndex) {
            val proj = projectOntoSegment(point, polyline[i], polyline[i + 1])
            if (proj.distanceMeters < minDist) {
                minDist = proj.distanceMeters
            }
        }
        return minDist
    }

    fun remainingDistanceAlongPolyline(point: GeoPointData, polyline: List<GeoPointData>): Double {
        if (polyline.size < 2) return 0.0

        var bestIndex = 0
        var bestProj = projectOntoSegment(point, polyline[0], polyline[1])
        for (i in 1 until polyline.lastIndex) {
            val candidate = projectOntoSegment(point, polyline[i], polyline[i + 1])
            if (candidate.distanceMeters < bestProj.distanceMeters) {
                bestProj = candidate
                bestIndex = i
            }
        }

        var remaining = distanceMeters(bestProj.projectedPoint, polyline[bestIndex + 1])
        for (i in (bestIndex + 1) until polyline.lastIndex) {
            remaining += distanceMeters(polyline[i], polyline[i + 1])
        }
        return remaining
    }

    fun totalPolylineDistance(polyline: List<GeoPointData>): Double {
        if (polyline.size < 2) return 0.0
        var sum = 0.0
        for (i in 0 until polyline.lastIndex) {
            sum += distanceMeters(polyline[i], polyline[i + 1])
        }
        return sum
    }

    fun interpolateAlongPolyline(
        polyline: List<GeoPointData>,
        distanceFromStartMeters: Double
    ): Pair<GeoPointData, Float> {
        if (polyline.isEmpty()) return GeoPointData(0.0, 0.0) to 0f
        if (polyline.size == 1) return polyline[0] to 0f

        if (distanceFromStartMeters <= 0.0) {
            return polyline.first() to bearingDegrees(polyline[0], polyline[1])
        }

        var accumulated = 0.0
        for (i in 0 until polyline.lastIndex) {
            val a = polyline[i]
            val b = polyline[i + 1]
            val segLen = distanceMeters(a, b)
            if (segLen <= 0.001) continue

            if (accumulated + segLen >= distanceFromStartMeters) {
                val t = ((distanceFromStartMeters - accumulated) / segLen).coerceIn(0.0, 1.0)
                val lat = a.lat + (b.lat - a.lat) * t
                val lon = a.lon + (b.lon - a.lon) * t
                return GeoPointData(lat, lon) to bearingDegrees(a, b)
            }
            accumulated += segLen
        }

        val lastIdx = polyline.lastIndex
        return polyline[lastIdx] to bearingDegrees(polyline[lastIdx - 1], polyline[lastIdx])
    }

    fun formatDistanceId(meters: Double): String {
        val safe = max(0.0, meters)
        return if (safe < 1000.0) {
            "${safe.roundToInt()} m"
        } else {
            String.format(ID_LOCALE, "%.1f km", safe / 1000.0).replace('.', ',')
        }
    }

    fun formatDurationId(seconds: Double): String {
        val safe = max(0.0, seconds)
        if (safe < 60.0) return "< 1 mnt"
        val totalMinutes = (safe / 60.0).roundToInt().coerceAtLeast(1)
        return if (totalMinutes < 60) {
            "$totalMinutes mnt"
        } else {
            val hours = totalMinutes / 60
            val mins = totalMinutes % 60
            if (mins == 0) "$hours j" else "$hours j $mins mnt"
        }
    }

    fun cardinalDirectionId(degrees: Float): String {
        val norm = ((degrees % 360f) + 360f) % 360f
        val directions = arrayOf("U", "TL", "T", "TG", "S", "BD", "B", "BL")
        val idx = ((norm + 22.5f) / 45f).toInt() % 8
        return directions[idx]
    }

    private data class SegmentProjection(
        val projectedPoint: GeoPointData,
        val distanceMeters: Double
    )

    private fun projectOntoSegment(
        p: GeoPointData,
        a: GeoPointData,
        b: GeoPointData
    ): SegmentProjection {
        val cosLat = cos(Math.toRadians(p.lat)).coerceAtLeast(0.01)
        val metersPerDegLat = 111_132.92
        val metersPerDegLon = 111_319.9 * cosLat

        val ax = (a.lon - p.lon) * metersPerDegLon
        val ay = (a.lat - p.lat) * metersPerDegLat
        val bx = (b.lon - p.lon) * metersPerDegLon
        val by = (b.lat - p.lat) * metersPerDegLat

        val abx = bx - ax
        val aby = by - ay
        val abLenSq = abx * abx + aby * aby
        if (abLenSq <= 1e-6) {
            return SegmentProjection(a, hypot(ax, ay))
        }

        val t = min(1.0, max(0.0, -(ax * abx + ay * aby) / abLenSq))
        val projX = ax + t * abx
        val projY = ay + t * aby
        val projPoint = GeoPointData(
            lat = a.lat + t * (b.lat - a.lat),
            lon = a.lon + t * (b.lon - a.lon)
        )
        return SegmentProjection(projPoint, hypot(projX, projY))
    }
}
