package com.skippy.droid.features.clock

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import com.skippy.droid.compositor.HudPalette
import com.skippy.droid.compositor.HudZone
import com.skippy.droid.compositor.hudFont
import com.skippy.droid.compositor.hudSp
import com.skippy.droid.layers.FeatureModule
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clock — top-right corner (glance-and-dismiss). Stacks above [BatteryModule]
 * which also claims TopEnd.
 */
class ClockModule : FeatureModule {
    override val id = "clock"
    override var enabled by mutableStateOf(true)
    // zOrder also controls vertical stacking within a shared zone: lowest → topmost
    // in the zone's Column. Clock sits above Battery in TopEnd, so it gets the lower value.
    override val zOrder = 5
    override val zone = HudZone.TopEnd

    private var timeString by mutableStateOf("")
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Composable
    override fun Overlay() {
        LaunchedEffect(Unit) {
            while (true) {
                timeString = fmt.format(Date())
                delay(1000)
            }
        }
        // Emit pure content — Compositor wraps this in the TopEnd positioning Box.
        Text(
            text = timeString,
            color = HudPalette.Cyan,
            fontSize = hudSp(1.2f),
            fontFamily = hudFont,
            fontWeight = FontWeight.Bold
        )
    }
}
