package com.example.googlemapsproject.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.example.googlemapsproject.R
import com.example.googlemapsproject.model.CameraMode
import com.example.googlemapsproject.model.GeoPointData
import com.example.googlemapsproject.model.GpsTelemetry
import com.example.googlemapsproject.model.MapLayerType
import com.example.googlemapsproject.model.PlaceItem
import com.example.googlemapsproject.model.PresetPlaces
import com.example.googlemapsproject.model.RouteOrigin
import com.example.googlemapsproject.model.RouteResult
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.abs
import kotlin.math.hypot

class MapViewController(
    private val context: Context,
    private val mapView: MapView,
    private val onSingleTap: (GeoPointData) -> Unit,
    private val onUserPanMap: () -> Unit,
    private val onMapOrientationChanged: (Float) -> Unit
) {

    private val density = context.resources.displayMetrics.density
    private val userLocationOverlay = UserLocationOverlay(density)

    private var selectedPinMarker: Marker? = null
    private var originMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var routeCasingPolyline: Polyline? = null
    private var routeFillPolyline: Polyline? = null

    private var currentLayer: MapLayerType? = null
    private var lastOrientationReported = Float.NaN
    private var wasNavigatingLocked = false

    @SuppressLint("ClickableViewAccessibility")
    fun initialize(initialLayer: MapLayerType) {
        mapView.apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(15.0)
            val defaultCenter = PresetPlaces.ALL.first().location
            controller.setCenter(GeoPoint(defaultCenter.lat, defaultCenter.lon))
        }

        setLayer(initialLayer)

        val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onSingleTap(GeoPointData(p.latitude, p.longitude))
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                onSingleTap(GeoPointData(p.latitude, p.longitude))
                return true
            }
        })
        mapView.overlays.add(0, eventsOverlay)
        mapView.overlays.add(userLocationOverlay)

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        var downX = 0f
        var downY = 0f
        mapView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && hypot(event.x - downX, event.y - downY) > touchSlop) {
                        onUserPanMap()
                    }
                }
            }
            false
        }

        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                reportOrientationIfChanged()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                reportOrientationIfChanged()
                return false
            }
        })
    }

    fun setLayer(layer: MapLayerType) {
        if (currentLayer == layer) return
        currentLayer = layer
        mapView.setTileSource(MapTileSources.tileSourceFor(layer))
        mapView.invalidate()
    }

    fun updateUserLocation(
        telemetry: GpsTelemetry,
        cameraMode: CameraMode,
        isNavigating: Boolean
    ) {
        val heading = if (isNavigating && telemetry.bearingDegrees != null) {
            telemetry.bearingDegrees
        } else {
            telemetry.compassHeadingDegrees
        }

        val changed = userLocationOverlay.update(
            location = telemetry.location,
            accuracyMeters = telemetry.accuracyMeters,
            headingDegrees = heading,
            isNavigationMode = isNavigating
        )

        val loc = telemetry.location
        if (loc != null) {
            val geo = GeoPoint(loc.lat, loc.lon)
            if (isNavigating && cameraMode != CameraMode.FREE) {
                if (!wasNavigatingLocked) {
                    mapView.controller.animateTo(geo, 18.0, 500L)
                    wasNavigatingLocked = true
                } else {
                    mapView.controller.animateTo(geo, null, 220L)
                }
                mapView.mapOrientation = -heading
                reportOrientationIfChanged()
            } else {
                wasNavigatingLocked = false
                when (cameraMode) {
                    CameraMode.FREE -> Unit
                    CameraMode.FOLLOW -> {
                        mapView.controller.animateTo(geo)
                    }
                    CameraMode.FOLLOW_COMPASS -> {
                        mapView.controller.animateTo(geo)
                        mapView.mapOrientation = -heading
                        reportOrientationIfChanged()
                    }
                }
            }
        } else {
            wasNavigatingLocked = false
        }

        if (changed) {
            mapView.invalidate()
        }
    }

    fun showSelectedPin(place: PlaceItem, animateCamera: Boolean) {
        clearSelectedPin()
        val marker = Marker(mapView).apply {
            position = GeoPoint(place.location.lat, place.location.lon)
            title = place.name
            snippet = place.address
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(context, R.drawable.ic_pin_destination)
            setInfoWindow(null)
        }
        selectedPinMarker = marker
        mapView.overlays.add(marker)
        ensureUserOverlayOnTop()

        if (animateCamera) {
            animateTo(place.location, 17.0)
        } else {
            mapView.invalidate()
        }
    }

    fun clearSelectedPin() {
        selectedPinMarker?.let { mapView.overlays.remove(it) }
        selectedPinMarker = null
        mapView.invalidate()
    }

    fun showRoute(route: RouteResult, fitBounds: Boolean) {
        clearRoute()
        clearSelectedPin()

        val geoPoints = route.points.map { GeoPoint(it.lat, it.lon) }
        if (geoPoints.isEmpty()) return

        val casing = Polyline(mapView).apply {
            setPoints(geoPoints)
            outlinePaint.color = Color.argb(200, 15, 23, 42)
            outlinePaint.strokeWidth = 16f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        }

        val fill = Polyline(mapView).apply {
            setPoints(geoPoints)
            outlinePaint.color = Color.parseColor("#14B8A6")
            outlinePaint.strokeWidth = 10f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        }

        routeCasingPolyline = casing
        routeFillPolyline = fill
        mapView.overlays.add(casing)
        mapView.overlays.add(fill)

        if (route.origin is RouteOrigin.Manual) {
            val originMk = Marker(mapView).apply {
                position = GeoPoint(route.origin.place.location.lat, route.origin.place.location.lon)
                title = route.origin.place.name
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = ContextCompat.getDrawable(context, R.drawable.ic_pin_origin)
                setInfoWindow(null)
            }
            originMarker = originMk
            mapView.overlays.add(originMk)
        }

        val destMk = Marker(mapView).apply {
            position = GeoPoint(route.destination.location.lat, route.destination.location.lon)
            title = route.destination.name
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(context, R.drawable.ic_pin_destination)
            setInfoWindow(null)
        }
        destinationMarker = destMk
        mapView.overlays.add(destMk)
        ensureUserOverlayOnTop()

        if (fitBounds && geoPoints.size >= 2) {
            mapView.post {
                try {
                    val box = BoundingBox.fromGeoPoints(geoPoints)
                    val latSpan = (box.latNorth - box.latSouth).coerceAtLeast(0.004)
                    val padded = BoundingBox(
                        box.latNorth + 0.002,
                        box.lonEast + 0.002,
                        box.latSouth - 0.002 - latSpan * 0.45,
                        box.lonWest - 0.002
                    )
                    mapView.zoomToBoundingBox(padded, true, (90 * density).toInt())
                } catch (_: Exception) {
                    animateTo(route.destination.location, 15.0)
                }
            }
        } else {
            mapView.invalidate()
        }
    }

    fun clearRoute() {
        routeCasingPolyline?.let { mapView.overlays.remove(it) }
        routeFillPolyline?.let { mapView.overlays.remove(it) }
        originMarker?.let { mapView.overlays.remove(it) }
        destinationMarker?.let { mapView.overlays.remove(it) }
        routeCasingPolyline = null
        routeFillPolyline = null
        originMarker = null
        destinationMarker = null
        mapView.invalidate()
    }

    fun resetNorth() {
        mapView.mapOrientation = 0f
        reportOrientationIfChanged()
        mapView.invalidate()
    }

    fun animateTo(point: GeoPointData, zoom: Double = 17.5) {
        mapView.controller.animateTo(GeoPoint(point.lat, point.lon), zoom, 750L)
    }

    fun zoomIn() {
        mapView.controller.zoomIn()
    }

    fun zoomOut() {
        mapView.controller.zoomOut()
    }

    private fun ensureUserOverlayOnTop() {
        mapView.overlays.remove(userLocationOverlay)
        mapView.overlays.add(userLocationOverlay)
    }

    private fun reportOrientationIfChanged() {
        val current = mapView.mapOrientation
        if (lastOrientationReported.isNaN() || abs(current - lastOrientationReported) >= 0.5f) {
            lastOrientationReported = current
            onMapOrientationChanged(current)
        }
    }
}
