package com.skippy.droid.features.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import com.skippy.droid.compositor.HudPalette
import com.skippy.droid.compositor.HudZone
import com.skippy.droid.compositor.hudFont
import com.skippy.droid.compositor.hudSp
import com.skippy.droid.layers.FeatureModule
import com.skippy.droid.layers.TransportLayer
import kotlinx.coroutines.delay
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Battery Panel — top-right corner, stacked below [ClockModule]. Shows charge
 * level for every Skippy device, one line each. Phone self via BatteryManager;
 * glasses + remote nodes via TransportLayer.
 *
 * If the list ever grows beyond what fits the TopEnd slot, we paginate (cycle
 * through groups every 3s) rather than truncate. Two devices fit easily at
 * default [hudSp]; past that we'll wire the paginator.
 */
class BatteryModule(
    private val context: Context,
    private val transport: TransportLayer
) : FeatureModule {
    override val id = "battery"
    override var enabled by mutableStateOf(true)
    override val requiresNetwork = false  // degrades gracefully when offline
    // zOrder also controls vertical stacking within a shared zone: lowest → topmost
    // in the zone's Column. Battery sits below Clock in TopEnd, so it gets the higher value.
    override val zOrder = 10
    override val zone = HudZone.TopEnd

    data class DeviceBattery(val label: String, val percent: Int?)

    private var batteries by mutableStateOf(listOf<DeviceBattery>())

    @Composable
    override fun Overlay() {
        LaunchedEffect(Unit) {
            while (true) {
                batteries = buildBatteryList()
                delay(60_000)
            }
        }
        // Pure content — Compositor wraps this in the TopEnd positioning Box.
        Column(horizontalAlignment = Alignment.End) {
            batteries.forEach { device ->
                Text(
                    text = "${device.label} ${formatPct(device.percent)}",
                    color = batteryColor(device.percent),
                    fontSize = hudSp(0.85f),
                    fontFamily = hudFont
                )
            }
        }
    }

    private fun buildBatteryList(): List<DeviceBattery> {
        val list = mutableListOf<DeviceBattery>()

        // Self (S23)
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val phonePct = if (level >= 0 && scale > 0) (level * 100 / scale) else null
        list += DeviceBattery("S23", phonePct)

        // Remote nodes via TransportLayer — each exposes GET /battery
        try {
            val resp = transport.post("/battery/glasses", ByteArray(0).toRequestBody(null))
            if (resp != null) {
                val json = JSONObject(resp)
                list += DeviceBattery(
                    json.optString("label", "Glasses"),
                    json.optInt("percent", -1).takeIf { it >= 0 }
                )
            }
        } catch (_: Exception) {}

        return list
    }

    private fun formatPct(pct: Int?) = if (pct != null) "$pct%" else "??"

    private fun batteryColor(pct: Int?): Color = when {
        pct == null -> HudPalette.DimGreen
        pct >= 50   -> HudPalette.Green
        pct >= 20   -> HudPalette.Amber
        else        -> HudPalette.Red
    }
}
