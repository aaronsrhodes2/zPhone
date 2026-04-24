package local.skippy.droid.features.coordinates

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import local.skippy.droid.compositor.HudPalette
import local.skippy.droid.compositor.HudZone
import local.skippy.droid.compositor.hudFont
import local.skippy.droid.compositor.hudSp
import local.skippy.droid.layers.ContextEngine
import local.skippy.droid.layers.DeviceLayer
import local.skippy.droid.layers.FeatureModule

/**
 * Coordinates — bottom-left corner (the Captain's self: "where I am").
 * Shows GPS lat/lon, altitude, and accuracy when a fix is available.
 * Greys out and shows dashes when GPS is not yet locked.
 */
class CoordinatesModule(private val device: DeviceLayer) : FeatureModule {
    override val id = "local.skippy.coordinates"
    override val requiresGps = true
    override var enabled by mutableStateOf(true)
    override val zOrder = 8
    override val zone = HudZone.BottomStart
    // Coordinates are useful when stationary (finding a place) or walking.
    // At driving speed they change too fast and clutter the field of view.
    override val activeIn = setOf(ContextEngine.Mode.STATIONARY, ContextEngine.Mode.WALKING)

    @Composable
    override fun Overlay() {
        val loc = device.location

        // Emit pure content — Compositor wraps this in the BottomStart positioning Box.
        Column(horizontalAlignment = Alignment.Start) {
            if (loc != null) {
                val latStr = formatDegrees(loc.latitude, "N", "S")
                val lonStr = formatDegrees(loc.longitude, "E", "W")

                Text(
                    text = latStr,
                    color = HudPalette.Green,
                    fontSize = hudSp(0.9f),
                    fontFamily = hudFont,
                )
                Text(
                    text = lonStr,
                    color = HudPalette.Green,
                    fontSize = hudSp(0.9f),
                    fontFamily = hudFont,
                )
                if (loc.hasAltitude()) {
                    Text(
                        text = "↑ ${loc.altitude.toInt()} m",
                        color = HudPalette.Green.copy(alpha = 0.7f),
                        fontSize = hudSp(0.8f),
                        fontFamily = hudFont,
                    )
                }
                if (loc.hasAccuracy()) {
                    Text(
                        text = "±${loc.accuracy.toInt()} m",
                        color = HudPalette.Green.copy(alpha = 0.5f),
                        fontSize = hudSp(0.75f),
                        fontFamily = hudFont,
                    )
                }
            } else {
                // No fix yet — dim placeholder so the slot isn't empty
                Text(
                    text = "GPS --",
                    color = HudPalette.Green.copy(alpha = 0.3f),
                    fontSize = hudSp(0.9f),
                    fontFamily = hudFont,
                )
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
