package com.example.googlemapsproject.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

class CompassSensor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val accelData = FloatArray(3)
    private val magData = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false

    private var smoothedHeading: Float? = null
    private var lastEmittedHeading: Float? = null
    private var listener: ((Float) -> Unit)? = null

    fun start(onHeadingChanged: (Float) -> Unit) {
        listener = onHeadingChanged
        val mgr = sensorManager ?: return

        if (rotationSensor != null) {
            mgr.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            accelerometer?.let { mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            magnetometer?.let { mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        listener = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        var rawAzimuthDeg: Float? = null

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                rawAzimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelData, 0, 3)
                hasAccel = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magData, 0, 3)
                hasMag = true
            }
        }

        if (rawAzimuthDeg == null && hasAccel && hasMag) {
            if (SensorManager.getRotationMatrix(rotationMatrix, null, accelData, magData)) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                rawAzimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            }
        }

        val azimuth = rawAzimuthDeg ?: return
        val normalized = ((azimuth % 360f) + 360f) % 360f

        val prev = smoothedHeading
        val next = if (prev == null) {
            normalized
        } else {
            val delta = shortestAngleDelta(prev, normalized)
            ((prev + SMOOTHING_ALPHA * delta) % 360f + 360f) % 360f
        }
        smoothedHeading = next

        val lastSent = lastEmittedHeading
        if (lastSent == null || abs(shortestAngleDelta(lastSent, next)) >= MIN_EMIT_DELTA_DEG) {
            lastEmittedHeading = next
            listener?.invoke(next)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun shortestAngleDelta(fromDeg: Float, toDeg: Float): Float {
        return ((toDeg - fromDeg + 540f) % 360f) - 180f
    }

    companion object {
        private const val SMOOTHING_ALPHA = 0.15f
        private const val MIN_EMIT_DELTA_DEG = 1.5f
    }
}
