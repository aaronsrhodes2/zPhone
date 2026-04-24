package local.skippy.droid.features.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import local.skippy.droid.compositor.HudPalette
import local.skippy.droid.compositor.HudZone
import local.skippy.droid.compositor.hudFont
import local.skippy.droid.compositor.hudSp
import local.skippy.droid.layers.FeatureModule
import local.skippy.droid.layers.VoiceEngine

/**
 * VoicePillModule — the Captain's-own-words bottom-center chip that used to
 * live as a hardcoded Box in MainActivity.onCreate. Session 9 cleanup pulls
 * it out into a FeatureModule so the Compositor owns it and so the HUD can
 * toggle it off in future "serious driving" or "immersive passthrough" modes.
 *
 * ── Four visual states (reading [VoiceEngine]) ───────────────────────────
 *   mic off    → dim "› chat with Skippy…"  (RECORD_AUDIO not yet granted)
 *   idle       → cyan "◉ listening…"        (armed, no speech yet)
 *   speaking   → bright white live partial  (captured while Captain talks)
 *   settled    → "heard: <last>"            (last utterance, awaiting next)
 *
 * Palette only — no hex, no Color.Green. Uses [hudSp] so the chip scales
 * with viewport (~13–14 sp on phone, ~18 sp on 1200p glasses).
 *
 * ── Non-goals ─────────────────────────────────────────────────────────────
 * Not a typed-input pill. The future typed-chat input lives in a separate
 * module (not built here). This module only displays voice state; the
 * dispatcher handles classification.
 */
class VoicePillModule(
    private val voice: VoiceEngine,
) : FeatureModule {
    override val id = "local.skippy.voice"
    override var enabled by mutableStateOf(true)
    override val zone = HudZone.BottomCenter
    override val zOrder = 0

    @Composable
    override fun Overlay() {
        val live     = voice.partialTranscript
        val last     = voice.lastHeard
        val hasLive  = live.isNotEmpty()
        val pillText = when {
            !voice.isListening -> "› chat with Skippy…"
            hasLive            -> live
            last.isNotEmpty()  -> "heard: $last"
            else               -> "◉ listening…"
        }
        val pillColor = when {
            !voice.isListening -> HudPalette.White.copy(alpha = 0.30f)
            hasLive            -> HudPalette.White
            else               -> HudPalette.Cyan.copy(alpha = 0.75f)
        }
        Text(
            text = pillText,
            color = pillColor,
            fontSize = hudSp(1.0f),
            fontFamily = hudFont,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(HudPalette.White.copy(alpha = 0.06f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}
