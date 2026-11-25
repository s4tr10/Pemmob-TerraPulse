package com.example.googlemapsproject

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.transition.TransitionManager
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.googlemapsproject.data.PlaceStorage
import com.example.googlemapsproject.databinding.ActivityMainBinding
import com.example.googlemapsproject.databinding.SheetFavoritesBinding
import com.example.googlemapsproject.databinding.SheetGpsTelemetryBinding
import com.example.googlemapsproject.databinding.SheetLayerPickerBinding
import com.example.googlemapsproject.location.CompassSensor
import com.example.googlemapsproject.location.GpsTracker
import com.example.googlemapsproject.map.MapViewController
import com.example.googlemapsproject.model.CameraMode
import com.example.googlemapsproject.model.GeoMath
import com.example.googlemapsproject.model.GeoPointData
import com.example.googlemapsproject.model.GpsStatus
import com.example.googlemapsproject.model.GpsTelemetry
import com.example.googlemapsproject.model.ManeuverType
import com.example.googlemapsproject.model.MapLayerType
import com.example.googlemapsproject.model.MapUiState
import com.example.googlemapsproject.model.PresetPlaces
import com.example.googlemapsproject.model.RouteOrigin
import com.example.googlemapsproject.model.TravelMode
import com.example.googlemapsproject.ui.MapViewModel
import com.example.googlemapsproject.ui.NavigationVoiceCoach
import com.example.googlemapsproject.ui.PlaceSuggestionAdapter
import com.example.googlemapsproject.ui.RouteStepAdapter
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MapViewModel
    private lateinit var mapController: MapViewController
    private lateinit var gpsTracker: GpsTracker
    private lateinit var compassSensor: CompassSensor
    private lateinit var voiceCoach: NavigationVoiceCoach

    private lateinit var searchAdapter: PlaceSuggestionAdapter
    private lateinit var routeStepAdapter: RouteStepAdapter

    private var telemetrySheetDialog: BottomSheetDialog? = null
    private var telemetrySheetBinding: SheetGpsTelemetryBinding? = null
    private var lastRenderedRouteKey: String? = null
    private var lastRenderedPinKey: String? = null
    private var lastStateClass: Class<*>? = null
    private var currentGpsHighFrequency: Boolean? = null
    private var wasNavigatingState = false
    private var lastCameraMode: CameraMode? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            ensureGpsEnabledAndStartTracking()
        } else {
            viewModel.onGpsStatusChanged(GpsStatus.NO_PERMISSION)
            Toast.makeText(this, "Izin lokasi diperlukan untuk pelacakan GPS", Toast.LENGTH_SHORT).show()
        }
    }

    private val gpsSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startLocationUpdatesIfPermitted(isHighFrequencyNav())
        } else {
            viewModel.onGpsStatusChanged(GpsStatus.DISABLED)
            Toast.makeText(this, "GPS belum diaktifkan", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val osmPrefs = applicationContext.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        Configuration.getInstance().load(applicationContext, osmPrefs)
        Configuration.getInstance().userAgentValue = "TerraPulse/1.0 ($packageName)"

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val placeStorage = PlaceStorage(applicationContext)
        viewModel = ViewModelProvider(this, MapViewModel.Factory(placeStorage))[MapViewModel::class.java]

        gpsTracker = GpsTracker(this)
        compassSensor = CompassSensor(this)
        voiceCoach = NavigationVoiceCoach(this)

        mapController = MapViewController(
            context = this,
            mapView = binding.mapView,
            onSingleTap = { point -> viewModel.onMapSingleTap(point) },
            onUserPanMap = { viewModel.onUserPannedMap() },
            onMapOrientationChanged = { orientationDeg ->
                binding.ivCompassNeedle.rotation = orientationDeg
            }
        )
        mapController.initialize(viewModel.activeLayer.value)

        applyEdgeToEdgeInsets()
        setupAdaptersAndInputs()
        setupClickListeners()
        setupBackNavigation()
        observeState()

        requestLocationPermissionOrStart()
    }

    override fun onStart() {
        super.onStart()
        if (gpsTracker.hasLocationPermission()) {
            startLocationUpdatesIfPermitted(isHighFrequencyNav())
        }
        compassSensor.start { heading ->
            viewModel.onCompassHeadingUpdate(heading)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        gpsTracker.stopUpdates()
        currentGpsHighFrequency = null
        compassSensor.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        telemetrySheetDialog?.dismiss()
        voiceCoach.shutdown()
    }

    private fun applyEdgeToEdgeInsets() {
        val dp12 = dpToPx(12)
        val dp16 = dpToPx(16)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            binding.topHudContainer.setPadding(dp16, sysBars.top + dp12, dp16, 0)
            binding.bottomPanelHost.setPadding(0, 0, 0, sysBars.bottom)
            binding.panelSearchOverlay.searchContentContainer.setPadding(
                dp16,
                sysBars.top + dp16,
                dp16,
                max(sysBars.bottom, ime.bottom) + dp16
            )
            binding.panelNavigationHud.navTopContainer.setPadding(dp12, sysBars.top + dp12, dp12, 0)
            binding.panelNavigationHud.navBottomContainer.setPadding(dp12, dp12, dp12, sysBars.bottom + dp12)
            insets
        }
    }

    private fun setupAdaptersAndInputs() {
        searchAdapter = PlaceSuggestionAdapter { place ->
            hideKeyboard()
            viewModel.selectPlaceFromSearch(place)
        }
        binding.panelSearchOverlay.rvSearchResults.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = searchAdapter
        }

        routeStepAdapter = RouteStepAdapter()
        binding.panelRoutePreview.rvRouteSteps.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = routeStepAdapter
        }

        binding.panelSearchOverlay.etSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                binding.panelSearchOverlay.btnSearchClear.visibility =
                    if (text.isNotEmpty()) View.VISIBLE else View.GONE
                if (viewModel.uiState.value is MapUiState.Search) {
                    viewModel.onSearchQueryChanged(text)
                }
            }
        })

        binding.panelSearchOverlay.etSearchInput.setOnEditorActionListener { v, actionId, event ->
            val isSearchAction = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isSearchAction) {
                hideKeyboard()
                viewModel.submitSearch(v.text.toString())
                true
            } else {
                false
            }
        }
    }

    private fun setupClickListeners() {
        // Top HUD
        binding.cardGpsPill.setOnClickListener { showGpsTelemetrySheet() }
        binding.cardCompass.setOnClickListener { mapController.resetNorth() }

        // Mid-Right Map Controls
        binding.btnLayerPicker.setOnClickListener { showLayerPickerSheet() }
        binding.btnZoomIn.setOnClickListener { mapController.zoomIn() }
        binding.btnZoomOut.setOnClickListener { mapController.zoomOut() }
        binding.btnMyLocation.setOnClickListener {
            if (!gpsTracker.hasLocationPermission()) {
                requestLocationPermissionOrStart()
                return@setOnClickListener
            }
            if (viewModel.telemetry.value.location == null) {
                ensureGpsEnabledAndStartTracking()
            }
            viewModel.cycleCameraMode()
        }

        // Explore Dock
        binding.panelExploreDock.cardExploreSearchTrigger.setOnClickListener {
            viewModel.openSearch(pickingOriginForRoute = false)
        }
        binding.panelExploreDock.chipFavorites.setOnClickListener { showFavoritesSheet() }
        binding.panelExploreDock.chipITS.setOnClickListener { viewModel.selectPresetPlace(PresetPlaces.ALL[0]) }
        binding.panelExploreDock.chipTuguPahlawan.setOnClickListener { viewModel.selectPresetPlace(PresetPlaces.ALL[1]) }
        binding.panelExploreDock.chipGubeng.setOnClickListener { viewModel.selectPresetPlace(PresetPlaces.ALL[2]) }
        binding.panelExploreDock.chipMonas.setOnClickListener { viewModel.selectPresetPlace(PresetPlaces.ALL[3]) }

        // Search Overlay
        binding.panelSearchOverlay.btnSearchBack.setOnClickListener {
            hideKeyboard()
            viewModel.handleBackPressed()
        }
        binding.panelSearchOverlay.btnSearchClear.setOnClickListener {
            binding.panelSearchOverlay.etSearchInput.text?.clear()
        }
        binding.panelSearchOverlay.btnClearRecents.setOnClickListener {
            viewModel.clearRecents()
        }

        // Place Sheet
        binding.panelPlaceSheet.btnClosePlace.setOnClickListener { viewModel.handleBackPressed() }
        binding.panelPlaceSheet.btnToggleFavorite.setOnClickListener {
            val state = viewModel.uiState.value as? MapUiState.PlaceSelected ?: return@setOnClickListener
            viewModel.toggleFavorite(state.place)
        }
        binding.panelPlaceSheet.btnCopyPlaceCoords.setOnClickListener {
            val state = viewModel.uiState.value as? MapUiState.PlaceSelected ?: return@setOnClickListener
            copyCoordsToClipboard(state.place.location)
        }
        binding.panelPlaceSheet.btnViewRoute.setOnClickListener {
            val state = viewModel.uiState.value as? MapUiState.PlaceSelected ?: return@setOnClickListener
            viewModel.openRoutePreview(state.place)
        }

        // Route Preview
        binding.panelRoutePreview.btnCloseRoute.setOnClickListener { viewModel.handleBackPressed() }
        binding.panelRoutePreview.btnPickRouteOrigin.setOnClickListener { showOriginPickerMenu() }
        binding.panelRoutePreview.btnSwapOriginDest.setOnClickListener { viewModel.swapOriginAndDestination() }
        binding.panelRoutePreview.chipModeCar.setOnClickListener { viewModel.selectTravelMode(TravelMode.CAR) }
        binding.panelRoutePreview.chipModeBike.setOnClickListener { viewModel.selectTravelMode(TravelMode.BIKE) }
        binding.panelRoutePreview.chipModeFoot.setOnClickListener { viewModel.selectTravelMode(TravelMode.FOOT) }
        binding.panelRoutePreview.btnToggleSteps.setOnClickListener { viewModel.toggleStepsExpanded() }
        binding.panelRoutePreview.btnRouteUseItsOrigin.setOnClickListener {
            viewModel.setManualOrigin(PresetPlaces.ALL[0])
        }
        binding.panelRoutePreview.btnRouteRetry.setOnClickListener { viewModel.retryRoute() }
        binding.panelRoutePreview.btnStartNavigation.setOnClickListener {
            viewModel.requestStartRealNavigation()
        }
        binding.panelRoutePreview.btnSimulateDemo.setOnClickListener {
            viewModel.startSimulatedNavigation()
        }

        // Navigation HUD
        binding.panelNavigationHud.btnSimSpeed.setOnClickListener { viewModel.toggleSimulationSpeed() }
        binding.panelNavigationHud.btnSimPausePlay.setOnClickListener { viewModel.toggleSimulationPause() }
        binding.panelNavigationHud.btnNavToggleVoice.setOnClickListener { viewModel.toggleVoiceEnabled() }
        binding.panelNavigationHud.btnNavRecenter.setOnClickListener { viewModel.recenterNavigation() }
        binding.panelNavigationHud.btnStopNavigation.setOnClickListener {
            voiceCoach.stop()
            viewModel.stopNavigation()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                hideKeyboard()
                if (!viewModel.handleBackPressed()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state -> renderUiState(state) }
                }
                launch {
                    viewModel.telemetry.collect { telemetry -> renderTelemetry(telemetry) }
                }
                launch {
                    viewModel.cameraMode.collect { mode -> renderCameraModeButton(mode) }
                }
                launch {
                    viewModel.activeLayer.collect { layer -> mapController.setLayer(layer) }
                }
                launch {
                    viewModel.favorites.collect {
                        val current = viewModel.uiState.value
                        if (current is MapUiState.Search) renderSearchList(current)
                    }
                }
                launch {
                    viewModel.recents.collect {
                        val current = viewModel.uiState.value
                        if (current is MapUiState.Search) renderSearchList(current)
                    }
                }
                launch {
                    viewModel.oneShotEvent.collect { event -> handleUiEvent(event) }
                }
            }
        }
    }

    private fun renderUiState(state: MapUiState) {
        val stateClass = state::class.java
        if (lastStateClass != stateClass) {
            TransitionManager.beginDelayedTransition(binding.main)
            lastStateClass = stateClass
        }

        val isNavigating = state is MapUiState.Navigating
        if (wasNavigatingState && !isNavigating) {
            mapController.resetNorth()
        }
        wasNavigatingState = isNavigating
        val isSearch = state is MapUiState.Search

        binding.topHudContainer.visibility = if (isNavigating || isSearch) View.GONE else View.VISIBLE
        binding.rightControlsContainer.visibility = if (isNavigating || isSearch) View.GONE else View.VISIBLE
        binding.bottomPanelHost.visibility = if (isNavigating || isSearch) View.GONE else View.VISIBLE

        binding.panelExploreDock.exploreDockRoot.visibility =
            if (state is MapUiState.Explore) View.VISIBLE else View.GONE
        binding.panelPlaceSheet.placeSheetRoot.visibility =
            if (state is MapUiState.PlaceSelected) View.VISIBLE else View.GONE
        binding.panelRoutePreview.routePreviewRoot.visibility =
            if (state is MapUiState.RoutePreview) View.VISIBLE else View.GONE
        binding.panelSearchOverlay.searchOverlayRoot.visibility =
            if (isSearch) View.VISIBLE else View.GONE
        binding.panelNavigationHud.navHudRoot.visibility =
            if (isNavigating) View.VISIBLE else View.GONE

        if (isNavigating) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Adjust GPS polling rate cleanly between real navigation vs exploration
        val wantHighFreq = state is MapUiState.Navigating && !state.isSimulated
        if (gpsTracker.hasLocationPermission() && currentGpsHighFrequency != wantHighFreq) {
            startLocationUpdatesIfPermitted(wantHighFreq)
        }

        when (state) {
            MapUiState.Explore -> {
                if (lastRenderedPinKey != null) {
                    mapController.clearSelectedPin()
                    lastRenderedPinKey = null
                }
                if (lastRenderedRouteKey != null) {
                    mapController.clearRoute()
                    lastRenderedRouteKey = null
                }
            }

            is MapUiState.Search -> {
                renderSearchPanel(state)
            }

            is MapUiState.PlaceSelected -> {
                if (lastRenderedRouteKey != null) {
                    mapController.clearRoute()
                    lastRenderedRouteKey = null
                }
                val pinKey = "${state.place.id}_${state.place.location.lat}_${state.place.location.lon}"
                if (lastRenderedPinKey != pinKey) {
                    mapController.showSelectedPin(state.place, animateCamera = false)
                    lastRenderedPinKey = pinKey
                }
                renderPlaceSheet(state)
            }

            is MapUiState.RoutePreview -> {
                lastRenderedPinKey = null
                renderRoutePreview(state)
            }

            is MapUiState.Navigating -> {
                renderNavigationHud(state)
            }
        }
    }

    private fun renderSearchPanel(state: MapUiState.Search) {
        val input = binding.panelSearchOverlay.etSearchInput
        if (input.text.toString() != state.query && state.query.isEmpty()) {
            input.setText("")
        }
        input.hint = if (state.pickingOriginForRoute) {
            "Cari titik awal rute..."
        } else {
            getString(R.string.search_hint)
        }
        binding.panelSearchOverlay.progressSearch.visibility =
            if (state.isLoading) View.VISIBLE else View.GONE

        renderSearchList(state)

        if (!input.hasFocus()) {
            input.requestFocus()
            showKeyboard(input)
        }
    }

    private fun renderSearchList(state: MapUiState.Search) {
        val userLoc = viewModel.telemetry.value.location
        val trimmed = state.query.trim()

        if (trimmed.length >= 2) {
            binding.panelSearchOverlay.tvSearchSectionTitle.text = "Hasil Pencarian"
            binding.panelSearchOverlay.btnClearRecents.visibility = View.GONE
            searchAdapter.submitList(state.suggestions, userLoc, PlaceSuggestionAdapter.IconMode.SEARCH_RESULT)
            binding.panelSearchOverlay.tvSearchEmpty.visibility =
                if (!state.isLoading && state.suggestions.isEmpty()) View.VISIBLE else View.GONE
            binding.panelSearchOverlay.tvSearchEmpty.text = "Tidak ditemukan hasil untuk \"$trimmed\"."
        } else {
            val recents = viewModel.recents.value
            val favs = viewModel.favorites.value
            if (recents.isNotEmpty()) {
                binding.panelSearchOverlay.tvSearchSectionTitle.text = getString(R.string.label_recent_searches)
                binding.panelSearchOverlay.btnClearRecents.visibility = View.VISIBLE
                searchAdapter.submitList(recents, userLoc, PlaceSuggestionAdapter.IconMode.RECENT)
                binding.panelSearchOverlay.tvSearchEmpty.visibility = View.GONE
            } else if (favs.isNotEmpty()) {
                binding.panelSearchOverlay.tvSearchSectionTitle.text = getString(R.string.label_favorites)
                binding.panelSearchOverlay.btnClearRecents.visibility = View.GONE
                searchAdapter.submitList(favs, userLoc, PlaceSuggestionAdapter.IconMode.FAVORITE)
                binding.panelSearchOverlay.tvSearchEmpty.visibility = View.GONE
            } else {
                binding.panelSearchOverlay.tvSearchSectionTitle.text = "Pencarian Cepat"
                binding.panelSearchOverlay.btnClearRecents.visibility = View.GONE
                searchAdapter.submitList(PresetPlaces.ALL, userLoc, PlaceSuggestionAdapter.IconMode.SEARCH_RESULT)
                binding.panelSearchOverlay.tvSearchEmpty.visibility = View.GONE
            }
        }
    }

    private fun renderPlaceSheet(state: MapUiState.PlaceSelected) {
        val place = state.place
        binding.panelPlaceSheet.tvPlaceName.text = place.name
        binding.panelPlaceSheet.tvPlaceAddress.text = place.address
        binding.panelPlaceSheet.tvPlaceCoords.text = String.format(
            Locale.US,
            "%.5f, %.5f",
            place.location.lat,
            place.location.lon
        )

        val userLoc = viewModel.telemetry.value.location
        if (userLoc != null) {
            val dist = GeoMath.distanceMeters(userLoc, place.location)
            binding.panelPlaceSheet.tvPlaceDistanceBadge.text = "${GeoMath.formatDistanceId(dist)} dari Anda"
        } else {
            binding.panelPlaceSheet.tvPlaceDistanceBadge.text = "GPS belum terkunci"
        }

        binding.panelPlaceSheet.btnToggleFavorite.setImageResource(
            if (state.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        )
    }

    private fun renderRoutePreview(state: MapUiState.RoutePreview) {
        val originLabel = when (val orig = state.origin) {
            RouteOrigin.CurrentGps -> "Lokasi Saya (GPS)"
            is RouteOrigin.Manual -> orig.place.name
        }
        binding.panelRoutePreview.tvRouteOriginName.text = originLabel
        binding.panelRoutePreview.tvRouteDestName.text = state.destination.name

        when (state.mode) {
            TravelMode.CAR -> binding.panelRoutePreview.chipGroupTravelMode.check(R.id.chipModeCar)
            TravelMode.BIKE -> binding.panelRoutePreview.chipGroupTravelMode.check(R.id.chipModeBike)
            TravelMode.FOOT -> binding.panelRoutePreview.chipGroupTravelMode.check(R.id.chipModeFoot)
        }

        binding.panelRoutePreview.layoutRouteLoading.visibility =
            if (state.isLoading) View.VISIBLE else View.GONE

        val route = state.route
        if (route != null && !state.isLoading) {
            binding.panelRoutePreview.layoutRouteSummary.visibility = View.VISIBLE
            binding.panelRoutePreview.layoutRouteError.visibility = View.GONE
            binding.panelRoutePreview.layoutRouteActions.visibility = View.VISIBLE

            binding.panelRoutePreview.tvRouteEta.text = GeoMath.formatDurationId(route.durationSeconds)
            binding.panelRoutePreview.tvRouteDistance.text = "(${GeoMath.formatDistanceId(route.distanceMeters)})"
            binding.panelRoutePreview.btnToggleSteps.text = if (state.stepsExpanded) {
                "Tutup Langkah"
            } else {
                "Lihat Langkah (${route.steps.size})"
            }
            binding.panelRoutePreview.rvRouteSteps.visibility =
                if (state.stepsExpanded) View.VISIBLE else View.GONE
            routeStepAdapter.submitSteps(route.steps)

            val routeKey = "${route.origin}_${route.destination.id}_${route.mode}_${route.points.size}_${route.distanceMeters}"
            if (lastRenderedRouteKey != routeKey) {
                mapController.showRoute(route, fitBounds = true)
                lastRenderedRouteKey = routeKey
            }
        } else {
            binding.panelRoutePreview.layoutRouteSummary.visibility = View.GONE
            binding.panelRoutePreview.rvRouteSteps.visibility = View.GONE
            binding.panelRoutePreview.layoutRouteActions.visibility = View.GONE

            if (!state.isLoading && state.errorMessage != null) {
                binding.panelRoutePreview.layoutRouteError.visibility = View.VISIBLE
                binding.panelRoutePreview.tvRouteErrorMessage.text = state.errorMessage
                binding.panelRoutePreview.btnRouteUseItsOrigin.visibility =
                    if (state.isNoRouteError || state.origin == RouteOrigin.CurrentGps) View.VISIBLE else View.GONE
            } else {
                binding.panelRoutePreview.layoutRouteError.visibility = View.GONE
            }

            if (lastRenderedRouteKey != null) {
                mapController.clearRoute()
                lastRenderedRouteKey = null
            }
        }
    }

    private fun renderNavigationHud(state: MapUiState.Navigating) {
        val routeKey = "nav_${state.route.origin}_${state.route.destination.id}_${state.route.mode}_${state.route.points.size}"
        if (lastRenderedRouteKey != routeKey) {
            mapController.showRoute(state.route, fitBounds = false)
            lastRenderedRouteKey = routeKey
        }

        val hud = binding.panelNavigationHud
        val progress = state.progress

        hud.layoutSimControls.visibility = if (state.isSimulated) View.VISIBLE else View.GONE
        if (state.isSimulated) {
            hud.btnSimSpeed.text = if (state.simulationSpeedMultiplier > 1.5f) "2x" else "1x"
            hud.btnSimPausePlay.setImageResource(
                if (state.simulationPaused) R.drawable.ic_play_demo else R.drawable.ic_pause
            )
        }

        if (progress.hasArrived) {
            hud.ivNavManeuverIcon.setImageResource(R.drawable.ic_flag_arrive)
            hud.tvNavManeuverDistance.text = "Tiba!"
            hud.tvNavInstruction.text = "Anda telah tiba di ${state.route.destination.name}"
        } else {
            hud.ivNavManeuverIcon.setImageResource(
                RouteStepAdapter.iconForManeuver(progress.currentStep.maneuverType)
            )
            hud.tvNavManeuverDistance.text = GeoMath.formatDistanceId(progress.distanceToNextManeuverMeters)
            hud.tvNavInstruction.text = progress.currentStep.instruction
        }

        hud.tvNavRerouting.visibility = if (state.isRerouting) View.VISIBLE else View.GONE
        hud.btnNavToggleVoice.setImageResource(
            if (state.voiceEnabled) R.drawable.ic_volume_on else R.drawable.ic_volume_off
        )
        hud.btnNavRecenter.visibility = if (state.isCameraDetached) View.VISIBLE else View.GONE

        val speedKmh = viewModel.telemetry.value.speedKmh.roundToInt()
        hud.tvNavSpeedValue.text = speedKmh.toString()
        hud.tvNavRemainingEta.text = if (progress.hasArrived) "Tiba di Tujuan" else GeoMath.formatDurationId(progress.remainingDurationSeconds)
        hud.tvNavRemainingDist.text = "${GeoMath.formatDistanceId(progress.remainingDistanceMeters)} tersisa"
    }

    private fun renderTelemetry(telemetry: GpsTelemetry) {
        val isNavigating = viewModel.uiState.value is MapUiState.Navigating
        mapController.updateUserLocation(telemetry, viewModel.cameraMode.value, isNavigating)

        val dotColorRes = when (telemetry.status) {
            GpsStatus.LOCKED -> R.color.tp_gps_locked
            GpsStatus.SEARCHING -> R.color.tp_gps_searching
            GpsStatus.DISABLED, GpsStatus.NO_PERMISSION -> R.color.tp_gps_error
        }
        binding.viewGpsStatusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, dotColorRes))

        when (telemetry.status) {
            GpsStatus.LOCKED -> {
                val acc = telemetry.accuracyMeters?.roundToInt() ?: 0
                binding.tvGpsPillSummary.text = "GPS ±${acc}m"
                binding.tvGpsPillSpeed.visibility = View.VISIBLE
                binding.tvGpsPillSpeed.text = "· ${telemetry.speedKmh.roundToInt()} km/j"
            }
            GpsStatus.SEARCHING -> {
                binding.tvGpsPillSummary.text = getString(R.string.gps_searching)
                binding.tvGpsPillSpeed.visibility = View.GONE
            }
            GpsStatus.DISABLED -> {
                binding.tvGpsPillSummary.text = getString(R.string.gps_disabled)
                binding.tvGpsPillSpeed.visibility = View.GONE
            }
            GpsStatus.NO_PERMISSION -> {
                binding.tvGpsPillSummary.text = getString(R.string.gps_no_permission)
                binding.tvGpsPillSpeed.visibility = View.GONE
            }
        }

        if (isNavigating) {
            binding.panelNavigationHud.tvNavSpeedValue.text = telemetry.speedKmh.roundToInt().toString()
        }

        telemetrySheetBinding?.let { bindTelemetrySheetContent(it, telemetry) }
    }

    private fun renderCameraModeButton(mode: CameraMode) {
        if (lastCameraMode == CameraMode.FOLLOW_COMPASS && mode != CameraMode.FOLLOW_COMPASS) {
            mapController.resetNorth()
        }
        if (lastCameraMode != null && lastCameraMode != mode && viewModel.uiState.value !is MapUiState.Navigating) {
            when (mode) {
                CameraMode.FOLLOW -> Toast.makeText(this, "Kamera mengikuti GPS", Toast.LENGTH_SHORT).show()
                CameraMode.FOLLOW_COMPASS -> Toast.makeText(this, "Kamera mengikuti arah kompas", Toast.LENGTH_SHORT).show()
                CameraMode.FREE -> Unit
            }
        }
        lastCameraMode = mode

        val tintColor = when (mode) {
            CameraMode.FREE -> ContextCompat.getColor(this, R.color.tp_on_surface_variant)
            CameraMode.FOLLOW -> ContextCompat.getColor(this, R.color.tp_primary)
            CameraMode.FOLLOW_COMPASS -> ContextCompat.getColor(this, R.color.tp_secondary)
        }
        binding.ivMyLocationIcon.imageTintList = ColorStateList.valueOf(tintColor)
        binding.btnMyLocation.strokeColor = tintColor
    }

    private fun handleUiEvent(event: MapViewModel.UiEvent) {
        when (event) {
            is MapViewModel.UiEvent.Message -> {
                Toast.makeText(this, event.text, Toast.LENGTH_SHORT).show()
            }
            is MapViewModel.UiEvent.SpeakCue -> {
                voiceCoach.speak(event.text)
            }
            is MapViewModel.UiEvent.AnimateMapTo -> {
                mapController.animateTo(event.point, event.zoom)
            }
            is MapViewModel.UiEvent.ConfirmManualOriginNavigation -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Titik Awal Manual Terpilih")
                    .setMessage("Titik awal rute berbeda dari lokasi GPS Anda saat ini. Ingin jalankan Simulasi Demo menyusuri rute ini, atau hitung ulang dari GPS asli?")
                    .setPositiveButton("Simulasi Demo") { _, _ ->
                        viewModel.startSimulatedNavigation()
                    }
                    .setNegativeButton("Dari GPS Asli") { _, _ ->
                        viewModel.startRealNavigationFromCurrentGps()
                    }
                    .setNeutralButton("Batal", null)
                    .show()
            }
        }
    }

    private fun showGpsTelemetrySheet() {
        val sheetBinding = SheetGpsTelemetryBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)

        telemetrySheetBinding = sheetBinding
        telemetrySheetDialog = dialog

        bindTelemetrySheetContent(sheetBinding, viewModel.telemetry.value)

        sheetBinding.btnCopyTelemetryCoords.setOnClickListener {
            val loc = viewModel.telemetry.value.location
            if (loc != null) {
                copyCoordsToClipboard(loc)
            } else {
                Toast.makeText(this, "Koordinat GPS belum tersedia", Toast.LENGTH_SHORT).show()
            }
        }
        sheetBinding.btnEnableGpsFromSheet.setOnClickListener {
            dialog.dismiss()
            requestLocationPermissionOrStart()
        }

        dialog.setOnDismissListener {
            telemetrySheetBinding = null
            telemetrySheetDialog = null
        }
        dialog.show()
    }

    private fun bindTelemetrySheetContent(
        sheet: SheetGpsTelemetryBinding,
        telemetry: GpsTelemetry
    ) {
        sheet.tvSheetGpsStatus.text = when (telemetry.status) {
            GpsStatus.LOCKED -> "TERKUNCI"
            GpsStatus.SEARCHING -> "MENCARI"
            GpsStatus.DISABLED -> "NONAKTIF"
            GpsStatus.NO_PERMISSION -> "IZIN DITOLAK"
        }
        sheet.tvTelemetryAccuracy.text = telemetry.accuracyMeters?.let { "± ${it.roundToInt()} m" } ?: "—"
        sheet.tvTelemetrySpeed.text = "${telemetry.speedKmh.roundToInt()} km/j"
        sheet.tvTelemetryAltitude.text = telemetry.altitudeMeters?.let { "${it.roundToInt()} mdpl" } ?: "—"
        val heading = telemetry.compassHeadingDegrees
        sheet.tvTelemetryHeading.text = "${heading.roundToInt()}° ${GeoMath.cardinalDirectionId(heading)}"

        val loc = telemetry.location
        sheet.tvTelemetryCoords.text = if (loc != null) {
            String.format(Locale.US, "%.6f, %.6f", loc.lat, loc.lon)
        } else {
            "Menunggu kunci satelit GPS..."
        }

        sheet.btnEnableGpsFromSheet.visibility =
            if (telemetry.status == GpsStatus.DISABLED || telemetry.status == GpsStatus.NO_PERMISSION) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun showLayerPickerSheet() {
        val sheetBinding = SheetLayerPickerBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)

        val active = viewModel.activeLayer.value
        val activeColor = ContextCompat.getColor(this, R.color.tp_primary)
        val inactiveColor = ContextCompat.getColor(this, R.color.tp_outline)

        val mapping = listOf(
            MapLayerType.STREET to sheetBinding.cardLayerStreet,
            MapLayerType.SATELLITE to sheetBinding.cardLayerSatellite,
            MapLayerType.DARK to sheetBinding.cardLayerDark,
            MapLayerType.TOPO to sheetBinding.cardLayerTopo
        )

        for ((layer, card) in mapping) {
            val isSelected = layer == active
            card.strokeColor = if (isSelected) activeColor else inactiveColor
            card.strokeWidth = dpToPx(if (isSelected) 2 else 1)
            card.setOnClickListener {
                viewModel.setMapLayer(layer)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showFavoritesSheet() {
        val sheetBinding = SheetFavoritesBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)

        val favList = viewModel.favorites.value
        if (favList.isEmpty()) {
            sheetBinding.rvFavorites.visibility = View.GONE
            sheetBinding.tvFavoritesEmpty.visibility = View.VISIBLE
        } else {
            sheetBinding.rvFavorites.visibility = View.VISIBLE
            sheetBinding.tvFavoritesEmpty.visibility = View.GONE
            val favAdapter = PlaceSuggestionAdapter { place ->
                dialog.dismiss()
                viewModel.selectPresetPlace(place)
            }
            sheetBinding.rvFavorites.layoutManager = LinearLayoutManager(this)
            sheetBinding.rvFavorites.adapter = favAdapter
            favAdapter.submitList(
                favList,
                viewModel.telemetry.value.location,
                PlaceSuggestionAdapter.IconMode.FAVORITE
            )
        }
        dialog.show()
    }

    private fun showOriginPickerMenu() {
        val options = arrayOf(
            "Lokasi Saya (GPS)",
            "Cari Lokasi Manual...",
            "Kampus ITS Surabaya",
            "Tugu Pahlawan Surabaya",
            "Stasiun Surabaya Gubeng"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Pilih Titik Awal Rute")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.useCurrentGpsAsOrigin()
                    1 -> viewModel.openSearch(pickingOriginForRoute = true)
                    2 -> viewModel.setManualOrigin(PresetPlaces.ALL[0])
                    3 -> viewModel.setManualOrigin(PresetPlaces.ALL[1])
                    4 -> viewModel.setManualOrigin(PresetPlaces.ALL[2])
                }
            }
            .show()
    }

    private fun requestLocationPermissionOrStart() {
        if (gpsTracker.hasLocationPermission()) {
            ensureGpsEnabledAndStartTracking()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun ensureGpsEnabledAndStartTracking() {
        gpsTracker.checkGpsSettings(
            onSatisfied = {
                startLocationUpdatesIfPermitted(isHighFrequencyNav())
            },
            onResolvable = { resolvable ->
                try {
                    gpsSettingsLauncher.launch(
                        IntentSenderRequest.Builder(resolvable.resolution).build()
                    )
                } catch (_: Exception) {
                    viewModel.onGpsStatusChanged(GpsStatus.DISABLED)
                }
            },
            onUnavailable = {
                viewModel.onGpsStatusChanged(GpsStatus.DISABLED)
            }
        )
    }

    private fun startLocationUpdatesIfPermitted(highFrequency: Boolean) {
        currentGpsHighFrequency = highFrequency
        gpsTracker.startUpdates(
            highFrequency = highFrequency,
            onLocation = { telemetry -> viewModel.onGpsTelemetryUpdate(telemetry) },
            onStatusChanged = { status -> viewModel.onGpsStatusChanged(status) }
        )
    }

    private fun isHighFrequencyNav(): Boolean {
        val state = viewModel.uiState.value
        return state is MapUiState.Navigating && !state.isSimulated
    }

    private fun copyCoordsToClipboard(point: GeoPointData) {
        val formatted = String.format(Locale.US, "%.6f, %.6f", point.lat, point.lon)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Koordinat TerraPulse", formatted))
        Toast.makeText(this, "Koordinat disalin: $formatted", Toast.LENGTH_SHORT).show()
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        view.post { imm.showSoftInput(view, 0) }
    }

    private fun hideKeyboard() {
        val focus = currentFocus ?: binding.panelSearchOverlay.etSearchInput
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(focus.windowToken, 0)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }
}
