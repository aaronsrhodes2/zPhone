package local.skippy.droid.features.compass

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import local.skippy.droid.compositor.HudPalette
import local.skippy.droid.compositor.HudZone
import local.skippy.droid.compositor.cardinalGlyph
import local.skippy.droid.compositor.hudFont
import local.skippy.droid.compositor.hudSp
import local.skippy.droid.layers.DeviceLayer
import local.skippy.droid.layers.FeatureModule

/**
 * Compass — top-center (represents the world outside, per the April 21 2026
 * layout doctrine). Numeric heading stacked above the cardinal glyph.
 */
class CompassModule(private val device: DeviceLayer) : FeatureModule {
    override val id = "local.skippy.compass"
    override var enabled by mutableStateOf(true)
    override val zOrder = 10
    override val zone = HudZone.TopCenter

    @Composable
    override fun Overlay() {
        // Read directly from DeviceLayer — headingDegrees is backed by mutableDoubleStateOf
        // so Compose recomposes automatically when the sensor updates it.
        val heading = device.headingDegrees
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${heading.toInt()}°",
                color = HudPalette.Amber,           // numeric metric
                fontSize = hudSp(1.1f),
                fontFamily = hudFont,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = cardinalGlyph(heading),
                color = HudPalette.White,           // content label
                fontSize = hudSp(0.85f),
                fontFamily = hudFont
            )
        }
    }
}
