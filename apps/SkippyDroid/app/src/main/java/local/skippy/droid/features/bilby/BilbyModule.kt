package local.skippy.droid.features.bilby

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import local.skippy.droid.compositor.HudPalette
import local.skippy.droid.compositor.HudZone
import local.skippy.droid.compositor.hudFont
import local.skippy.droid.compositor.hudSp
import local.skippy.droid.layers.FeatureModule
import local.skippy.droid.layers.TransportLayer
import org.json.JSONObject

/**
 * Bilby music module for SkippyDroid.
 *
 * Renders a compact now-playing card in the HUD Viewport zone while music is
 * active; self-suppresses (renders nothing) when the session is stopped.
 *
 * Two poll loops (both run inside [Overlay]'s [LaunchedEffect]):
 *   - Session loop   : 2 s → GET /music/session
 *   - Karaoke loop   : 500 ms → GET /bilby/karaoke (degrades to null offline)
 *
 * Card layout (Viewport 1540 × 960):
 * ┌──────────────────────────────────────────────────────┐
 * │ ARTIST NAME                             128 BPM  Am  │
 * │ Song Title                                           │
 * │ ♪ Lyric line here…                                   │
 * │ ████████░░░░░░░░   (karaoke progress bar)            │
 * └──────────────────────────────────────────────────────┘
 *
 * Colors follow HudPalette doctrine:
 *   White      — artist / title (primary content text)
 *   Amber      — BPM / key (numeric metrics)
 *   Green      — karaoke line (primary accent)
 *   DimGreen   — card border / progress track
 *   DimGreenHi — filled progress bar
 */
class BilbyModule(
    private val transport: TransportLayer,
) : FeatureModule {

    override val id       = "local.skippy.bilby"
    override var enabled  by mutableStateOf(true)
    override val zone     = HudZone.Viewport
    override val zOrder   = 5   // below PassthroughHost (zOrder 10)

    // ── Internal state (Compose-observable) ──────────────────────────────────
    private var nowPlaying  by mutableStateOf<NowPlaying?>(null)

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseSession(raw: String): NowPlaying? {
        return try {
            val obj       = JSONObject(raw)
            val statusStr = obj.optString("status", "stopped")
            val status    = when (statusStr) {
                "playing" -> PlayStatus.PLAYING
                "paused"  -> PlayStatus.PAUSED
                else      -> return null        // stopped → nothing to render
            }
            val np = obj.optJSONObject("now_playing") ?: return null
            NowPlaying(
                title          = np.optString("title",  "Unknown"),
                artist         = np.optString("artist", "Unknown"),
                bpmInt         = np.optInt("bpm").takeIf { np.has("bpm") && it > 0 },
                key            = np.optString("key").takeIf { it.isNotEmpty() },
                status         = status,
                queueRemaining = obj.optInt("queue_remaining", 0),
            )
        } catch (_: Exception) { null }
    }

    private fun parseKaraoke(raw: String): KaraokeState? {
        return try {
            val obj  = JSONObject(raw)
            val line = obj.optString("line").takeIf { it.isNotEmpty() } ?: return null
            KaraokeState(
                line        = line,
                nextLine    = obj.optString("next_line").takeIf { it.isNotEmpty() },
                elapsedPct  = obj.optDouble("elapsed_pct", 0.0)
                                  .toFloat().coerceIn(0f, 1f),
            )
        } catch (_: Exception) { null }
    }

    // ── Composable ─────────────────────────────────────────────────────────────

    @Composable
    override fun Overlay() {

        // ── Poll session (2 s) ────────────────────────────────────────────────
        LaunchedEffect(Unit) {
            while (true) {
                val raw = withContext(Dispatchers.IO) { transport.get("/music/session") }
                val parsed = raw?.let { parseSession(it) }
                nowPlaying = parsed?.copy(karaoke = nowPlaying?.karaoke)  // preserve karaoke
                delay(2_000L)
            }
        }

        // ── Poll karaoke (500 ms) ─────────────────────────────────────────────
        LaunchedEffect(Unit) {
            while (true) {
                val raw = withContext(Dispatchers.IO) { transport.get("/bilby/karaoke") }
                val kara = raw?.let { parseKaraoke(it) }
                // Merge karaoke into current nowPlaying without wiping the rest.
                nowPlaying = nowPlaying?.copy(karaoke = kara)
                delay(500L)
            }
        }

        // ── Render ─────────────────────────────────────────────────────────────
        val np = nowPlaying ?: return   // nothing playing → render nothing
        if (np.status == PlayStatus.NONE) return

        BilbyCard(np)
    }
}

// ── Card composable (extracted for readability) ──────────────────────────────

@Composable
private fun BilbyCard(np: NowPlaying) {
    val kara          = np.karaoke
    val progressTarget = kara?.elapsedPct ?: 0f
    val progress by animateFloatAsState(
        targetValue   = progressTarget,
        animationSpec = tween(durationMillis = 450),
        label         = "karaoke_progress",
    )

    Box(
        modifier = Modifier
            .padding(16.dp)
            .border(1.dp, HudPalette.DimGreen, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column {
            // ── Row 1: artist  ·  BPM / key ───────────────────────────────
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text       = np.artist.uppercase(),
                    color      = HudPalette.White,
                    fontSize   = hudSp(0.95f),
                    fontFamily = hudFont,
                    modifier   = Modifier.weight(1f),
                )
                if (np.bpmInt != null || np.key != null) {
                    if (np.bpmInt != null) {
                        Text(
                            text       = "${np.bpmInt} BPM",
                            color      = HudPalette.Amber,
                            fontSize   = hudSp(0.75f),
                            fontFamily = hudFont,
                        )
                    }
                    if (np.key != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text       = np.key,
                            color      = HudPalette.Amber,
                            fontSize   = hudSp(0.75f),
                            fontFamily = hudFont,
                        )
                    }
                }
            }

            Spacer(Modifier.height(2.dp))

            // ── Row 2: title ─────────────────────────────────────────────
            Text(
                text       = np.title,
                color      = HudPalette.White,
                fontSize   = hudSp(1.1f),
                fontFamily = hudFont,
            )

            // ── Row 3: karaoke line + progress bar ────────────────────────
            if (kara != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text       = "♪ ${kara.line}",
                    color      = HudPalette.Green,
                    fontSize   = hudSp(0.85f),
                    fontFamily = hudFont,
                )
                Spacer(Modifier.height(4.dp))
                // Progress bar: DimGreen track, DimGreenHi fill
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(HudPalette.DimGreen, RoundedCornerShape(1.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(HudPalette.DimGreenHi),
                    )
                }
            }

            // ── Queue count (only when >= 2 remaining) ────────────────────
            if (np.queueRemaining >= 2) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text       = "+${np.queueRemaining} in queue",
                    color      = HudPalette.DimGreenHi,
                    fontSize   = hudSp(0.6f),
                    fontFamily = hudFont,
                )
            }
        }
    }
}
