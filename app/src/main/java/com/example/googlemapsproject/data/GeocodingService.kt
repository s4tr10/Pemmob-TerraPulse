package com.example.googlemapsproject.data

import com.example.googlemapsproject.model.GeoPointData
import com.example.googlemapsproject.model.PlaceItem
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

object GeocodingParser {

    fun parsePhotonFeatures(json: String): List<PlaceItem> {
        if (json.isBlank()) return emptyList()
        return try {
            val root = JsonParser().parse(json)
            if (!root.isJsonObject) return emptyList()
            val features = root.asJsonObject.getAsJsonArray("features") ?: return emptyList()

            val results = mutableListOf<PlaceItem>()
            for (element in features) {
                if (!element.isJsonObject) continue
                val feature = element.asJsonObject
                val coords = feature.getAsJsonObject("geometry")?.getAsJsonArray("coordinates") ?: continue
                if (coords.size() < 2) continue
                val lon = coords[0].asDoubleOrNull() ?: continue
                val lat = coords[1].asDoubleOrNull() ?: continue

                val props = feature.getAsJsonObject("properties") ?: JsonObject()
                val osmId = props.getStringOrEmpty("osm_id")
                val rawName = props.getStringOrEmpty("name")
                val street = props.getStringOrEmpty("street")
                val city = props.getStringOrEmpty("city").ifEmpty { props.getStringOrEmpty("county") }
                val state = props.getStringOrEmpty("state")
                val country = props.getStringOrEmpty("country")
                val osmValue = props.getStringOrEmpty("osm_value")

                val title = rawName.ifEmpty { street }.ifEmpty { city }.ifEmpty { "Lokasi Terpilih" }
                val addressParts = linkedSetOf<String>()
                if (street.isNotEmpty() && !street.equals(title, ignoreCase = true)) addressParts.add(street)
                if (city.isNotEmpty() && !city.equals(title, ignoreCase = true)) addressParts.add(city)
                if (state.isNotEmpty() && !state.equals(title, ignoreCase = true)) addressParts.add(state)
                if (addressParts.isEmpty() && country.isNotEmpty()) addressParts.add(country)

                val address = if (addressParts.isNotEmpty()) {
                    addressParts.joinToString(", ")
                } else {
                    String.format(Locale.US, "%.5f, %.5f", lat, lon)
                }

                val stableId = if (osmId.isNotEmpty()) {
                    "photon_$osmId"
                } else {
                    String.format(Locale.US, "photon_%.5f_%.5f", lat, lon)
                }

                results.add(
                    PlaceItem(
                        id = stableId,
                        name = title,
                        address = address,
                        location = GeoPointData(lat, lon),
                        category = osmValue
                    )
                )
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseNominatimSearch(json: String): List<PlaceItem> {
        if (json.isBlank()) return emptyList()
        return try {
            val root = JsonParser().parse(json)
            if (!root.isJsonArray) return emptyList()
            val array = root.asJsonArray
            val results = mutableListOf<PlaceItem>()
            for (element in array) {
                if (!element.isJsonObject) continue
                val obj = element.asJsonObject
                val lat = obj.get("lat")?.asDoubleOrNull() ?: continue
                val lon = obj.get("lon")?.asDoubleOrNull() ?: continue
                val displayName = obj.getStringOrEmpty("display_name")
                val parts = displayName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val name = parts.firstOrNull() ?: "Lokasi"
                val address = if (parts.size > 1) {
                    parts.drop(1).take(3).joinToString(", ")
                } else {
                    String.format(Locale.US, "%.5f, %.5f", lat, lon)
                }
                val placeId = obj.getStringOrEmpty("place_id").ifEmpty {
                    String.format(Locale.US, "%.5f_%.5f", lat, lon)
                }
                results.add(
                    PlaceItem(
                        id = "nom_$placeId",
                        name = name,
                        address = address,
                        location = GeoPointData(lat, lon),
                        category = obj.getStringOrEmpty("type")
                    )
                )
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseNominatimReverse(json: String, fallbackPoint: GeoPointData): PlaceItem? {
        if (json.isBlank()) return null
        return try {
            val root = JsonParser().parse(json)
            if (!root.isJsonObject) return null
            val obj = root.asJsonObject
            if (obj.has("error")) return null
            val lat = obj.get("lat")?.asDoubleOrNull() ?: fallbackPoint.lat
            val lon = obj.get("lon")?.asDoubleOrNull() ?: fallbackPoint.lon
            val displayName = obj.getStringOrEmpty("display_name")
            val parts = displayName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val name = obj.getStringOrEmpty("name").ifEmpty { parts.firstOrNull() ?: "Lokasi Dipilih" }
            val address = if (parts.size > 1) {
                parts.drop(1).take(3).joinToString(", ")
            } else {
                String.format(Locale.US, "%.5f, %.5f", lat, lon)
            }
            PlaceItem(
                id = String.format(Locale.US, "rev_%.5f_%.5f", lat, lon),
                name = name,
                address = address,
                location = GeoPointData(lat, lon)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.getStringOrEmpty(key: String): String {
        val el = get(key) ?: return ""
        return if (el.isJsonNull) "" else el.asString.trim()
    }

    private fun JsonElement.asDoubleOrNull(): Double? {
        return try {
            if (isJsonNull) null else asDouble
        } catch (_: Exception) {
            null
        }
    }
}

open class GeocodingService(
    private val userAgent: String = "TerraPulse/1.0 (com.example.googlemapsproject)"
) {

    open suspend fun searchAutocomplete(
        query: String,
        biasLocation: GeoPointData? = null,
        limit: Int = 6
    ): List<PlaceItem> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()

        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val biasQuery = if (biasLocation != null) {
            String.format(Locale.US, "&lat=%.5f&lon=%.5f", biasLocation.lat, biasLocation.lon)
        } else {
            ""
        }

        val photonBody = httpGet("https://photon.komoot.io/api/?q=$encoded&limit=$limit$biasQuery")
        if (photonBody != null) {
            val photonResults = GeocodingParser.parsePhotonFeatures(photonBody)
            if (photonResults.isNotEmpty()) return@withContext photonResults
        }

        val nomBody = httpGet("https://nominatim.openstreetmap.org/search?format=json&q=$encoded&limit=$limit")
        if (nomBody != null) {
            return@withContext GeocodingParser.parseNominatimSearch(nomBody)
        }
        emptyList()
    }

    open suspend fun reverseGeocode(point: GeoPointData): PlaceItem = withContext(Dispatchers.IO) {
        val photonUrl = String.format(
            Locale.US,
            "https://photon.komoot.io/reverse?lon=%.6f&lat=%.6f",
            point.lon,
            point.lat
        )
        val photonBody = httpGet(photonUrl)
        if (photonBody != null) {
            val parsed = GeocodingParser.parsePhotonFeatures(photonBody).firstOrNull()
            if (parsed != null) {
                return@withContext parsed.copy(location = point)
            }
        }

        val nomUrl = String.format(
            Locale.US,
            "https://nominatim.openstreetmap.org/reverse?format=json&lat=%.6f&lon=%.6f",
            point.lat,
            point.lon
        )
        val nomBody = httpGet(nomUrl)
        if (nomBody != null) {
            val parsed = GeocodingParser.parseNominatimReverse(nomBody, point)
            if (parsed != null) return@withContext parsed
        }

        PlaceItem(
            id = String.format(Locale.US, "pin_%.5f_%.5f", point.lat, point.lon),
            name = "Lokasi Dipilih",
            address = String.format(Locale.US, "Lat %.5f, Lon %.5f", point.lat, point.lon),
            location = point
        )
    }

    private fun httpGet(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
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
