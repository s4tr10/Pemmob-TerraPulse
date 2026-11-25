package com.example.googlemapsproject.ui

import com.example.googlemapsproject.data.GeocodingService
import com.example.googlemapsproject.data.InMemoryPlaceStore
import com.example.googlemapsproject.data.RoutingOutcome
import com.example.googlemapsproject.data.RoutingService
import com.example.googlemapsproject.model.GeoPointData
import com.example.googlemapsproject.model.GpsStatus
import com.example.googlemapsproject.model.GpsTelemetry
import com.example.googlemapsproject.model.ManeuverType
import com.example.googlemapsproject.model.MapUiState
import com.example.googlemapsproject.model.PlaceItem
import com.example.googlemapsproject.model.PresetPlaces
import com.example.googlemapsproject.model.RouteOrigin
import com.example.googlemapsproject.model.RouteResult
import com.example.googlemapsproject.model.RouteStep
import com.example.googlemapsproject.model.TravelMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MapStateMachineTest {

    private lateinit var testScope: CoroutineScope

    private val its = PresetPlaces.ALL[0]
    private val tugu = PresetPlaces.ALL[1]

    private class FakeGeocodingService(
        private val searchResults: List<PlaceItem> = emptyList()
    ) : GeocodingService() {
        override suspend fun searchAutocomplete(
            query: String,
            biasLocation: GeoPointData?,
            limit: Int
        ): List<PlaceItem> = searchResults

        override suspend fun reverseGeocode(point: GeoPointData): PlaceItem {
            return PlaceItem("rev", "Titik Peta", "Surabaya", point)
        }
    }

    private class FakeRoutingService(
        private val returnNoRoute: Boolean = false
    ) : RoutingService() {
        override suspend fun fetchRoute(
            origin: RouteOrigin,
            originPoint: GeoPointData,
            destination: PlaceItem,
            mode: TravelMode
        ): RoutingOutcome {
            if (returnNoRoute) {
                return RoutingOutcome.NoRoute("Rute jalan darat tidak tersedia.")
            }
            val mid = GeoPointData(
                (originPoint.lat + destination.location.lat) / 2.0,
                (originPoint.lon + destination.location.lon) / 2.0
            )
            return RoutingOutcome.Success(
                RouteResult(
                    origin = origin,
                    destination = destination,
                    mode = mode,
                    points = listOf(originPoint, mid, destination.location),
                    distanceMeters = 5000.0,
                    durationSeconds = 420.0,
                    steps = listOf(
                        RouteStep("Mulai", "Jl. Awal", 2500.0, 210.0, ManeuverType.DEPART, originPoint),
                        RouteStep("Belok kanan", "Jl. Tengah", 2500.0, 210.0, ManeuverType.RIGHT, mid),
                        RouteStep("Tiba", "", 0.0, 0.0, ManeuverType.ARRIVE, destination.location)
                    )
                )
            )
        }
    }

    @Before
    fun setup() {
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun stateTransitions_andBackStackUnwinding_workCleanly() {
        val vm = MapViewModel(
            geocodingService = FakeGeocodingService(listOf(tugu)),
            routingService = FakeRoutingService(),
            placeStore = InMemoryPlaceStore(),
            customScope = testScope
        )
        vm.onGpsTelemetryUpdate(
            GpsTelemetry(status = GpsStatus.LOCKED, location = its.location, accuracyMeters = 8f)
        )

        assertTrue(vm.uiState.value is MapUiState.Explore)

        vm.openSearch()
        assertTrue(vm.uiState.value is MapUiState.Search)

        vm.selectPlaceFromSearch(tugu)
        val placeState = vm.uiState.value as MapUiState.PlaceSelected
        assertEquals(tugu.id, placeState.place.id)
        assertEquals(1, vm.recents.value.size)

        vm.openRoutePreview(tugu)
        val routeState = vm.uiState.value as MapUiState.RoutePreview
        assertTrue(routeState.route != null)

        vm.startSimulatedNavigation()
        val navState = vm.uiState.value as MapUiState.Navigating
        assertTrue(navState.isSimulated)
        assertEquals(1f, navState.simulationSpeedMultiplier, 0.01f)

        vm.toggleSimulationSpeed()
        assertEquals(2f, (vm.uiState.value as MapUiState.Navigating).simulationSpeedMultiplier, 0.01f)

        vm.toggleSimulationPause()
        assertTrue((vm.uiState.value as MapUiState.Navigating).simulationPaused)

        // Unwind Back: Navigating -> RoutePreview -> PlaceSelected -> Explore -> false
        assertTrue(vm.handleBackPressed())
        assertTrue(vm.uiState.value is MapUiState.RoutePreview)

        assertTrue(vm.handleBackPressed())
        assertTrue(vm.uiState.value is MapUiState.PlaceSelected)

        assertTrue(vm.handleBackPressed())
        assertTrue(vm.uiState.value is MapUiState.Explore)

        assertFalse(vm.handleBackPressed())
    }

    @Test
    fun manualOriginGuard_andNoRouteState_behaveCorrectly() {
        val events = mutableListOf<MapViewModel.UiEvent>()
        val vm = MapViewModel(
            geocodingService = FakeGeocodingService(),
            routingService = FakeRoutingService(),
            placeStore = InMemoryPlaceStore(),
            customScope = testScope
        )
        val collectJob = testScope.launch {
            vm.oneShotEvent.collect { events.add(it) }
        }

        // GPS is far away in Mountain View (emulator default)
        vm.onGpsTelemetryUpdate(
            GpsTelemetry(status = GpsStatus.LOCKED, location = GeoPointData(37.421998, -122.084), accuracyMeters = 5f)
        )
        vm.selectPresetPlace(tugu)
        vm.openRoutePreview(tugu)
        vm.setManualOrigin(its)

        // Trying to start real GPS navigation emits confirmation event instead of silently entering Navigating
        vm.requestStartRealNavigation()
        assertTrue(events.any { it is MapViewModel.UiEvent.ConfirmManualOriginNavigation })
        assertTrue(vm.uiState.value is MapUiState.RoutePreview)

        // NoRoute response sets isNoRouteError = true
        val noRouteVm = MapViewModel(
            geocodingService = FakeGeocodingService(),
            routingService = FakeRoutingService(returnNoRoute = true),
            placeStore = InMemoryPlaceStore(),
            customScope = testScope
        )
        noRouteVm.onGpsTelemetryUpdate(
            GpsTelemetry(status = GpsStatus.LOCKED, location = GeoPointData(37.421998, -122.084), accuracyMeters = 5f)
        )
        noRouteVm.openRoutePreview(tugu)
        val preview = noRouteVm.uiState.value as MapUiState.RoutePreview
        assertTrue(preview.isNoRouteError)
        assertTrue(preview.route == null)

        collectJob.cancel()
    }
}
