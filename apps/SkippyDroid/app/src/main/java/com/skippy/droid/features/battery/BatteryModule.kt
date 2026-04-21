package com.skippy.droid.features.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skippy.droid.layers.FeatureModule
import com.skippy.droid.layers.TransportLayer
import kotlinx.coroutines.delay
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Battery Panel — thin strip showing charge level for every Skippy device.
 * Phone: BatteryManager (self). Glasses + remote nodes: polled from TransportLayer.
 */
class BatteryModule(
    private val context: Context,
    private val transport: TransportLayer
) : FeatureModule {
    override val id = "battery"
    override var enabled by mutableStateOf(true)
    override val requiresNetwork = false  // degrades gracefully when offline
    override val zOrder = 5

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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            batteries.forEach { device ->
                Text(
                    text = "${device.label}:${formatPct(device.percent)}  ",
                    color = batteryColor(device.percent),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
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
        // Returns JSON: {"label":"...", "percent":85} or null when offline
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

    private fun batteryColor(pct: Int?) = when {
        pct == null -> Color.Gray
        pct >= 50 -> Color.Green
        pct >= 20 -> Color.Yellow
        else -> Color.Red
    }
}
