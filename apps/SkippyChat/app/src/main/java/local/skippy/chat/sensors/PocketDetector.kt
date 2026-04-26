package local.skippy.chat.sensors

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import local.skippy.chat.admin.SkippyDeviceAdmin

/**
 * Pocket detector — uses the proximity sensor to detect when the Captain
 * has put the phone away, then locks the screen via DevicePolicyManager.
 *
 * Behaviour:
 *   - Proximity sensor covered for ≥ 1.5 s → call [lockNow].
 *   - Proximity sensor uncovered → cancel pending lock.
 *   - If DeviceAdmin is not active, pocket-lock is silently skipped.
 *   - If the device has no proximity sensor, this class is a no-op.
 *
 * Lifecycle: call [start] in Activity.onStart, [stop] in Activity.onStop.
 */
class PocketDetector(private val context: Context) {

    private val sm      = context.getSystemService(SensorManager::class.java)
    private val prox    = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    private val lockRunnable = Runnable { lockNow() }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // Proximity reports distance in cm. When the value is below the
            // sensor's maximum range the screen is covered (hand, pocket, etc.).
            val covered = event.values[0] < event.sensor.maximumRange
            if (covered) {
                // Arm: fire lockNow after 1.5 s of sustained coverage.
                handler.postDelayed(lockRunnable, POCKET_DELAY_MS)
            } else {
                // Disarm: phone back in hand, cancel pending lock.
                handler.removeCallbacks(lockRunnable)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    /**
     * Start listening. Safe to call multiple times (idempotent).
     * No-op if the device has no proximity sensor.
     */
    fun start() {
        if (started || prox == null) return
        sm.registerListener(listener, prox, SensorManager.SENSOR_DELAY_NORMAL)
        started = true
        Log.d(TAG, "proximity listener registered")
    }

    /**
     * Stop listening and cancel any pending lock. Call in onStop.
     */
    fun stop() {
        if (!started) return
        sm.unregisterListener(listener)
        handler.removeCallbacks(lockRunnable)
        started = false
        Log.d(TAG, "proximity listener unregistered")
    }

    private fun lockNow() {
        val dpm   = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = ComponentName(context, SkippyDeviceAdmin::class.java)
        if (!dpm.isAdminActive(admin)) {
            // Not activated — silently skip. Run the adb command to enable:
            //   adb shell dpm set-active-admin local.skippy.chat/.admin.SkippyDeviceAdmin
            Log.d(TAG, "device admin not active — pocket-lock skipped")
            return
        }
        Log.i(TAG, "proximity sustained → locking screen")
        dpm.lockNow()
    }

    private companion object {
        const val TAG             = "Local.Skippy.Pocket"
        const val POCKET_DELAY_MS = 1500L   // 1.5 s of sustained coverage → lock
    }
}
