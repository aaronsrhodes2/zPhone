package com.skippy.droid.features.navigation

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skippy.droid.layers.FeatureModule

/**
 * Navigation overlay — bottom-center of the glasses display.
 *
 * Shows the current maneuver arrow, instruction, remaining distance, and ETA.
 * Self-hides when [NavigationEngine.isNavigating] is false — no activeIn
 * restriction because the module manages its own visibility.
 *
 * The mode (walking vs driving) is embedded in the active route's NavState,
 * so the Context Engine doesn't need to gate this module.
 *
 * Trigger navigation externally via [NavigationEngine.navigateTo] —
 * voice commands (future) or "Hey Skippy, navigate to X" will call it.
 */
class NavigationModule(private val nav: NavigationEngine) : FeatureModule {
    override val id = "navigation"
    override val requiresGps = true
    override val requiresNetwork = true
    override var enabled by mutableStateOf(true)
    override val zOrder = 20    // above all other modules while navigating

    @Composable
    override fun Overlay() {
        if (!nav.isNavigating) return
        val state = nav.state ?: return
        val step  = state.currentStep ?: return

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
                // ── Maneuver + instruction ──────────────────────────
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

                // ── Distance + ETA ──────────────────────────────────
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

                    // Walking / Driving badge
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

    // ── Helpers ───────────────────────────────────────────────────────────────

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
