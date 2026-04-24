package local.skippy.droid.features.context

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
import local.skippy.droid.layers.FeatureModule

/**
 * ContextModeModule — fills the [HudZone.BottomEnd] slot that Session 7
 * reserved for "context-mode readout".
 *
 * The LeftBar already paints [local.skippy.droid.compositor.ModeSegments] for
 * the same signal symbolically; this module is the *text* counterpart — a
 * tiny glance-and-dismiss tag that names the current mode in one word so
 * the Captain doesn't have to decode the segment stack.
 *
 * ── Palette rule ─────────────────────────────────────────────────────────
 *   STATIONARY → [HudPalette.DimGreenHi]  (idle tag, muted)
 *   WALKING    → [HudPalette.Green]       (active — "self in motion")
 *   DRIVING    → [HudPalette.Amber]       (active — numeric/metric territory)
 *
 * The text is a single short word; no sentence, no wrapping, no marquee.
 * Rendered via [hudSp] (fixed-glance chrome), not [local.skippy.droid.compositor.SizeJustifiedText].
 */
class ContextModeModule(
    private val context: ContextEngine,
) : FeatureModule {
    override val id = "local.skippy.context"
    override var enabled by mutableStateOf(true)
    override val zone = HudZone.BottomEnd
    override val zOrder = 0

    @Composable
    override fun Overlay() {
        val mode = context.currentMode
        val label = when (mode) {
            ContextEngine.Mode.STATIONARY -> "STATIONARY"
            ContextEngine.Mode.WALKING    -> "WALKING"
            ContextEngine.Mode.DRIVING    -> "DRIVING"
        }
        val color = when (mode) {
            ContextEngine.Mode.STATIONARY -> HudPalette.DimGreenHi
            ContextEngine.Mode.WALKING    -> HudPalette.Green
            ContextEngine.Mode.DRIVING    -> HudPalette.Amber
        }
        Text(
            text = label,
            color = color,
            fontSize = hudSp(0.9f),
            fontFamily = hudFont,
            fontWeight = FontWeight.Bold,
        )
    }
}
