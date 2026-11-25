package com.example.googlemapsproject.data

import com.example.googlemapsproject.model.GeoPointData
import com.example.googlemapsproject.model.ManeuverType
import com.example.googlemapsproject.model.PlaceItem
import com.example.googlemapsproject.model.RouteOrigin
import com.example.googlemapsproject.model.RouteResult
import com.example.googlemapsproject.model.RouteStep
import com.example.googlemapsproject.model.TravelMode
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

sealed class RoutingOutcome {
    data class Success(val route: RouteResult) : RoutingOutcome()
    data class NoRoute(val message: String) : RoutingOutcome()
    data class NetworkError(val message: String) : RoutingOutcome()
}

object RoutingParser {

    fun parseOsrmResponse(
        json: String,
        origin: RouteOrigin,
        destination: PlaceItem,
        mode: TravelMode,
        usedFallbackProfile: Boolean = false
    ): RoutingOutcome {
        if (json.isBlank()) {
            return RoutingOutcome.NetworkError("Respons rute kosong")
        }
        return try {
            val root = JsonParser().parse(json)
            if (!root.isJsonObject) {
                return RoutingOutcome.NetworkError("Format respons rute tidak valid")
            }
            val obj = root.asJsonObject
            val code = obj.getStringOrEmpty("code")
            if (code.equals("NoRoute", ignoreCase = true) ||
                code.equals("NoSegment", ignoreCase = true) ||
                code.equals("InvalidQuery", ignoreCase = true)
            ) {
                return RoutingOutcome.NoRoute(
                    "Rute jalan darat tidak tersedia antara titik awal dan tujuan."
                )
            }
            if (!code.equals("Ok", ignoreCase = true)) {
                return RoutingOutcome.NetworkError("Server rute mengembalikan status: $code")
            }

            val routes = obj.getAsJsonArray("routes")
            if (routes == null || routes.size() == 0) {
                return RoutingOutcome.NoRoute("Rute tidak ditemukan.")
            }
            val firstRoute = routes[0].asJsonObject
            val totalDistance = firstRoute.getDoubleOrZero("distance")
            val rawDuration = firstRoute.getDoubleOrZero("duration")
            val effectiveDuration = if (usedFallbackProfile && mode != TravelMode.CAR && totalDistance > 0) {
                totalDistance / mode.fallbackSpeedMps
            } else {
                rawDuration
            }

            val coords = firstRoute.getAsJsonObject("geometry")?.getAsJsonArray("coordinates")
            val points = mutableListOf<GeoPointData>()
            if (coords != null) {
                for (c in coords) {
                    if (!c.isJsonArray) continue
                    val arr = c.asJsonArray
                    if (arr.size() >= 2) {
                        val lon = arr[0].asDoubleOrNull() ?: continue
                        val lat = arr[1].asDoubleOrNull() ?: continue
                        points.add(GeoPointData(lat, lon))
                    }
                }
            }

            val steps = mutableListOf<RouteStep>()
            val legs = firstRoute.getAsJsonArray("legs")
            if (legs != null && legs.size() > 0) {
                val rawSteps = legs[0].asJsonObject.getAsJsonArray("steps")
                if (rawSteps != null) {
                    for (s in rawSteps) {
                        if (!s.isJsonObject) continue
                        val stepObj = s.asJsonObject
                        val stepDist = stepObj.getDoubleOrZero("distance")
                        val stepDur = stepObj.getDoubleOrZero("duration")
                        val roadName = stepObj.getStringOrEmpty("name")
                        val maneuverObj = stepObj.getAsJsonObject("maneuver") ?: JsonObject()
                        val mType = maneuverObj.getStringOrEmpty("type")
                        val mMod = maneuverObj.getStringOrEmpty("modifier").ifEmpty { null }
                        val exit = maneuverObj.get("exit")?.asIntOrNull()
                        val locArr = maneuverObj.getAsJsonArray("location")
                        val stepPoint = if (locArr != null && locArr.size() >= 2) {
                            GeoPointData(
                                lat = locArr[1].asDoubleOrNull() ?: destination.location.lat,
                                lon = locArr[0].asDoubleOrNull() ?: destination.location.lon
                            )
                        } else {
                            destination.location
                        }

                        val (parsedType, instruction) = formatIndonesianInstruction(
                            maneuverType = mType,
                            modifier = mMod,
                            roadName = roadName,
                            exitNumber = exit
                        )
                        steps.add(
                            RouteStep(
                                instruction = instruction,
                                roadName = roadName,
                                distanceMeters = stepDist,
                                durationSeconds = if (usedFallbackProfile && mode != TravelMode.CAR && stepDist > 0) {
                                    stepDist / mode.fallbackSpeedMps
                                } else {
                                    stepDur
                                },
                                maneuverType = parsedType,
                                location = stepPoint
                            )
                        )
                    }
                }
            }

            if (points.isEmpty()) {
                return RoutingOutcome.NoRoute("Geometri rute kosong.")
            }

            RoutingOutcome.Success(
                RouteResult(
                    origin = origin,
                    destination = destination,
                    mode = mode,
                    points = points,
                    distanceMeters = totalDistance,
                    durationSeconds = effectiveDuration,
                    steps = steps,
                    usedFallbackProfile = usedFallbackProfile
                )
            )
        } catch (_: Exception) {
            RoutingOutcome.NetworkError("Gagal membaca data rute")
        }
    }

    fun formatIndonesianInstruction(
        maneuverType: String,
        modifier: String?,
        roadName: String,
        exitNumber: Int?
    ): Pair<ManeuverType, String> {
        val typeLower = maneuverType.lowercase(Locale.US)
        val modLower = modifier?.lowercase(Locale.US) ?: ""
        val roadSuffix = if (roadName.isNotBlank()) " ke $roadName" else ""
        val onRoadSuffix = if (roadName.isNotBlank()) " di $roadName" else ""

        return when (typeLower) {
            "depart" -> {
                val msg = if (roadName.isNotBlank()) "Mulai perjalanan menuju $roadName" else "Mulai perjalanan"
                ManeuverType.DEPART to msg
            }
            "arrive" -> {
                ManeuverType.ARRIVE to "Anda telah tiba di tujuan"
            }
            "roundabout", "rotary", "exit roundabout", "exit rotary" -> {
                val exitText = if (exitNumber != null && exitNumber > 0) "ambil keluar ke-$exitNumber" else "ikuti bundaran"
                ManeuverType.ROUNDABOUT to "Di bundaran, $exitText$roadSuffix"
            }
            else -> when (modLower) {
                "uturn" -> ManeuverType.UTURN to "Putar balik$roadSuffix"
                "sharp right" -> ManeuverType.SHARP_RIGHT to "Belok tajam ke kanan$roadSuffix"
                "right" -> ManeuverType.RIGHT to "Belok kanan$roadSuffix"
                "slight right" -> ManeuverType.SLIGHT_RIGHT to "Ambil kanan sedikit$roadSuffix"
                "sharp left" -> ManeuverType.SHARP_LEFT to "Belok tajam ke kiri$roadSuffix"
                "left" -> ManeuverType.LEFT to "Belok kiri$roadSuffix"
                "slight left" -> ManeuverType.SLIGHT_LEFT to "Ambil kiri sedikit$roadSuffix"
                else -> ManeuverType.STRAIGHT to "Lurus terus$onRoadSuffix"
            }
        }
    }

    private fun JsonObject.getStringOrEmpty(key: String): String {
        val el = get(key) ?: return ""
        return if (el.isJsonNull) "" else el.asString.trim()
    }

    private fun JsonObject.getDoubleOrZero(key: String): Double {
        return get(key)?.asDoubleOrNull() ?: 0.0
    }

    private fun JsonElement.asDoubleOrNull(): Double? {
        return try {
            if (isJsonNull) null else asDouble
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonElement.asIntOrNull(): Int? {
        return try {
            if (isJsonNull) null else asInt
        } catch (_: Exception) {
            null
        }
    }
}

open class RoutingService(
    private val userAgent: String = "TerraPulse/1.0 (com.example.googlemapsproject)"
) {

    open suspend fun fetchRoute(
        origin: RouteOrigin,
        originPoint: GeoPointData,
        destination: PlaceItem,
        mode: TravelMode
    ): RoutingOutcome = withContext(Dispatchers.IO) {
        val primaryUrl = String.format(
            Locale.US,
            "https://routing.openstreetmap.de/%s/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true",
            mode.osrmSubpath,
            originPoint.lon,
            originPoint.lat,
            destination.location.lon,
            destination.location.lat
        )

        val primaryResp = executeGet(primaryUrl)
        if (primaryResp != null) {
            val outcome = RoutingParser.parseOsrmResponse(
                json = primaryResp.body,
                origin = origin,
                destination = destination,
                mode = mode,
                usedFallbackProfile = false
            )
            // Never fall back blindly when OSRM explicitly says NoRoute (e.g. cross-ocean).
            if (outcome is RoutingOutcome.Success || outcome is RoutingOutcome.NoRoute) {
                return@withContext outcome
            }
        }

        val fallbackUrl = String.format(
            Locale.US,
            "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true",
            originPoint.lon,
            originPoint.lat,
            destination.location.lon,
            destination.location.lat
        )
        val fallbackResp = executeGet(fallbackUrl)
        if (fallbackResp != null) {
            return@withContext RoutingParser.parseOsrmResponse(
                json = fallbackResp.body,
                origin = origin,
                destination = destination,
                mode = mode,
                usedFallbackProfile = true
            )
        }

        RoutingOutcome.NetworkError("Tidak dapat terhubung ke server rute. Periksa koneksi internet.")
    }

    private data class HttpResponse(val code: Int, val body: String)

    private fun executeGet(urlString: String): HttpResponse? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 9000
                readTimeout = 9000
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..499 && body.isNotBlank()) {
                HttpResponse(code, body)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
