package com.example.googlemapsproject.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.googlemapsproject.model.GeoPointData
import com.example.googlemapsproject.model.GpsStatus
import com.example.googlemapsproject.model.GpsTelemetry
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

class GpsTracker(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val settingsClient = LocationServices.getSettingsClient(context)

    private var locationCallback: LocationCallback? = null
    private var currentCancellationToken: CancellationTokenSource? = null
    private var isTracking = false
    private var currentHighFrequency = false

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isLocationProviderEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun checkGpsSettings(
        onSatisfied: () -> Unit,
        onResolvable: (ResolvableApiException) -> Unit,
        onUnavailable: () -> Unit
    ) {
        val request = buildLocationRequest(highFrequency = false)
        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(request)
            .setAlwaysShow(true)
            .build()

        settingsClient.checkLocationSettings(settingsRequest)
            .addOnSuccessListener { onSatisfied() }
            .addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    onResolvable(exception)
                } else {
                    onUnavailable()
                }
            }
    }

    @SuppressLint("MissingPermission")
    fun startUpdates(
        highFrequency: Boolean,
        onLocation: (GpsTelemetry) -> Unit,
        onStatusChanged: (GpsStatus) -> Unit
    ) {
        if (!hasLocationPermission()) {
            onStatusChanged(GpsStatus.NO_PERMISSION)
            return
        }

        if (isTracking && currentHighFrequency == highFrequency) {
            return
        }
        stopUpdates()

        isTracking = true
        currentHighFrequency = highFrequency
        onStatusChanged(if (isLocationProviderEnabled()) GpsStatus.SEARCHING else GpsStatus.DISABLED)

        val request = buildLocationRequest(highFrequency)

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                onLocation(toTelemetry(loc))
            }
        }
        locationCallback = callback

        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

        // Fast initial fix from lastLocation + fresh single fix if needed
        fusedClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                onLocation(toTelemetry(loc))
            }
        }

        currentCancellationToken?.cancel()
        val cts = CancellationTokenSource()
        currentCancellationToken = cts
        fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    onLocation(toTelemetry(loc))
                }
            }
    }

    fun stopUpdates() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
        currentCancellationToken?.cancel()
        currentCancellationToken = null
        isTracking = false
    }

    private fun buildLocationRequest(highFrequency: Boolean): LocationRequest {
        val intervalMs = if (highFrequency) 1000L else 2500L
        val minIntervalMs = if (highFrequency) 500L else 1200L
        val minDistanceMeters = if (highFrequency) 1f else 2f

        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(minIntervalMs)
            .setMinUpdateDistanceMeters(minDistanceMeters)
            .build()
    }

    private fun toTelemetry(location: Location): GpsTelemetry {
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        val speedKmh = if (location.hasSpeed()) (location.speed * 3.6f).coerceAtLeast(0f) else 0f
        val altitude = if (location.hasAltitude()) location.altitude else null
        val bearing = if (location.hasBearing()) location.bearing else null
        val status = if (accuracy == null || accuracy <= 50f) GpsStatus.LOCKED else GpsStatus.SEARCHING

        return GpsTelemetry(
            status = status,
            location = GeoPointData(location.latitude, location.longitude),
            accuracyMeters = accuracy,
            speedKmh = speedKmh,
            altitudeMeters = altitude,
            bearingDegrees = bearing,
            timestampMs = location.time
        )
    }
}
