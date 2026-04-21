package com.skippy.droid.features.compass

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skippy.droid.layers.DeviceLayer
import com.skippy.droid.layers.FeatureModule

class CompassModule(private val device: DeviceLayer) : FeatureModule {
    override val id = "compass"
    override var enabled by mutableStateOf(true)
    override val zOrder = 10

    @Composable
    override fun Overlay() {
        val heading by mutableStateOf(device.headingDegrees)
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(top = 30.dp, end = 12.dp)
        ) {
            Text(
                text = "${heading.toInt()}°",
                color = Color.Cyan,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = cardinal(heading),
                color = Color.Cyan,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    private fun cardinal(deg: Double): String {
        val dirs = listOf("N","NNE","NE","ENE","E","ESE","SE","SSE",
                          "S","SSW","SW","WSW","W","WNW","NW","NNW")
        return dirs[((deg + 11.25) / 22.5).toInt() % 16]
    }
}
