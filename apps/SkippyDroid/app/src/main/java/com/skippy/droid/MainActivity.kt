package com.skippy.droid

import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.skippy.droid.compositor.Compositor
import com.skippy.droid.compositor.GlassesPresentation
import com.skippy.droid.features.battery.BatteryModule
import com.skippy.droid.features.clock.ClockModule
import com.skippy.droid.features.compass.CompassModule
import com.skippy.droid.layers.CameraPassthrough
import com.skippy.droid.layers.DeviceLayer
import com.skippy.droid.layers.FeatureModule
import com.skippy.droid.layers.PassthroughCamera
import com.skippy.droid.layers.TransportLayer

class MainActivity : ComponentActivity() {

    // Tailscale address of the Skippy Tel Network PC.
    // Replace with actual hostname once Tailscale is configured.
    private val pcUrl = "http://skippy-pc:5001"

    private lateinit var device: DeviceLayer
    private lateinit var transport: TransportLayer
    private lateinit var passthrough: PassthroughCamera

    // Modules live outside Compose so both the phone screen and GlassesPresentation
    // share the same state objects (all backed by mutableStateOf / mutableDoubleStateOf).
    private lateinit var modules: List<FeatureModule>

    private lateinit var displayManager: DisplayManager
    private var glassesPresentation: GlassesPresentation? = null

    // Modern permission API — callback fires after user responds to the system dialog.
    // passthrough is guaranteed to be initialized before the callback can fire (it's
    // set in onCreate, which must complete before the user can respond to a dialog).
    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) passthrough.onPermissionGranted()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        device    = DeviceLayer(this)
        transport = TransportLayer(pcUrl)
        passthrough = PassthroughCamera(this)

        modules = listOf(
            ClockModule(),
            CompassModule(device),
            BatteryModule(this, transport)
        )

        // Ask for CAMERA now. If already granted, PassthroughCamera opens as soon as
        // the TextureView's onSurfaceTextureAvailable fires. If not yet granted, the
        // result comes back via requestCameraPermission callback above.
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermission.launch(android.Manifest.permission.CAMERA)
        }

        // Watch for VITURE glasses connecting / disconnecting via USB-C DP Alt Mode.
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, null)
        displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull()?.let { showGlasses(it) }

        setContent {
            // Phone screen: camera passthrough background + HUD overlays.
            //
            // When glasses are plugged in, PassthroughCamera.attachGlasses() steals the
            // camera session for the glasses display and the phone's TextureView goes dark.
            // When glasses are removed, PassthroughCamera.detachGlasses() hands the camera
            // back to the phone's surface automatically.
            Box(modifier = Modifier.fillMaxSize()) {

                // Layer 2: camera fills the frame
                CameraPassthrough(
                    onSurfaceAvailable = { surface -> passthrough.attachPhone(surface) },
                    onSurfaceDestroyed  = { passthrough.detachPhone() },
                    modifier = Modifier.fillMaxSize()
                )

                // Layer 6: HUD overlays — TopEnd corner, same as glasses display
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopEnd
                ) {
                    Compositor(modules)
                }
            }
        }
    }

    // ── Display listener ──────────────────────────────────────────────────────

    private val displayListener = object : DisplayManager.DisplayListener {

        override fun onDisplayAdded(displayId: Int) {
            val display = displayManager.getDisplay(displayId) ?: return
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
        glassesPresentation = GlassesPresentation(this, display, modules, passthrough)
            .also { it.show() }
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
        passthrough.detachPhone()
    }
}
