package local.skippy.droid.features.speed

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import local.skippy.droid.compositor.HudPalette
import local.skippy.droid.compositor.HudZone
import local.skippy.droid.compositor.hudFont
import local.skippy.droid.compositor.hudSp
import local.skippy.droid.layers.ContextEngine
import local.skippy.droid.layers.DeviceLayer
import local.skippy.droid.layers.FeatureModule

/**
 * Speed — top-left corner (glance-and-dismiss).
 * Auto-hides when speed is below [MIN_DISPLAY_KMH] (GPS noise / standing still).
 * Colour-coded: cyan ≤ 15 km/h (walking), light-green ≤ 60 km/h, amber ≤ 100, red > 100.
 * Context Engine (Layer 3) uses the same [DRIVING_THRESHOLD_KMH] for driving mode.
 */
class SpeedModule(private val device: DeviceLayer) : FeatureModule {
    override val id = "local.skippy.speed"
    override val requiresGps = true
    override var enabled by mutableStateOf(true)
    override val zOrder = 9
    override val zone = HudZone.TopStart
    // Speed is only meaningful when actually moving
    override val activeIn = setOf(ContextEngine.Mode.WALKING, ContextEngine.Mode.DRIVING)

    companion object {
        const val MIN_DISPLAY_KMH = 2.0f          // below this = stationary, hide
        const val DRIVING_THRESHOLD_KMH = 15.0f   // above this = driving mode
    }

    @Composable
    override fun Overlay() {
        val speedKmh = device.speedMps * 3.6f
        if (speedKmh < MIN_DISPLAY_KMH) return     // invisible when standing still

        Text(
            text = "${speedKmh.toInt()} km/h",
            // Speed is a numeric metric (Session 7 palette doctrine) — Amber always.
            // Previous version colored by speed band; that's an orthogonal concern
            // the Captain can surface elsewhere if needed.
            color = HudPalette.Amber,
            fontSize = hudSp(1.0f),
            fontFamily = hudFont,
            fontWeight = FontWeight.Bold
        )
    }
}
