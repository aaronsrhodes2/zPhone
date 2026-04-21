package com.skippy.droid

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.skippy.droid.compositor.Compositor
import com.skippy.droid.features.battery.BatteryModule
import com.skippy.droid.features.clock.ClockModule
import com.skippy.droid.features.compass.CompassModule
import com.skippy.droid.layers.DeviceLayer
import com.skippy.droid.layers.TransportLayer

class MainActivity : ComponentActivity() {

    // Tailscale address of the Skippy Tel Network PC.
    // Replace with actual hostname once Tailscale is configured.
    private val pcUrl = "http://skippy-pc:5001"

    private lateinit var device: DeviceLayer
    private lateinit var transport: TransportLayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on; fullscreen immersive
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        device = DeviceLayer(this)
        transport = TransportLayer(pcUrl)

        setContent {
            val modules = remember {
                listOf(
                    ClockModule(),
                    CompassModule(device),
                    BatteryModule(this, transport)
                )
            }

            // Layer 2: passthrough — black background stands in for camera feed until
            // CameraPassthroughView is wired up.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.TopEnd
            ) {
                // Layer 6: compositor draws all enabled HUD overlays
                Compositor(modules)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        device.start()
        transport.start()
    }

    override fun onStop() {
        super.onStop()
        device.stop()
        transport.stop()
    }
}
