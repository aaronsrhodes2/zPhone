package com.skippy.droid.features.clock

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skippy.droid.layers.FeatureModule
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClockModule : FeatureModule {
    override val id = "clock"
    override var enabled by mutableStateOf(true)
    override val zOrder = 10

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
        Text(
            text = timeString,
            color = Color.Cyan,
            fontSize = 18.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp, end = 12.dp)
        )
    }
}
