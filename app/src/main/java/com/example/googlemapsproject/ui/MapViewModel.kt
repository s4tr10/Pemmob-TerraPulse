package com.example.googlemapsproject.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.googlemapsproject.data.GeocodingService
import com.example.googlemapsproject.data.InMemoryPlaceStore
import com.example.googlemapsproject.data.PlaceStore
import com.example.googlemapsproject.data.RoutingOutcome
import com.example.googlemapsproject.data.RoutingService
import com.example.googlemapsproject.location.RouteSimulator
import com.example.googlemapsproject.model.CameraMode
import com.example.googlemapsproject.model.GeoMath
import com.example.googlemapsproject.model.GeoPointData
import com.example.googlemapsproject.model.GpsStatus
import com.example.googlemapsproject.model.GpsTelemetry
import com.example.googlemapsproject.model.MapLayerType
import com.example.googlemapsproject.model.MapUiState
import com.example.googlemapsproject.model.NavigationEngine
import com.example.googlemapsproject.model.NavigationProgress
import com.example.googlemapsproject.model.PlaceItem
import com.example.googlemapsproject.model.RouteOrigin
import com.example.googlemapsproject.model.RouteResult
import com.example.googlemapsproject.model.TravelMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class MapViewModel(
    private val geocodingService: GeocodingService = GeocodingService(),
    private val routingService: RoutingService = RoutingService(),
    private val placeStore: PlaceStore = InMemoryPlaceStore(),
    private val customScope: CoroutineScope? = null
) : ViewModel() {

    sealed interface UiEvent {
        data class Message(val text: String) : UiEvent
        data class SpeakCue(val text: String) : UiEvent
        data class ConfirmManualOriginNavigation(val route: RouteResult) : UiEvent
        data class AnimateMapTo(val point: GeoPointData, val zoom: Double = 17.0) : UiEvent
    }

    private val scope: CoroutineScope
        get() = customScope ?: viewModelScope

    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Explore)
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    private val _telemetry = MutableStateFlow(GpsTelemetry())
    val telemetry: StateFlow<GpsTelemetry> = _telemetry.asStateFlow()

    private val _cameraMode = MutableStateFlow(CameraMode.FOLLOW)
    val cameraMode: StateFlow<CameraMode> = _cameraMode.asStateFlow()

    private val _activeLayer = MutableStateFlow(placeStore.getSavedLayer())
    val activeLayer: StateFlow<MapLayerType> = _activeLayer.asStateFlow()

    private val _favorites = MutableStateFlow(placeStore.getFavorites())
    val favorites: StateFlow<List<PlaceItem>> = _favorites.asStateFlow()

    private val _recents = MutableStateFlow(placeStore.getRecents())
    val recents: StateFlow<List<PlaceItem>> = _recents.asStateFlow()

    private val _oneShotEvent = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val oneShotEvent: SharedFlow<UiEvent> = _oneShotEvent.asSharedFlow()

    private var searchJob: Job? = null
    private var reverseGeocodeJob: Job? = null
    private var routeJob: Job? = null
    private var simulationJob: Job? = null
    private var rerouteJob: Job? = null

    private var navigationEngine: NavigationEngine? = null
    private var routeSimulator: RouteSimulator? = null
    private var savedRoutePreviewBeforeNav: MapUiState.RoutePreview? = null
    private var savedRoutePreviewBeforeOriginPick: MapUiState.RoutePreview? = null
    private var lastSelectedPlace: PlaceItem? = null

    fun openSearch(pickingOriginForRoute: Boolean = false) {
        if (pickingOriginForRoute) {
            val current = _uiState.value
            if (current is MapUiState.RoutePreview) {
                savedRoutePreviewBeforeOriginPick = current
            }
        }
        _uiState.value = MapUiState.Search(
            query = "",
            isLoading = false,
            suggestions = emptyList(),
            pickingOriginForRoute = pickingOriginForRoute
        )
    }

    fun onSearchQueryChanged(query: String) {
        val current = _uiState.value as? MapUiState.Search ?: return
        val trimmed = query.trim()
        searchJob?.cancel()

        if (trimmed.length < 2) {
            _uiState.value = current.copy(query = query, isLoading = false, suggestions = emptyList())
            return
        }

        _uiState.value = current.copy(query = query, isLoading = true)
        searchJob = scope.launch {
            delay(400L)
            val results = geocodingService.searchAutocomplete(
                query = trimmed,
                biasLocation = _telemetry.value.location,
                limit = 6
            )
            val latest = _uiState.value as? MapUiState.Search ?: return@launch
            if (latest.query.trim() == trimmed) {
                _uiState.value = latest.copy(isLoading = false, suggestions = results)
            }
        }
    }

    fun submitSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _oneShotEvent.tryEmit(UiEvent.Message("Masukkan minimal 2 huruf untuk mencari lokasi"))
            return
        }
        val current = _uiState.value as? MapUiState.Search ?: return
        searchJob?.cancel()
        _uiState.value = current.copy(query = query, isLoading = true)
        searchJob = scope.launch {
            val results = geocodingService.searchAutocomplete(
                query = trimmed,
                biasLocation = _telemetry.value.location,
                limit = 6
            )
            val latest = _uiState.value as? MapUiState.Search ?: return@launch
            if (results.isEmpty()) {
                _uiState.value = latest.copy(isLoading = false, suggestions = emptyList())
                _oneShotEvent.tryEmit(UiEvent.Message("Lokasi tidak ditemukan"))
            } else if (results.size == 1) {
                selectPlaceFromSearch(results.first())
            } else {
                _uiState.value = latest.copy(isLoading = false, suggestions = results)
            }
        }
    }

    fun selectPlaceFromSearch(place: PlaceItem) {
        searchJob?.cancel()
        val current = _uiState.value
        val pickingOrigin = (current as? MapUiState.Search)?.pickingOriginForRoute == true
        if (pickingOrigin) {
            val preview = savedRoutePreviewBeforeOriginPick
            savedRoutePreviewBeforeOriginPick = null
            if (preview != null) {
                setManualOriginOnPreview(preview, place)
                return
            }
        }

        _recents.value = placeStore.addRecent(place)
        selectPresetPlace(place)
    }

    fun selectPresetPlace(place: PlaceItem) {
        reverseGeocodeJob?.cancel()
        _cameraMode.value = CameraMode.FREE
        lastSelectedPlace = place
        _uiState.value = MapUiState.PlaceSelected(
            place = place,
            isLoadingAddress = false,
            isFavorite = placeStore.isFavorite(place)
        )
        _oneShotEvent.tryEmit(UiEvent.AnimateMapTo(place.location, 17.0))
    }

    fun onMapSingleTap(point: GeoPointData) {
        val current = _uiState.value
        if (current is MapUiState.Navigating || current is MapUiState.Search) return

        reverseGeocodeJob?.cancel()
        _cameraMode.value = CameraMode.FREE
        val placeholder = PlaceItem(
            id = String.format(Locale.US, "pin_%.5f_%.5f", point.lat, point.lon),
            name = "Memuat alamat...",
            address = String.format(Locale.US, "Lat %.5f, Lon %.5f", point.lat, point.lon),
            location = point
        )
        lastSelectedPlace = placeholder
        _uiState.value = MapUiState.PlaceSelected(
            place = placeholder,
            isLoadingAddress = true,
            isFavorite = placeStore.isFavorite(placeholder)
        )

        reverseGeocodeJob = scope.launch {
            val resolved = geocodingService.reverseGeocode(point)
            val latest = _uiState.value
            if (latest is MapUiState.PlaceSelected && latest.place.location == point) {
                lastSelectedPlace = resolved
                _uiState.value = MapUiState.PlaceSelected(
                    place = resolved,
                    isLoadingAddress = false,
                    isFavorite = placeStore.isFavorite(resolved)
                )
            }
        }
    }

    fun toggleFavorite(place: PlaceItem) {
        val (updated, isNowFav) = placeStore.toggleFavorite(place)
        _favorites.value = updated
        val current = _uiState.value
        if (current is MapUiState.PlaceSelected && current.place.id == place.id) {
            _uiState.value = current.copy(isFavorite = isNowFav)
        }
        val msg = if (isNowFav) "Ditambahkan ke Favorit" else "Dihapus dari Favorit"
        _oneShotEvent.tryEmit(UiEvent.Message(msg))
    }

    fun clearRecents() {
        placeStore.clearRecents()
        _recents.value = emptyList()
    }

    fun openRoutePreview(destination: PlaceItem) {
        lastSelectedPlace = destination
        val preview = MapUiState.RoutePreview(
            origin = RouteOrigin.CurrentGps,
            destination = destination,
            mode = TravelMode.CAR
        )
        loadRouteForPreview(preview)
    }

    fun setManualOrigin(place: PlaceItem) {
        val current = _uiState.value as? MapUiState.RoutePreview ?: return
        setManualOriginOnPreview(current, place)
    }

    fun useCurrentGpsAsOrigin() {
        val current = _uiState.value as? MapUiState.RoutePreview ?: return
        loadRouteForPreview(current.copy(origin = RouteOrigin.CurrentGps))
    }

    fun swapOriginAndDestination() {
        val current = _uiState.value as? MapUiState.RoutePreview ?: return
        val currentOriginPlace = when (val orig = current.origin) {
            is RouteOrigin.Manual -> orig.place
            RouteOrigin.CurrentGps -> {
                val gpsLoc = _telemetry.value.location
                if (gpsLoc == null) {
                    _oneShotEvent.tryEmit(UiEvent.Message("Lokasi GPS belum terkunci untuk ditukar"))
                    return
                }
                PlaceItem("gps_origin", "Lokasi Saya (GPS)", "Posisi saat ini", gpsLoc)
            }
        }
        val newOrigin = RouteOrigin.Manual(current.destination)
        val newDestination = currentOriginPlace
        lastSelectedPlace = newDestination
        loadRouteForPreview(current.copy(origin = newOrigin, destination = newDestination))
    }

    fun selectTravelMode(mode: TravelMode) {
        val current = _uiState.value as? MapUiState.RoutePreview ?: return
        if (current.mode == mode && current.route != null) return
        loadRouteForPreview(current.copy(mode = mode))
    }

    fun retryRoute() {
        val current = _uiState.value as? MapUiState.RoutePreview ?: return
        loadRouteForPreview(current)
    }

    fun toggleStepsExpanded() {
        val current = _uiState.value as? MapUiState.RoutePreview ?: return
        _uiState.value = current.copy(stepsExpanded = !current.stepsExpanded)
    }

    private fun setManualOriginOnPreview(preview: MapUiState.RoutePreview, originPlace: PlaceItem) {
        loadRouteForPreview(preview.copy(origin = RouteOrigin.Manual(originPlace)))
    }

    private fun loadRouteForPreview(preview: MapUiState.RoutePreview) {
        routeJob?.cancel()
        val originPoint = when (val orig = preview.origin) {
            is RouteOrigin.Manual -> orig.place.location
            RouteOrigin.CurrentGps -> _telemetry.value.location
        }

        if (originPoint == null) {
            _uiState.value = preview.copy(
                isLoading = false,
                route = null,
                errorMessage = "GPS belum terkunci. Aktifkan GPS atau pilih titik awal manual (misalnya Kampus ITS).",
                isNoRouteError = false
            )
            return
        }

        _uiState.value = preview.copy(
            isLoading = true,
            errorMessage = null,
            isNoRouteError = false
        )

        routeJob = scope.launch {
            when (val outcome = routingService.fetchRoute(preview.origin, originPoint, preview.destination, preview.mode)) {
                is RoutingOutcome.Success -> {
                    _uiState.value = preview.copy(
                        isLoading = false,
                        route = outcome.route,
                        errorMessage = null,
                        isNoRouteError = false
                    )
                }
                is RoutingOutcome.NoRoute -> {
                    _uiState.value = preview.copy(
                        isLoading = false,
                        route = null,
                        errorMessage = outcome.message + " Coba ubah titik awal untuk rute dalam pulau yang sama.",
                        isNoRouteError = true
                    )
                }
                is RoutingOutcome.NetworkError -> {
                    _uiState.value = preview.copy(
                        isLoading = false,
                        route = null,
                        errorMessage = outcome.message,
                        isNoRouteError = false
                    )
                }
            }
        }
    }

    fun requestStartRealNavigation() {
        val current = _uiState.value as? MapUiState.RoutePreview ?: return
        val route = current.route ?: run {
            _oneShotEvent.tryEmit(UiEvent.Message("Tunggu rute selesai dihitung terlebih dahulu"))
            return
        }

        val gpsLoc = _telemetry.value.location
        val origin = route.origin
        if (origin is RouteOrigin.Manual) {
            val distFromRealGps = gpsLoc?.let { GeoMath.distanceMeters(it, origin.place.location) } ?: Double.MAX_VALUE
            if (distFromRealGps > 100.0) {
                _oneShotEvent.tryEmit(UiEvent.ConfirmManualOriginNavigation(route))
                return
            }
        }

        if (gpsLoc == null) {
            _oneShotEvent.tryEmit(UiEvent.Message("GPS belum terkunci. Gunakan Simulasi Demo atau aktifkan GPS."))
            return
        }

        enterNavigation(route, isSimulated = false, previewSnapshot = current)
    }

    fun startRealNavigationFromCurrentGps() {
        val current = _uiState.value as? MapUiState.RoutePreview ?: return
        val gpsLoc = _telemetry.value.location
        if (gpsLoc == null) {
            _oneShotEvent.tryEmit(UiEvent.Message("Lokasi GPS belum tersedia"))
            return
        }
        routeJob?.cancel()
        _uiState.value = current.copy(isLoading = true, errorMessage = null)
        routeJob = scope.launch {
            when (val outcome = routingService.fetchRoute(RouteOrigin.CurrentGps, gpsLoc, current.destination, current.mode)) {
                is RoutingOutcome.Success -> {
                    val updatedPreview = current.copy(
                        origin = RouteOrigin.CurrentGps,
                        isLoading = false,
                        route = outcome.route
                    )
                    enterNavigation(outcome.route, isSimulated = false, previewSnapshot = updatedPreview)
                }
                is RoutingOutcome.NoRoute -> {
                    _uiState.value = current.copy(
                        isLoading = false,
                        route = null,
                        errorMessage = outcome.message,
                        isNoRouteError = true
                    )
                }
                is RoutingOutcome.NetworkError -> {
                    _uiState.value = current.copy(
                        isLoading = false,
                        errorMessage = outcome.message
                    )
                }
            }
        }
    }

    fun startSimulatedNavigation() {
        val current = _uiState.value as? MapUiState.RoutePreview ?: return
        val route = current.route ?: run {
            _oneShotEvent.tryEmit(UiEvent.Message("Rute belum siap untuk disimulasikan"))
            return
        }
        enterNavigation(route, isSimulated = true, previewSnapshot = current)
    }

    private fun enterNavigation(
        route: RouteResult,
        isSimulated: Boolean,
        previewSnapshot: MapUiState.RoutePreview
    ) {
        simulationJob?.cancel()
        rerouteJob?.cancel()
        savedRoutePreviewBeforeNav = previewSnapshot

        val engine = NavigationEngine(route)
        navigationEngine = engine
        _cameraMode.value = CameraMode.FOLLOW_COMPASS

        val startPoint = route.points.firstOrNull() ?: route.destination.location
        val initialEval = engine.evaluate(startPoint, isSimulated = isSimulated, nowMs = System.currentTimeMillis())

        _uiState.value = MapUiState.Navigating(
            route = route,
            progress = initialEval.progress,
            isSimulated = isSimulated,
            simulationPaused = false,
            simulationSpeedMultiplier = 1f,
            voiceEnabled = true,
            isRerouting = false,
            isCameraDetached = false
        )

        if (isSimulated) {
            val sim = RouteSimulator(route)
            routeSimulator = sim
            val initialTick = sim.advance(0.0)
            applySimulatedTick(initialTick)

            simulationJob = scope.launch {
                while (isActive) {
                    delay(250L)
                    val navState = _uiState.value as? MapUiState.Navigating ?: break
                    if (!navState.isSimulated) break
                    val tick = sim.advance(0.25)
                    applySimulatedTick(tick)
                    if (tick.finished) break
                }
            }
        } else {
            routeSimulator = null
            _telemetry.value.location?.let { evaluateRealNavigationLocation(it) }
        }
    }

    private fun applySimulatedTick(tick: RouteSimulator.SimulationTick) {
        val navState = _uiState.value as? MapUiState.Navigating ?: return
        if (!navState.isSimulated) return

        val engine = navigationEngine ?: return
        val eval = engine.evaluate(tick.location, isSimulated = true, nowMs = System.currentTimeMillis())

        _telemetry.value = _telemetry.value.copy(
            status = GpsStatus.LOCKED,
            location = tick.location,
            accuracyMeters = 5f,
            speedKmh = tick.speedKmh,
            bearingDegrees = tick.bearingDegrees,
            timestampMs = System.currentTimeMillis()
        )

        _uiState.value = navState.copy(progress = eval.progress)
        if (navState.voiceEnabled && eval.progress.speechCue != null) {
            _oneShotEvent.tryEmit(UiEvent.SpeakCue(eval.progress.speechCue))
        }
    }

    fun toggleSimulationPause() {
        val navState = _uiState.value as? MapUiState.Navigating ?: return
        if (!navState.isSimulated) return
        val nextPaused = !navState.simulationPaused
        routeSimulator?.isPaused = nextPaused
        _uiState.value = navState.copy(simulationPaused = nextPaused)
    }

    fun toggleSimulationSpeed() {
        val navState = _uiState.value as? MapUiState.Navigating ?: return
        if (!navState.isSimulated) return
        val nextSpeed = if (navState.simulationSpeedMultiplier < 1.5f) 2f else 1f
        routeSimulator?.speedMultiplier = nextSpeed
        _uiState.value = navState.copy(simulationSpeedMultiplier = nextSpeed)
    }

    fun toggleVoiceEnabled() {
        val navState = _uiState.value as? MapUiState.Navigating ?: return
        _uiState.value = navState.copy(voiceEnabled = !navState.voiceEnabled)
    }

    fun stopNavigation() {
        simulationJob?.cancel()
        rerouteJob?.cancel()
        routeSimulator = null
        navigationEngine = null
        _cameraMode.value = CameraMode.FOLLOW

        val current = _uiState.value as? MapUiState.Navigating
        if (current != null && current.progress.hasArrived) {
            savedRoutePreviewBeforeNav = null
            _uiState.value = MapUiState.Explore
        } else {
            _uiState.value = savedRoutePreviewBeforeNav ?: MapUiState.Explore
        }
    }

    fun cycleCameraMode() {
        val currentNav = _uiState.value as? MapUiState.Navigating
        if (currentNav != null) {
            recenterNavigation()
            return
        }
        val next = when (_cameraMode.value) {
            CameraMode.FREE -> CameraMode.FOLLOW
            CameraMode.FOLLOW -> CameraMode.FOLLOW_COMPASS
            CameraMode.FOLLOW_COMPASS -> CameraMode.FOLLOW
        }
        _cameraMode.value = next
        _telemetry.value.location?.let {
            _oneShotEvent.tryEmit(UiEvent.AnimateMapTo(it, 17.5))
        }
    }

    fun onUserPannedMap() {
        if (_cameraMode.value != CameraMode.FREE) {
            _cameraMode.value = CameraMode.FREE
        }
        val currentNav = _uiState.value as? MapUiState.Navigating ?: return
        if (!currentNav.isCameraDetached) {
            _uiState.value = currentNav.copy(isCameraDetached = true)
        }
    }

    fun recenterNavigation() {
        _cameraMode.value = CameraMode.FOLLOW_COMPASS
        val currentNav = _uiState.value as? MapUiState.Navigating ?: return
        _uiState.value = currentNav.copy(isCameraDetached = false)
    }

    fun setMapLayer(layer: MapLayerType) {
        _activeLayer.value = layer
        placeStore.saveLayer(layer)
    }

    fun onGpsStatusChanged(status: GpsStatus) {
        val currentNav = _uiState.value as? MapUiState.Navigating
        if (currentNav?.isSimulated == true) return
        _telemetry.value = _telemetry.value.copy(status = status)
    }

    fun onGpsTelemetryUpdate(update: GpsTelemetry) {
        val currentNav = _uiState.value as? MapUiState.Navigating
        if (currentNav?.isSimulated == true) return

        _telemetry.value = update.copy(
            compassHeadingDegrees = _telemetry.value.compassHeadingDegrees
        )

        val loc = update.location ?: return
        if (currentNav != null && !currentNav.isSimulated) {
            evaluateRealNavigationLocation(loc)
        }
    }

    fun onCompassHeadingUpdate(headingDegrees: Float) {
        _telemetry.value = _telemetry.value.copy(compassHeadingDegrees = headingDegrees)
    }

    private fun evaluateRealNavigationLocation(location: GeoPointData) {
        val navState = _uiState.value as? MapUiState.Navigating ?: return
        if (navState.isSimulated) return
        val engine = navigationEngine ?: return

        val eval = engine.evaluate(location, isSimulated = false, nowMs = System.currentTimeMillis())
        _uiState.value = navState.copy(progress = eval.progress)

        if (navState.voiceEnabled && eval.progress.speechCue != null) {
            _oneShotEvent.tryEmit(UiEvent.SpeakCue(eval.progress.speechCue))
        }

        if (eval.shouldTriggerReroute && rerouteJob?.isActive != true) {
            triggerAutoReroute(location, navState)
        }
    }

    private fun triggerAutoReroute(fromLocation: GeoPointData, navState: MapUiState.Navigating) {
        _uiState.value = navState.copy(isRerouting = true)
        rerouteJob = scope.launch {
            val outcome = routingService.fetchRoute(
                origin = RouteOrigin.CurrentGps,
                originPoint = fromLocation,
                destination = navState.route.destination,
                mode = navState.route.mode
            )
            val latest = _uiState.value as? MapUiState.Navigating ?: return@launch
            if (latest.isSimulated) return@launch

            if (outcome is RoutingOutcome.Success) {
                val newEngine = NavigationEngine(outcome.route)
                navigationEngine = newEngine
                val eval = newEngine.evaluate(fromLocation, isSimulated = false, nowMs = System.currentTimeMillis())
                _uiState.value = latest.copy(
                    route = outcome.route,
                    progress = eval.progress,
                    isRerouting = false
                )
            } else {
                _uiState.value = latest.copy(isRerouting = false)
            }
        }
    }

    fun handleBackPressed(): Boolean {
        return when (val current = _uiState.value) {
            is MapUiState.Navigating -> {
                stopNavigation()
                true
            }
            is MapUiState.RoutePreview -> {
                routeJob?.cancel()
                val dest = current.destination
                _uiState.value = MapUiState.PlaceSelected(
                    place = dest,
                    isLoadingAddress = false,
                    isFavorite = placeStore.isFavorite(dest)
                )
                true
            }
            is MapUiState.PlaceSelected -> {
                reverseGeocodeJob?.cancel()
                lastSelectedPlace = null
                _uiState.value = MapUiState.Explore
                true
            }
            is MapUiState.Search -> {
                searchJob?.cancel()
                val backToPreview = if (current.pickingOriginForRoute) savedRoutePreviewBeforeOriginPick else null
                savedRoutePreviewBeforeOriginPick = null
                _uiState.value = backToPreview ?: MapUiState.Explore
                true
            }
            MapUiState.Explore -> false
        }
    }

    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel()
        reverseGeocodeJob?.cancel()
        routeJob?.cancel()
        simulationJob?.cancel()
        rerouteJob?.cancel()
    }

    class Factory(private val placeStore: PlaceStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MapViewModel(placeStore = placeStore) as T
        }
    }
}
