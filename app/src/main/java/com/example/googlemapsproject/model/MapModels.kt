package com.example.googlemapsproject.model

data class GeoPointData(
    val lat: Double,
    val lon: Double
)

data class PlaceItem(
    val id: String,
    val name: String,
    val address: String,
    val location: GeoPointData,
    val category: String = ""
)

sealed class RouteOrigin {
    data object CurrentGps : RouteOrigin()
    data class Manual(val place: PlaceItem) : RouteOrigin()
}

enum class TravelMode(
    val labelId: String,
    val osrmSubpath: String,
    val fallbackSpeedMps: Double
) {
    CAR("Mobil", "routed-car", 11.1),
    BIKE("Sepeda", "routed-bike", 4.44),
    FOOT("Jalan Kaki", "routed-foot", 1.33)
}

enum class MapLayerType(
    val displayName: String,
    val subtitle: String
) {
    STREET("Peta Jalan", "Standar & detail jalan"),
    SATELLITE("Satelit", "Citra udara resolusi tinggi"),
    DARK("Mode Gelap", "Kontras nyaman malam hari"),
    TOPO("Topografi", "Kontur medan & elevasi")
}

enum class CameraMode {
    FREE,
    FOLLOW,
    FOLLOW_COMPASS
}

enum class GpsStatus {
    LOCKED,
    SEARCHING,
    DISABLED,
    NO_PERMISSION
}

data class GpsTelemetry(
    val status: GpsStatus = GpsStatus.SEARCHING,
    val location: GeoPointData? = null,
    val accuracyMeters: Float? = null,
    val speedKmh: Float = 0f,
    val altitudeMeters: Double? = null,
    val bearingDegrees: Float? = null,
    val compassHeadingDegrees: Float = 0f,
    val timestampMs: Long = 0L
)

enum class ManeuverType {
    DEPART,
    STRAIGHT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    UTURN,
    ROUNDABOUT,
    ARRIVE
}

data class RouteStep(
    val instruction: String,
    val roadName: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val maneuverType: ManeuverType,
    val location: GeoPointData
)

data class RouteResult(
    val origin: RouteOrigin,
    val destination: PlaceItem,
    val mode: TravelMode,
    val points: List<GeoPointData>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<RouteStep>,
    val usedFallbackProfile: Boolean = false
)

data class NavigationProgress(
    val currentStepIndex: Int,
    val currentStep: RouteStep,
    val distanceToNextManeuverMeters: Double,
    val remainingDistanceMeters: Double,
    val remainingDurationSeconds: Double,
    val isOffRoute: Boolean,
    val hasArrived: Boolean,
    val speechCue: String? = null
)

sealed interface MapUiState {
    data object Explore : MapUiState

    data class Search(
        val query: String = "",
        val isLoading: Boolean = false,
        val suggestions: List<PlaceItem> = emptyList(),
        val pickingOriginForRoute: Boolean = false
    ) : MapUiState

    data class PlaceSelected(
        val place: PlaceItem,
        val isLoadingAddress: Boolean = false,
        val isFavorite: Boolean = false
    ) : MapUiState

    data class RoutePreview(
        val origin: RouteOrigin,
        val destination: PlaceItem,
        val mode: TravelMode = TravelMode.CAR,
        val isLoading: Boolean = false,
        val route: RouteResult? = null,
        val errorMessage: String? = null,
        val isNoRouteError: Boolean = false,
        val stepsExpanded: Boolean = false
    ) : MapUiState

    data class Navigating(
        val route: RouteResult,
        val progress: NavigationProgress,
        val isSimulated: Boolean,
        val simulationPaused: Boolean = false,
        val simulationSpeedMultiplier: Float = 1f,
        val voiceEnabled: Boolean = true,
        val isRerouting: Boolean = false,
        val isCameraDetached: Boolean = false
    ) : MapUiState
}

object PresetPlaces {
    val ALL: List<PlaceItem> = listOf(
        PlaceItem(
            id = "preset_its",
            name = "Kampus ITS Surabaya",
            address = "Sukolilo, Surabaya, Jawa Timur",
            location = GeoPointData(-7.282356, 112.794925),
            category = "Kampus"
        ),
        PlaceItem(
            id = "preset_tugu_pahlawan",
            name = "Tugu Pahlawan",
            address = "Alun-alun Contong, Bubutan, Surabaya",
            location = GeoPointData(-7.245875, 112.737846),
            category = "Landmark"
        ),
        PlaceItem(
            id = "preset_gubeng",
            name = "Stasiun Surabaya Gubeng",
            address = "Pacar Keling, Tambaksari, Surabaya",
            location = GeoPointData(-7.265403, 112.752063),
            category = "Transportasi"
        ),
        PlaceItem(
            id = "preset_monas",
            name = "Monas Jakarta",
            address = "Gambir, Jakarta Pusat, DKI Jakarta",
            location = GeoPointData(-6.175392, 106.827153),
            category = "Landmark"
        )
    )
}
