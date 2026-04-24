package local.skippy.droid.layers

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

    // Heading is driven by exactly ONE source at a time. Two sources are
    // registered; whichever proves itself alive first "wins" for the rest
    // of this process, and the other is ignored.
    //
    //  Preferred — TYPE_ROTATION_VECTOR
    //    Android's fused orientation. Higher rate, pre-calibrated, and the
    //    emulator's Virtual Sensors panel populates it directly. On a
    //    healthy device this fires within the first few sensor ticks.
    //
    //  Fallback — TYPE_ACCELEROMETER + TYPE_MAGNETIC_FIELD
    //    Classic compose-your-own-orientation path. Only consulted if
    //    rotation vector never shows up (unusual, but possible on a minimal
    //    emulator profile or a device with a dead fusion sensor).
    //
    // One-way latch: once rotation vector has fired, `useFallback` is
    // permanently false and acc+mag events are dropped on the floor. This
    // prevents the two sources from ping-ponging the heading value on every
    // tick (they disagree by a few degrees, which reads as flutter).
    //
    // Why not just unregister the fallback listeners? We'd need to outlive
    // onAccuracyChanged races and re-register on stop/restart. Cheaper to
    // let the events arrive and discard them.
    private val acceleration = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private val rotationVector = FloatArray(5)
    private var useFallback = true   // flipped to false on first rotation-vector event
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    useFallback = false
                    // Values beyond index 3 may be absent on older devices;
                    // copy only what the event provides.
                    val n = minOf(event.values.size, rotationVector.size)
                    System.arraycopy(event.values, 0, rotationVector, 0, n)
                    val r = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(r, rotationVector)
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(r, orientation)
                    phoneHeadingDegrees = ((Math.toDegrees(orientation[0].toDouble()) + 360) % 360)
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    if (useFallback) System.arraycopy(event.values, 0, acceleration, 0, 3)
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    if (!useFallback) return
                    System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                    val r = FloatArray(9)
                    val i = FloatArray(9)
                    if (SensorManager.getRotationMatrix(r, i, acceleration, geomagnetic)) {
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
        // Register all three — whichever the device/emulator populates wins.
        listOf(
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_MAGNETIC_FIELD,
        ).forEach { type ->
            sensorManager.getDefaultSensor(type)?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            }
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
