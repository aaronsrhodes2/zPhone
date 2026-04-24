package local.skippy.droid.features.sidebars

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import local.skippy.droid.compositor.HudPalette
import local.skippy.droid.compositor.HudZone
import local.skippy.droid.compositor.ModeSegments
import local.skippy.droid.compositor.RmsGlyph
import local.skippy.droid.layers.ContextEngine
import local.skippy.droid.layers.FeatureModule
import local.skippy.droid.layers.VoiceEngine

/**
 * LeftBar — Session 7 Passthrough HUD Standard.
 *
 * Symbology-only sidebar. Top half paints [ModeSegments] driven by
 * [ContextEngine.currentMode] (stationary → walking → driving, ordered by
 * speed top-to-bottom). Bottom half paints [RmsGlyph] driven by
 * [VoiceEngine.rmsLevel].
 *
 * ── Hard rules (enforced by doctrine, not code) ───────────────────────────
 *   - No `Text()`. No `Image()`. No glyph characters.
 *   - All colors from [HudPalette].
 *   - Pure content — Compositor wraps this in the LeftBar positioning Box.
 *
 * ── Why these two ─────────────────────────────────────────────────────────
 * The left sidebar is the Captain's "self-awareness" channel — it tells the
 * Captain about his own body + voice, not the outside world. Mode = how
 * fast am I moving; RMS = what is my voice doing right now. Both answer
 * "what am I, Skippy's operator, currently doing?" without any words.
 */
class LeftBarModule(
    private val context: ContextEngine,
    private val voice: VoiceEngine,
) : FeatureModule {
    override val id = "local.skippy.leftbar"
    override var enabled by mutableStateOf(true)
    override val zone = HudZone.LeftBar
    override val zOrder = 0   // only module in LeftBar today; value doesn't matter yet

    @Composable
    override fun Overlay() {
        // Sidebar is 180 dp wide (per HudZone.LeftBar budget). Split vertically:
        // top half = mode segments, bottom half = RMS glyph.
        Column(
            modifier = Modifier
                .width(180.dp)
                .fillMaxHeight(),
        ) {
            // Top half — context mode
            ModeSegments(
                activeIndex = context.currentMode.indexOrdered(),
                total = ContextEngine.Mode.entries.size,
                color = HudPalette.Green,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            // Bottom half — mic RMS
            RmsGlyph(
                rms = voice.rmsLevel,
                color = HudPalette.Red,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

/**
 * Map [ContextEngine.Mode] to a top-to-bottom segment index ordered by speed.
 * STATIONARY → 0 (top), WALKING → 1 (middle), DRIVING → 2 (bottom).
 *
 * Declaration order in the enum already matches this ordering, but making
 * the mapping explicit here insulates [ModeSegments] from an unrelated enum
 * reshuffle in [ContextEngine].
 */
private fun ContextEngine.Mode.indexOrdered(): Int = when (this) {
    ContextEngine.Mode.STATIONARY -> 0
    ContextEngine.Mode.WALKING    -> 1
    ContextEngine.Mode.DRIVING    -> 2
}
