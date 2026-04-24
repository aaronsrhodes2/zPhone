package com.skippy.droid.features.teleprompter

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skippy.droid.compositor.HudPalette
import com.skippy.droid.compositor.HudZone
import com.skippy.droid.compositor.SizeJustifiedText
import com.skippy.droid.layers.FeatureModule

/**
 * TeleprompterModule — BottomCenter scrolling-text window driven by
 * [TeleprompterEngine].
 *
 * ── Windowing strategy ────────────────────────────────────────────────────
 * We keep a fixed-word *window* of text centered on the current progress
 * word, so the Captain reads a continuous ribbon that advances through the
 * script. [SizeJustifiedText] fits the window's text into the bottom 160 dp
 * of the viewport; short scripts render big, long windows shrink.
 *
 * Phase 1 is content-only; the highlight-current-word layer and the
 * auto-wrap-on-punctuation polish are future work.
 *
 * ── Zone collision with VoicePillModule ───────────────────────────────────
 * Both live in [HudZone.BottomCenter]. Compositor stacks by zOrder; we
 * give the teleprompter a lower zOrder (nearer the anchor = on top of the
 * pill visually via the zone Column). When the teleprompter is hidden
 * ([TeleprompterEngine.visible] == false) we emit no content, so the pill
 * sits in its natural position.
 *
 * ── Palette ───────────────────────────────────────────────────────────────
 * Running → [HudPalette.White] (Captain's words — this is what *you* say).
 * Paused  → [HudPalette.White.copy(alpha = 0.6f)] (dimmed while paused).
 */
class TeleprompterModule(
    val engine: TeleprompterEngine,
) : FeatureModule {
    override val id = "teleprompter"
    override var enabled by mutableStateOf(true)
    override val zone = HudZone.BottomCenter
    override val zOrder = -5   // above VoicePill when both share BottomCenter

    companion object {
        /** How many words of the script are visible in the window. */
        private const val WINDOW_WORDS = 18
    }

    @Composable
    override fun Overlay() {
        if (!engine.visible) return

        val text = engine.text
        val words = remember(text) { text.trim().split(Regex("\\s+")) }
        val n = words.size
        if (n == 0) return

        // Center the window on progressWords, clamped to the script bounds.
        val center = engine.progressWords.toInt().coerceIn(0, n - 1)
        val half = WINDOW_WORDS / 2
        val start = (center - half).coerceAtLeast(0)
        val end = (start + WINDOW_WORDS).coerceAtMost(n)
        val window = words.subList(start, end).joinToString(" ")

        val color = if (engine.running) HudPalette.White
                    else                HudPalette.White.copy(alpha = 0.6f)

        SizeJustifiedText(
            text      = window,
            color     = color,
            textAlign = TextAlign.Center,
            modifier  = Modifier
                .fillMaxWidth()
                .height(160.dp),
            maxSp     = 48f,
        )
    }
}
