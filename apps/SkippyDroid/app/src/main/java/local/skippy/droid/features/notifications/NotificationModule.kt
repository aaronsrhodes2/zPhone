package local.skippy.droid.features.notifications

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import local.skippy.droid.compositor.HudPalette
import local.skippy.droid.compositor.HudZone
import local.skippy.droid.compositor.SizeJustifiedText
import local.skippy.droid.layers.FeatureModule
import local.skippy.droid.layers.SoundLayer
import kotlinx.coroutines.delay

/**
 * NotificationModule — renders the most recent [NotificationBus.Arrival] as
 * a transient TopCenter toast. Fades after [NotificationBus.TOAST_DURATION_MS]
 * by clearing the bus.
 *
 * ── Palette ──────────────────────────────────────────────────────────────
 *   Amber — numeric/tag-ish (falls into "world readout" category).
 *   White — the actual message text (Captain-readable content).
 *
 * We use [SizeJustifiedText] so long notification bodies shrink to fit
 * the TopCenter budget (1200×180 dp) with no ellipsis — doctrine.
 *
 * ── SoundLayer integration ───────────────────────────────────────────────
 * When a new arrival appears, fire a right-ear chime (CHIME_NOTIFY).
 * "Right ear = world events" per SoundLayer doctrine.
 *
 * If [sound] is null the module is silent — tests construct it that way.
 */
class NotificationModule(
    private val sound: SoundLayer? = null,
) : FeatureModule {
    override val id = "local.skippy.notifications"
    override var enabled by mutableStateOf(true)
    override val zone = HudZone.TopCenter
    override val zOrder = 10   // below compass (compass is glance chrome, notifications are transient)

    /** Last arrival we chimed for — prevents re-chiming on recomposition. */
    private var lastChimedAtMs: Long by mutableStateOf(0L)

    @Composable
    override fun Overlay() {
        val arrival = NotificationBus.latest ?: return

        // Chime exactly once per arrival.
        LaunchedEffect(arrival.postedAtMs) {
            if (arrival.postedAtMs != lastChimedAtMs) {
                lastChimedAtMs = arrival.postedAtMs
                sound?.notify()
            }
            // Auto-clear after the toast duration.
            delay(NotificationBus.TOAST_DURATION_MS)
            // Guard: only clear if still showing this arrival (a newer one
            // may have replaced it mid-countdown).
            if (NotificationBus.latest?.postedAtMs == arrival.postedAtMs) {
                NotificationBus.clear()
            }
        }

        // TopCenter budget is 1200×180 dp; we give the text 120 dp to fill,
        // leaving 60 dp of breathing room for the compass above.
        SizeJustifiedText(
            text       = arrival.oneLine,
            color      = HudPalette.Amber,
            textAlign  = TextAlign.Center,
            modifier   = Modifier
                .fillMaxWidth()
                .height(120.dp),
            maxSp      = 36f,
        )
    }
}
