package com.skippy.droid.features.navigation

import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skippy.droid.layers.DeviceLayer
import com.skippy.droid.layers.FeatureModule
import kotlin.math.cos
import kotlin.math.sin

/**
 * Navigation overlay — direction dots + bottom-centre instruction panel.
 *
 * Direction dots: a perspective trail of blue circles radiating from the bottom-centre
 * of the screen (your feet) toward the next step endpoint, rotated by the compass
 * heading so "ahead" is always up on screen. Think Pac-Man pellets on the ground.
 *
 * Instruction panel: maneuver arrow, street name, distance to step end, live ETA.
 *
 * Self-hides when [NavigationEngine.isNavigating] is false.
 */
class NavigationModule(
    private val nav: NavigationEngine,
    private val device: DeviceLayer
) : FeatureModule {

    override val id = "navigation"
    override val requiresGps = true
    override val requiresNetwork = true
    override var enabled by mutableStateOf(true)
    override val zOrder = 20    // above all other modules while navigating

    @Composable
    override fun Overlay() {
        // ── Error banner ──────────────────────────────────────────────────────
        nav.error?.let { err ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Text(
                    text = "⚠ $err",
                    color = Color(0xFFFF4444),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(bottom = 24.dp)
                        .background(Color.Black.copy(alpha = 0.80f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
            return
        }

        if (!nav.isNavigating) return
        val state = nav.state ?: return
        val step  = state.currentStep ?: return

        // ── Direction dots — AR ground-plane cue ─────────────────────────────
        // Read location and heading from DeviceLayer — both are mutableStateOf
        // so the Canvas recomposes automatically as you move / turn.
        val loc     = device.location
        val heading = device.headingDegrees

        if (loc != null) {
            val relBearing = relativeBearing(loc, step.endLat, step.endLng, heading)
            DirectionDots(relBearing)
        }

        // ── Instruction panel ─────────────────────────────────────────────────
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.80f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Maneuver arrow + instruction
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = maneuverArrow(step.maneuver),
                        color = Color.Cyan,
                        fontSize = 30.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = 14.dp)
                    )
                    Text(
                        text = step.instruction,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 20.sp,
                        maxLines = 2
                    )
                }

                // Distance + ETA + mode badge
                Row(
                    modifier = Modifier.padding(top = 8.dp, start = 44.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "in ${formatDist(step.distanceMeters)}",
                        color = Color.Cyan,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "  ·  ETA ${formatDuration(state.remainingDurationSeconds)}",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    val modeLabel = if (state.mode == NavigationEngine.TravelMode.WALKING)
                        " · 🚶" else " · 🚗"
                    Text(
                        text = modeLabel,
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

    // ── Direction dots ────────────────────────────────────────────────────────

    /**
     * Draws a perspective trail of blue dots from the bottom-centre of the screen
     * (conceptually your feet) outward in the direction of [relativeBearingDeg].
     *
     * 0° = straight ahead (top of screen), 90° = right, 270° = left.
     * Dots closer to you are larger and more opaque; dots further away are smaller
     * and dimmer — giving a ground-plane perspective illusion.
     */
    @Composable
    private fun DirectionDots(relativeBearingDeg: Float) {
        val density   = LocalDensity.current
        val dotColor  = Color(0xFF00AAFF)   // sky blue — distinct from the cyan HUD text
        val dotCount  = 8

        Canvas(modifier = Modifier.fillMaxSize()) {
            val rad = Math.toRadians(relativeBearingDeg.toDouble())
            val dx  = sin(rad).toFloat()
            val dy  = -cos(rad).toFloat()   // negative: y increases downward in screen space

            // Origin at bottom-centre of the composable (the Captain's feet)
            val originX = size.width / 2f
            val originY = size.height

            for (i in 0 until dotCount) {
                val t = i / (dotCount - 1).toFloat()   // 0 = closest, 1 = furthest

                // Dots start close and spread out with increasing gap (perspective spacing)
                val distPx   = with(density) { (50 + i * i * 14).dp.toPx() }
                val radiusPx = with(density) { lerp(24f, 5f, t).dp.toPx() }
                val alpha    = lerp(0.92f, 0.18f, t)

                drawCircle(
                    color  = dotColor.copy(alpha = alpha),
                    radius = radiusPx,
                    center = Offset(originX + dx * distPx, originY + dy * distPx)
                )
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Bearing from [loc] to ([endLat], [endLng]) relative to the Captain's [headingDeg].
     * Returns a value in [0, 360): 0° = ahead, 90° = right, 270° = left.
     */
    private fun relativeBearing(
        loc: Location,
        endLat: Double,
        endLng: Double,
        headingDeg: Double
    ): Float {
        val target = Location("").apply { latitude = endLat; longitude = endLng }
        val absolute = loc.bearingTo(target)    // -180..180
        return ((absolute - headingDeg + 360) % 360).toFloat()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun maneuverArrow(maneuver: String): String = when (maneuver) {
        "turn-left"          -> "↰"
        "turn-right"         -> "↱"
        "turn-sharp-left"    -> "↩"
        "turn-sharp-right"   -> "↪"
        "turn-slight-left"   -> "↖"
        "turn-slight-right"  -> "↗"
        "straight"           -> "⬆"
        "merge"              -> "⬆"
        "ramp-left"          -> "↖"
        "ramp-right"         -> "↗"
        "fork-left"          -> "↙"
        "fork-right"         -> "↘"
        "roundabout-left"    -> "↺"
        "roundabout-right"   -> "↻"
        "u-turn-left"        -> "↩"
        "u-turn-right"       -> "↪"
        "ferry"              -> "⛴"
        else                 -> "⬆"
    }

    private fun formatDist(meters: Int): String =
        if (meters < 1000) "$meters m"
        else "${"%.1f".format(meters / 1000.0)} km"

    private fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m}min" else "${m}min"
    }
}
