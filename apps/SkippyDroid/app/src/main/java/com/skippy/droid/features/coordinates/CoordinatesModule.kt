package com.skippy.droid.features.coordinates

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skippy.droid.layers.ContextEngine
import com.skippy.droid.layers.DeviceLayer
import com.skippy.droid.layers.FeatureModule

/**
 * Coordinates module — bottom-left corner.
 * Shows GPS lat/lon, altitude, and accuracy when a fix is available.
 * Greys out and shows dashes when GPS is not yet locked.
 */
class CoordinatesModule(private val device: DeviceLayer) : FeatureModule {
    override val id = "coordinates"
    override val requiresGps = true
    override var enabled by mutableStateOf(true)
    override val zOrder = 8
    // Coordinates are useful when stationary (finding a place) or walking.
    // At driving speed they change too fast and clutter the field of view.
    override val activeIn = setOf(ContextEngine.Mode.STATIONARY, ContextEngine.Mode.WALKING)

    @Composable
    override fun Overlay() {
        val loc = device.location

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomStart
        ) {
            Column(
                modifier = Modifier.padding(start = 10.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.Start
            ) {
                if (loc != null) {
                    // Latitude
                    val latStr = formatDegrees(loc.latitude, "N", "S")
                    val lonStr = formatDegrees(loc.longitude, "E", "W")

                    Text(
                        text = latStr,
                        color = Color.Green,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = lonStr,
                        color = Color.Green,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (loc.hasAltitude()) {
                        Text(
                            text = "↑ ${loc.altitude.toInt()} m",
                            color = Color.Green.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (loc.hasAccuracy()) {
                        Text(
                            text = "±${loc.accuracy.toInt()} m",
                            color = Color.Green.copy(alpha = 0.5f),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                } else {
                    // No fix yet — dim placeholder so the slot isn't empty
                    Text(
                        text = "GPS --",
                        color = Color.Green.copy(alpha = 0.3f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    private fun formatDegrees(value: Double, posDir: String, negDir: String): String {
        val dir = if (value >= 0) posDir else negDir
        val deg = Math.abs(value)
        val d = deg.toInt()
        val mRaw = (deg - d) * 60.0
        val m = mRaw.toInt()
        val s = ((mRaw - m) * 60.0).toInt()
        return "%d°%02d'%02d\"%s".format(d, m, s, dir)
    }
}
