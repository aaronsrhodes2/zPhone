package com.skippy.droid

import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.skippy.droid.compositor.Compositor
import com.skippy.droid.compositor.GlassesPresentation
import com.skippy.droid.features.battery.BatteryModule
import com.skippy.droid.features.clock.ClockModule
import com.skippy.droid.features.compass.CompassModule
import com.skippy.droid.layers.DeviceLayer
import com.skippy.droid.layers.FeatureModule
import com.skippy.droid.layers.TransportLayer

class MainActivity : ComponentActivity() {

    // Tailscale address of the Skippy Tel Network PC.
    // Replace with actual hostname once Tailscale is configured.
    private val pcUrl = "http://skippy-pc:5001"

    private lateinit var device: DeviceLayer
    private lateinit var transport: TransportLayer

    // Modules live outside Compose so both the phone screen and GlassesPresentation
    // share the same state objects (all backed by mutableStateOf / mutableDoubleStateOf).
    private lateinit var modules: List<FeatureModule>

    private lateinit var displayManager: DisplayManager
    private var glassesPresentation: GlassesPresentation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on; fullscreen immersive via theme
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        device = DeviceLayer(this)
        transport = TransportLayer(pcUrl)

        modules = listOf(
            ClockModule(),
            CompassModule(device),
            BatteryModule(this, transport)
        )

        // Watch for VITURE glasses connecting / disconnecting via USB-C DP Alt Mode.
        // Android exposes the glasses as a DISPLAY_CATEGORY_PRESENTATION secondary display.
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, null)

        // Glasses already connected when the app cold-starts?
        displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull()
            ?.let { showGlasses(it) }

        setContent {
            // Phone screen: dev preview — identical overlays on a black background.
            // No `remember` needed; modules is a stable lateinit class property.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.TopEnd
            ) {
                Compositor(modules)
            }
        }
    }

    // ── Display listener ──────────────────────────────────────────────────────

    private val displayListener = object : DisplayManager.DisplayListener {

        override fun onDisplayAdded(displayId: Int) {
            val display = displayManager.getDisplay(displayId) ?: return
            // Only act on presentation-capable displays (where VITURE appears)
            val isPresentation = displayManager
                .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .any { it.displayId == displayId }
            if (isPresentation) {
                runOnUiThread { showGlasses(display) }
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
            glassesPresentation?.let { p ->
                if (p.display.displayId == displayId) {
                    runOnUiThread {
                        p.dismiss()
                        glassesPresentation = null
                    }
                }
            }
        }

        override fun onDisplayChanged(displayId: Int) {}
    }

    private fun showGlasses(display: android.view.Display) {
        glassesPresentation?.dismiss()
        glassesPresentation = GlassesPresentation(this, display, modules).also { it.show() }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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

    override fun onDestroy() {
        super.onDestroy()
        displayManager.unregisterDisplayListener(displayListener)
        glassesPresentation?.dismiss()
        glassesPresentation = null
    }
}
