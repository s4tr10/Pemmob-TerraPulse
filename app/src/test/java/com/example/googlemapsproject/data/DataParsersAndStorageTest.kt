package com.example.googlemapsproject.data

import com.example.googlemapsproject.model.GeoPointData
import com.example.googlemapsproject.model.ManeuverType
import com.example.googlemapsproject.model.PlaceItem
import com.example.googlemapsproject.model.RouteOrigin
import com.example.googlemapsproject.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataParsersAndStorageTest {

    @Test
    fun geocodingParser_photonValidAndMalformedJson_behavesSafely() {
        val samplePhoton = """
            {
              "features": [
                {
                  "geometry": { "coordinates": [112.794925, -7.282356] },
                  "properties": {
                    "osm_id": 12345,
                    "name": "Kampus ITS",
                    "street": "Jl. Teknik Kimia",
                    "city": "Surabaya",
                    "state": "Jawa Timur"
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = GeocodingParser.parsePhotonFeatures(samplePhoton)
        assertEquals(1, parsed.size)
        assertEquals("Kampus ITS", parsed[0].name)
        assertEquals(-7.282356, parsed[0].location.lat, 0.000001)
        assertEquals(112.794925, parsed[0].location.lon, 0.000001)
        assertTrue(parsed[0].address.contains("Surabaya"))

        // Malformed or empty JSON recovers cleanly
        assertTrue(GeocodingParser.parsePhotonFeatures("{broken json").isEmpty())
        assertTrue(GeocodingParser.parsePhotonFeatures("""{"features":[]}""").isEmpty())
    }

    @Test
    fun geocodingParser_nominatimSearch_parsesCorrectly() {
        val sampleNom = """
            [
              {
                "place_id": 999,
                "lat": "-7.245875",
                "lon": "112.737846",
                "display_name": "Tugu Pahlawan, Alun-alun Contong, Surabaya, Jawa Timur"
              }
            ]
        """.trimIndent()

        val list = GeocodingParser.parseNominatimSearch(sampleNom)
        assertEquals(1, list.size)
        assertEquals("Tugu Pahlawan", list[0].name)
        assertEquals(-7.245875, list[0].location.lat, 0.000001)
    }

    @Test
    fun routingParser_osrmSuccessAndFallbackDuration_parsesIndonesianSteps() {
        val sampleOsrm = """
            {
              "code": "Ok",
              "routes": [
                {
                  "distance": 1200.0,
                  "duration": 120.0,
                  "geometry": {
                    "coordinates": [
                      [112.7900, -7.2800],
                      [112.7940, -7.2800],
                      [112.7940, -7.2760]
                    ]
                  },
                  "legs": [
                    {
                      "steps": [
                        {
                          "distance": 600.0,
                          "duration": 60.0,
                          "name": "Jl. Teknik Kimia",
                          "maneuver": { "type": "depart", "modifier": "straight", "location": [112.7900, -7.2800] }
                        },
                        {
                          "distance": 600.0,
                          "duration": 60.0,
                          "name": "Jl. Arif Rahman Hakim",
                          "maneuver": { "type": "turn", "modifier": "left", "location": [112.7940, -7.2800] }
                        },
                        {
                          "distance": 0.0,
                          "duration": 0.0,
                          "name": "",
                          "maneuver": { "type": "arrive", "location": [112.7940, -7.2760] }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val dest = PlaceItem("d", "Tujuan", "Surabaya", GeoPointData(-7.2760, 112.7940))
        val carOutcome = RoutingParser.parseOsrmResponse(sampleOsrm, RouteOrigin.CurrentGps, dest, TravelMode.CAR, usedFallbackProfile = false)
        assertTrue(carOutcome is RoutingOutcome.Success)
        val carRoute = (carOutcome as RoutingOutcome.Success).route
        assertEquals(3, carRoute.points.size)
        assertEquals(3, carRoute.steps.size)
        assertEquals(ManeuverType.LEFT, carRoute.steps[1].maneuverType)
        assertEquals("Belok kiri ke Jl. Arif Rahman Hakim", carRoute.steps[1].instruction)
        assertEquals(120.0, carRoute.durationSeconds, 0.1)

        // Fallback profile recalculates duration for FOOT (1200 / 1.33 ~ 902s)
        val footOutcome = RoutingParser.parseOsrmResponse(sampleOsrm, RouteOrigin.CurrentGps, dest, TravelMode.FOOT, usedFallbackProfile = true)
        val footRoute = (footOutcome as RoutingOutcome.Success).route
        assertTrue(footRoute.durationSeconds > 800.0)
        assertTrue(footRoute.usedFallbackProfile)
    }

    @Test
    fun routingParser_osrmNoRoute_returnsNoRouteOutcome() {
        val noRouteJson = """{"code":"NoRoute","message":"Impossible route between points"}"""
        val dest = PlaceItem("d", "Monas", "Jakarta", GeoPointData(-6.175392, 106.827153))
        val outcome = RoutingParser.parseOsrmResponse(noRouteJson, RouteOrigin.CurrentGps, dest, TravelMode.CAR)
        assertTrue(outcome is RoutingOutcome.NoRoute)
    }

    @Test
    fun placeListSerializer_recoversCorruptJsonCapsRecentsAndTogglesFavorites() {
        assertTrue(PlaceListSerializer.fromJson("{corrupt!").isEmpty())
        assertTrue(PlaceListSerializer.fromJson(null).isEmpty())

        var recents = emptyList<PlaceItem>()
        for (i in 1..12) {
            recents = PlaceListSerializer.addRecent(
                recents,
                PlaceItem("id_$i", "Place $i", "Addr $i", GeoPointData(-7.28 + i * 0.001, 112.79))
            )
        }
        assertEquals(10, recents.size)
        assertEquals("id_12", recents.first().id)

        // Adding duplicate moves it to front without increasing size
        val duplicate = PlaceItem("id_5", "Place 5", "Addr 5", GeoPointData(-7.275, 112.79))
        val deduped = PlaceListSerializer.addRecent(recents, duplicate)
        assertEquals(10, deduped.size)
        assertEquals("id_5", deduped.first().id)

        // Idempotent favorite toggle
        val (added, isFav1) = PlaceListSerializer.toggleFavorite(emptyList(), duplicate)
        assertTrue(isFav1)
        assertEquals(1, added.size)

        val (removed, isFav2) = PlaceListSerializer.toggleFavorite(added, duplicate)
        assertFalse(isFav2)
        assertTrue(removed.isEmpty())
    }
}
