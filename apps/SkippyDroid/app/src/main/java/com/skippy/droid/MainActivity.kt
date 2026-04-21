package com.skippy.droid

import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skippy.droid.BuildConfig
import com.skippy.droid.compositor.Compositor
import com.skippy.droid.compositor.GlassesPresentation
import com.skippy.droid.features.battery.BatteryModule
import com.skippy.droid.features.clock.ClockModule
import com.skippy.droid.features.compass.CompassModule
import com.skippy.droid.features.coordinates.CoordinatesModule
import com.skippy.droid.features.navigation.NavigationEngine
import com.skippy.droid.features.navigation.NavigationModule
import com.skippy.droid.features.speed.SpeedModule
import com.skippy.droid.layers.CameraPassthrough
import com.skippy.droid.layers.ContextEngine
import com.skippy.droid.layers.DeviceLayer
import com.skippy.droid.layers.FeatureModule
import com.skippy.droid.layers.GlassesLayer
import com.skippy.droid.layers.PassthroughCamera
import com.skippy.droid.layers.TransportLayer
import kotlinx.coroutines.launch

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

    private lateinit var contextEngine: ContextEngine
    private lateinit var glassesLayer: GlassesLayer
    lateinit var navEngine: NavigationEngine   // exposed for voice module (future)

    private lateinit var displayManager: DisplayManager
    private var glassesPresentation: GlassesPresentation? = null

    // Modern permission API — callbacks fire after user responds to the system dialog.
    // Both passthrough and device are guaranteed initialized before any callback fires.

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) passthrough.onPermissionGranted()
        }

    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) device.onLocationPermissionGranted()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        device        = DeviceLayer(this)
        glassesLayer  = GlassesLayer().also { device.glasses = it }   // preferred heading source
        transport     = TransportLayer(pcUrl)
        passthrough   = PassthroughCamera(this)
        contextEngine = ContextEngine(device)
        navEngine     = NavigationEngine(device)

        modules = listOf(
            ClockModule(),
            CompassModule(device),
            BatteryModule(this, transport),
            CoordinatesModule(device),
            SpeedModule(device),
            NavigationModule(navEngine, device)
        )

        // Ask for CAMERA now. If already granted, PassthroughCamera opens as soon as
        // the TextureView's onSurfaceTextureAvailable fires. If not yet granted, the
        // result comes back via requestCameraPermission callback above.
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermission.launch(android.Manifest.permission.CAMERA)
        }

        // Ask for LOCATION. DeviceLayer.start() catches SecurityException silently; if permission
        // is granted after the fact the callback re-registers location updates immediately.
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermission.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Watch for VITURE glasses connecting / disconnecting via USB-C DP Alt Mode.
        // Initial scan is deferred to onStart() — Presentation.show() requires
        // the activity window to be attached, which isn't guaranteed in onCreate().
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, null)

        setContent {
            // Phone screen: camera passthrough background + HUD overlays.
            //
            // When glasses are plugged in, PassthroughCamera.attachGlasses() steals the
            // camera session for the glasses display and the phone's TextureView goes dark.
            // When glasses are removed, PassthroughCamera.detachGlasses() hands the camera
            // back to the phone's surface automatically.
            val scope = rememberCoroutineScope()

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
                    Compositor(modules, contextEngine)
                }

                // ── Debug NAV button (DEBUG builds only) ─────────────────────────────
                // Tap to start a test walking route; tap again to cancel.
                // Remove or gate behind a feature flag before any public release.
                if (BuildConfig.DEBUG) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 10.dp, bottom = 10.dp),
                        contentAlignment = Alignment.BottomStart
                    ) {
                        val isNav = navEngine.isNavigating
                        Text(
                            text = if (isNav) "■ STOP NAV" else "▶ TEST NAV",
                            color = Color.Yellow,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .background(
                                    Color.Black.copy(alpha = 0.60f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                .clickable {
                                    if (isNav) {
                                        navEngine.cancel()
                                    } else {
                                        scope.launch {
                                            navEngine.navigateTo(
                                                destination = "Johnny's Market Boulder Creek CA",
                                                mode = NavigationEngine.TravelMode.WALKING
                                            )
                                        }
                                    }
                                }
                        )
                    }
                }
            }
        }
    }

    // ── Display listener ──────────────────────────────────────────────────────

    private val displayListener = object : DisplayManager.DisplayListener {

        override fun onDisplayAdded(displayId: Int) {
            // DisplayManager may not have finished categorizing the new display yet,
            // so we defer the PRESENTATION check by 300 ms to avoid a race.
            Handler(Looper.getMainLooper()).postDelayed({
                val display = displayManager.getDisplay(displayId) ?: return@postDelayed
                val presDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                Log.d("Skippy", "onDisplayAdded: id=$displayId presCount=${presDisplays.size}")
                if (presDisplays.any { it.displayId == displayId }) {
                    showGlasses(display)
                }
            }, 300)
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
        Log.d("Skippy", "showGlasses: displayId=${display.displayId} name=${display.name}")
        glassesPresentation?.dismiss()
        glassesPresentation = GlassesPresentation(this, display, modules, passthrough, contextEngine)
            .also { it.show() }
        Log.d("Skippy", "showGlasses: presentation shown")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        device.start()
        glassesLayer.start()
        transport.start()
        contextEngine.start()

        // Scan for presentation displays here (not onCreate) so Presentation.show()
        // has a live window to render into.
        val presDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        Log.d("Skippy", "onStart: found ${presDisplays.size} presentation display(s)")
        presDisplays.firstOrNull()?.let { showGlasses(it) }
    }

    override fun onStop() {
        super.onStop()
        device.stop()
        glassesLayer.stop()
        transport.stop()
        contextEngine.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        displayManager.unregisterDisplayListener(displayListener)
        glassesPresentation?.dismiss()
        glassesPresentation = null
        passthrough.detachPhone()
    }
}
