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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skippy.droid.BuildConfig
import com.skippy.droid.compositor.Compositor
import com.skippy.droid.compositor.GlassesPresentation
import com.skippy.droid.compositor.HudPalette
import com.skippy.droid.features.battery.BatteryModule
import com.skippy.droid.features.clock.ClockModule
import com.skippy.droid.features.compass.CompassModule
import com.skippy.droid.features.coordinates.CoordinatesModule
import com.skippy.droid.features.navigation.NavigationEngine
import com.skippy.droid.features.navigation.NavigationModule
import com.skippy.droid.features.sidebars.LeftBarModule
import com.skippy.droid.features.sidebars.RightBarModule
import com.skippy.droid.features.speed.SpeedModule
import com.skippy.droid.layers.CommandDispatcher
import com.skippy.droid.layers.ContextEngine
import com.skippy.droid.layers.DeviceLayer
import com.skippy.droid.layers.FeatureModule
import com.skippy.droid.layers.GlassesLayer
import com.skippy.droid.layers.PassthroughCamera
import com.skippy.droid.layers.TransportLayer
import com.skippy.droid.layers.VoiceEngine
import androidx.lifecycle.lifecycleScope
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
    lateinit var navEngine: NavigationEngine   // exposed for voice module
    lateinit var voice: VoiceEngine

    /**
     * Session 7 one-input dispatcher — every input source (voice, future
     * keyboard, future POST /api/input-text, future PC MCP callbacks)
     * converges here. Modules register intents; the dispatcher classifies
     * raw text into a typed [CommandDispatcher.Intent] and invokes the
     * registered handler. See [registerIntents] below for the current set.
     */
    private lateinit var cmd: CommandDispatcher

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

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) voice.start()
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
        voice         = VoiceEngine(this)

        // One-input dispatcher — constructed AFTER engines exist so registered
        // intent handlers can capture them by reference.
        cmd = CommandDispatcher()
        registerIntents(cmd)
        voice.dispatcher = cmd

        modules = listOf(
            ClockModule(),
            CompassModule(device),
            BatteryModule(this, transport),
            CoordinatesModule(device),
            SpeedModule(device),
            NavigationModule(navEngine, device),
            // Session 7 sidebars — symbology-only, no Text/Image.
            LeftBarModule(contextEngine, voice),
            RightBarModule(this, transport),
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

        // Ask for MICROPHONE. If granted, the callback starts VoiceEngine. If already
        // granted, onStart() below starts it. Denied → "chat with Skippy…" pill stays dim.
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }

        // Watch for VITURE glasses connecting / disconnecting via USB-C DP Alt Mode.
        // Initial scan is deferred to onStart() — Presentation.show() requires
        // the activity window to be attached, which isn't guaranteed in onCreate().
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, null)

        setContent {
            // Phone screen: Skippy has replaced the Android OS experience entirely.
            // No camera preview, no phone-native overlays — the phone IS the glasses
            // mirror, always on. Future: a chat prompt at the bottom for voice
            // conversation. For now: glasses HUD on solid black + dev pills.
            //
            // The real camera passthrough is only rendered on the glasses display
            // (GlassesPresentation). Phone is Skippy-owned space.
            val scope = rememberCoroutineScope()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(HudPalette.Black)   // Skippy owns the phone screen
            ) {

                // Glasses HUD — always on, same compositor the VITURE display paints.
                // Take glasses off → phone shows exactly what they were showing.
                Compositor(modules, contextEngine, isGlasses = true)

                // ── Live voice pill (BottomCenter) ───────────────────────────────────
                // Reads VoiceEngine state directly. Three visual states:
                //   mic off  → dim "› chat with Skippy…" (perm not yet granted)
                //   idle     → "◉ listening…" (armed, waiting for speech)
                //   speaking → bright live transcript (partial results)
                //   settled  → "heard: <last utterance>" (after silence)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    val live     = voice.partialTranscript
                    val last     = voice.lastHeard
                    val hasLive  = live.isNotEmpty()
                    val pillText = when {
                        !voice.isListening -> "› chat with Skippy…"
                        hasLive            -> live
                        last.isNotEmpty()  -> "heard: $last"
                        else               -> "◉ listening…"
                    }
                    val pillColor = when {
                        !voice.isListening -> HudPalette.White.copy(alpha = 0.30f)
                        hasLive            -> HudPalette.White
                        else               -> HudPalette.Cyan.copy(alpha = 0.75f)
                    }
                    Text(
                        text = pillText,
                        color = pillColor,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(
                                HudPalette.White.copy(alpha = 0.06f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }

                // ── Debug NAV button (DEBUG builds only) ─────────────────────────────
                // Tap to start a test walking route; tap again to cancel.
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
                            color = HudPalette.Amber,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .background(
                                    HudPalette.Black.copy(alpha = 0.60f),
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

    // ── Command intents ───────────────────────────────────────────────────────

    /**
     * Register all intents the dispatcher should recognize. Called once
     * during [onCreate] after the engines exist.
     *
     * Doctrine: modules would ideally register their own intents
     * (FeatureModule.registerIntents(dispatcher)). We centralize here for
     * Phase 1 so the set is visible in one place; Phase 2 will push
     * registration into each module for auto-discovery.
     *
     * Reserved `help` / `inventory` verbs are owned by the dispatcher itself;
     * we only hook [CommandDispatcher.onInventoryRequest] to surface the
     * live verb list.
     */
    private fun registerIntents(d: CommandDispatcher) {

        // ── navigate ────────────────────────────────────────────────────────
        // "navigate to Starbucks downtown" / "hey skippy navigate to the park"
        // Args = everything after "navigate to " (e.g. "Starbucks downtown").
        d.register(
            id          = "navigate",
            phrases     = listOf("navigate to "),
            description = "Start walking navigation to a destination",
        ) { destination ->
            if (destination.isEmpty()) {
                Log.w("Skippy", "voice navigate with empty destination")
                return@register
            }
            val loc = device.location
            if (loc == null) {
                Log.w("Skippy", "voice navigate '$destination' but no GPS fix yet")
                return@register
            }
            Log.d("Skippy", "voice navigate: $destination")
            lifecycleScope.launch {
                navEngine.navigateTo(
                    destination = destination,
                    mode = NavigationEngine.TravelMode.WALKING,
                )
            }
        }

        // ── cancel ──────────────────────────────────────────────────────────
        // "cancel" / "stop navigation" / "never mind" — all drop the active route.
        d.register(
            id          = "cancel",
            phrases     = listOf("cancel", "stop navigation", "never mind"),
            description = "Cancel the active navigation route",
        ) {
            Log.d("Skippy", "voice cancel")
            navEngine.cancel()
        }

        // ── inventory / help ────────────────────────────────────────────────
        // The dispatcher owns the reserved verbs; we just observe the request.
        // Phase 1: log the live verb set so the Captain sees it in adb logcat.
        // Phase 2: TTS the list through an on-glasses speech channel.
        d.onInventoryRequest = { live ->
            val lines = live.joinToString("\n") { "  • ${it.id}: ${it.description}" }
            Log.d("Skippy.Inventory", "live intents (${live.size}):\n$lines")
        }

        // ── unmatched ───────────────────────────────────────────────────────
        // Logged at DEBUG so we can see what verbs the Captain is trying that
        // aren't wired yet. Phase 2 will escalate unmatched text to the PC MCP.
        d.onUnmatched = { text, source ->
            Log.d("Skippy.Dispatcher", "unmatched [$source]: '$text'")
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        device.start()
        glassesLayer.start()
        transport.start()
        contextEngine.start()
        // Voice only starts if RECORD_AUDIO is already granted; otherwise it starts
        // from requestMicPermission callback once the Captain grants it.
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            voice.start()
        }

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
        voice.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        displayManager.unregisterDisplayListener(displayListener)
        glassesPresentation?.dismiss()
        glassesPresentation = null
        passthrough.detachPhone()
    }
}
