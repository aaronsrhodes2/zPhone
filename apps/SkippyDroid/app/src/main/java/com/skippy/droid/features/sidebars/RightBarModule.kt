package com.skippy.droid.features.sidebars

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.skippy.droid.compositor.HudPalette
import com.skippy.droid.compositor.HudZone
import com.skippy.droid.compositor.SignalStack
import com.skippy.droid.compositor.VerticalFillBar
import com.skippy.droid.layers.FeatureModule
import com.skippy.droid.layers.TransportLayer
import kotlinx.coroutines.delay

/**
 * RightBar — Session 7 Passthrough HUD Standard.
 *
 * Symbology-only sidebar. Top half paints [VerticalFillBar] whose fill
 * height = phone self battery %. Bottom half paints [SignalStack] bound to
 * [TransportLayer.signalBars] (Tailscale reachability tier).
 *
 * ── Hard rules (doctrine) ─────────────────────────────────────────────────
 *   - No `Text()`. No `Image()`. No glyph characters.
 *   - All colors from [HudPalette].
 *   - Pure content — Compositor wraps this in the RightBar positioning Box.
 *
 * ── Coexistence note ──────────────────────────────────────────────────────
 * The existing text-based [com.skippy.droid.features.battery.BatteryModule]
 * in `HudZone.TopEnd` stays live — it enumerates EVERY device (phone +
 * glasses + future nodes). The RightBar sidebar shows *phone self only*
 * as a glanceable glyph. Redundant by design: the glyph is the "am I
 * about to die" channel, the text is the "all-devices status board."
 */
class RightBarModule(
    private val context: Context,
    private val transport: TransportLayer,
) : FeatureModule {
    override val id = "rightbar"
    override var enabled by mutableStateOf(true)
    override val zone = HudZone.RightBar
    override val zOrder = 0   // only module in RightBar today

    /** 0..100 or null when unavailable. Polled once per minute. */
    private var batteryPct: Int? by mutableStateOf(null)

    @Composable
    override fun Overlay() {
        LaunchedEffect(Unit) {
            while (true) {
                batteryPct = readPhoneBatteryPct(context)
                delay(60_000)
            }
        }

        Column(
            modifier = Modifier
                .width(200.dp)
                .fillMaxHeight(),
        ) {
            // Top half — phone battery as a vertical fill meter.
            VerticalFillBar(
                fraction = (batteryPct ?: 0) / 100f,
                color = batteryColor(batteryPct),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            // Bottom half — Tailscale signal stack.
            SignalStack(
                bars = transport.signalBars(),
                maxBars = 4,
                color = HudPalette.Cyan,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }

    /** Phone battery level via [BatteryManager.EXTRA_LEVEL] / EXTRA_SCALE. */
    private fun readPhoneBatteryPct(ctx: Context): Int? {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else null
    }

    /**
     * Map battery percent to palette color per Session 7 doctrine:
     *   `> 50%`   → [HudPalette.Green]
     *   `20–50%`  → [HudPalette.Amber]
     *   `< 20%`   → [HudPalette.Red]
     *   null      → [HudPalette.DimGreen] (unknown / placeholder)
     */
    private fun batteryColor(pct: Int?): Color = when {
        pct == null -> HudPalette.DimGreen
        pct >= 50   -> HudPalette.Green
        pct >= 20   -> HudPalette.Amber
        else        -> HudPalette.Red
    }
}
