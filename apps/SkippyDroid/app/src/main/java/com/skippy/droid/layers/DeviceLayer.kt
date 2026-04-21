package com.skippy.droid.layers

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Layer 1: single source of truth for all device sensor inputs. */
class DeviceLayer(context: Context) {

    // GPS
    var location: Location? by mutableStateOf(null)
    var speedMps: Float by mutableFloatStateOf(0f)

    // Glasses IMU — preferred heading source when VITURE is connected.
    // Wired by MainActivity once GlassesLayer has the VITURE SDK integrated.
    var glasses: GlassesLayer? = null

    // Phone IMU — fallback compass heading in degrees [0, 360)
    private var phoneHeadingDegrees: Double by mutableDoubleStateOf(0.0)

    /**
     * Best available heading in degrees [0, 360).
     *
     * Priority:
     *   1. VITURE glasses IMU  — head-mounted, tracks where you're looking
     *   2. Phone magnetometer  — fallback when glasses not connected / SDK not wired
     *
     * NavigationModule reads this for direction-dot bearing.  When the VITURE SDK
     * is integrated, plugging in the glasses will automatically upgrade the heading
     * source without changing any navigation code.
     */
    val headingDegrees: Double
        get() = glasses?.headingDegrees ?: phoneHeadingDegrees

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val locationListener = LocationListener { loc ->
        location = loc
        speedMps = if (loc.hasSpeed()) loc.speed else 0f
    }

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GRAVITY -> System.arraycopy(event.values, 0, gravity, 0, 3)
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                    val r = FloatArray(9)
                    val i = FloatArray(9)
                    if (SensorManager.getRotationMatrix(r, i, gravity, geomagnetic)) {
                        val orientation = FloatArray(3)
                        SensorManager.getOrientation(r, orientation)
                        phoneHeadingDegrees = ((Math.toDegrees(orientation[0].toDouble()) + 360) % 360)
                    }
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    fun start() {
        startLocationUpdates()
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    /** Call after ACCESS_FINE_LOCATION is granted to begin receiving fixes immediately. */
    fun onLocationPermissionGranted() = startLocationUpdates()

    private fun startLocationUpdates() {
        // Try GPS first, then network as fallback (network works in emulator without a GPS fix).
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 1_000L, 1f, locationListener)
                }
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {}
        }
    }

    fun stop() {
        locationManager.removeUpdates(locationListener)
        sensorManager.unregisterListener(sensorListener)
    }
}
