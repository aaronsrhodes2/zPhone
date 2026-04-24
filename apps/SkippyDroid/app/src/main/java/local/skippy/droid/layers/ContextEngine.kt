package local.skippy.droid.layers

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Layer 3 — Context Engine.
 *
 * Watches [DeviceLayer.speedMps] and maintains the current activity mode.
 * Mode changes require [HYSTERESIS_TICKS] consecutive polls at the new speed
 * band — this prevents flickering when stopped at a traffic light or when
 * GPS speed briefly spikes.
 *
 * Consuming composables read [currentMode]; because the backing field is
 * [mutableStateOf], they recompose automatically on mode changes.
 *
 * Thresholds:
 *   STATIONARY : < 2 km/h
 *   WALKING    : 2–15 km/h
 *   DRIVING    : ≥ 15 km/h
 */
class ContextEngine(private val device: DeviceLayer) {

    enum class Mode { STATIONARY, WALKING, DRIVING }

    var currentMode: Mode by mutableStateOf(Mode.STATIONARY)
        private set

    private val scope = MainScope()
    private var job: Job? = null

    // Hysteresis state — only commit a mode change after HYSTERESIS_TICKS polls agree
    private var pendingMode: Mode = Mode.STATIONARY
    private var pendingTicks: Int = 0

    companion object {
        const val WALKING_KMH  = 2.0f    // above this = moving (show speed module)
        const val DRIVING_KMH  = 15.0f   // above this = driving mode

        private const val POLL_MS          = 2_000L  // check speed every 2 s
        private const val HYSTERESIS_TICKS = 3        // 3 × 2 s = 6 s to commit a mode change
    }

    fun start() {
        job = scope.launch {
            while (true) {
                tick()
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        // Reset hysteresis but keep currentMode — avoids jarring jump on resume
        pendingTicks = 0
    }

    private fun tick() {
        val raw = modeFromSpeed(device.speedMps * 3.6f)

        if (raw == pendingMode) {
            pendingTicks++
            if (pendingTicks >= HYSTERESIS_TICKS && raw != currentMode) {
                currentMode = raw
            }
        } else {
            pendingMode = raw
            pendingTicks = 1
        }
    }

    private fun modeFromSpeed(kmh: Float): Mode = when {
        kmh >= DRIVING_KMH -> Mode.DRIVING
        kmh >= WALKING_KMH -> Mode.WALKING
        else               -> Mode.STATIONARY
    }
}
