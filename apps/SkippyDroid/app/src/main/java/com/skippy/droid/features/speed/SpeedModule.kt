package com.skippy.droid.features.speed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skippy.droid.layers.DeviceLayer
import com.skippy.droid.layers.FeatureModule

/**
 * Speed module — top-left, below the compass stack.
 * Auto-hides when speed is below MIN_DISPLAY_KMH (GPS noise / standing still).
 * Colour-coded: cyan ≤ 15 km/h (walking), yellow ≤ 60 km/h, orange ≤ 100, red > 100.
 * Context Engine (Layer 3) uses the same speed threshold for driving mode.
 */
class SpeedModule(private val device: DeviceLayer) : FeatureModule {
    override val id = "speed"
    override val requiresGps = true
    override var enabled by mutableStateOf(true)
    override val zOrder = 9

    companion object {
        const val MIN_DISPLAY_KMH = 2.0f          // below this = stationary, hide
        const val DRIVING_THRESHOLD_KMH = 15.0f   // above this = driving mode
    }

    @Composable
    override fun Overlay() {
        val speedKmh = device.speedMps * 3.6f
        if (speedKmh < MIN_DISPLAY_KMH) return     // invisible when standing still

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopStart
        ) {
            Text(
                text = "${speedKmh.toInt()} km/h",
                color = speedColor(speedKmh),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp, top = 58.dp)
            )
        }
    }

    private fun speedColor(kmh: Float): Color = when {
        kmh <= DRIVING_THRESHOLD_KMH -> Color.Cyan          // walking pace
        kmh <= 60f  -> Color(0xFF88FF88)                    // normal driving — light green
        kmh <= 100f -> Color(0xFFFFAA00)                    // fast — amber
        else        -> Color.Red                             // very fast
    }
}
