package local.skippy.droid

import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import local.skippy.droid.BuildConfig
import local.skippy.droid.compositor.Compositor
import local.skippy.droid.compositor.GlassesDisplayEngine
import local.skippy.droid.compositor.GlassesPresentation
import local.skippy.droid.compositor.HudPalette
import local.skippy.droid.layers.CameraPassthrough
import local.skippy.droid.compositor.passthrough.MockPassthroughView
import local.skippy.droid.compositor.passthrough.PassthroughHost
import local.skippy.droid.features.battery.BatteryModule
import local.skippy.droid.features.clock.ClockModule
import local.skippy.droid.features.compass.CompassModule
import local.skippy.droid.features.context.ContextModeModule
import local.skippy.droid.features.coordinates.CoordinatesModule
import local.skippy.droid.features.navigation.NavigationEngine
import local.skippy.droid.features.navigation.NavigationModule
import local.skippy.droid.features.notifications.NotificationModule
import local.skippy.droid.features.services.ServiceRegistry
import local.skippy.droid.features.services.ServicesPanelModule
import local.skippy.droid.features.sidebars.LeftBarModule
import local.skippy.droid.features.sidebars.RightBarModule
import local.skippy.droid.features.speed.SpeedModule
import local.skippy.droid.features.teleprompter.TeleprompterEngine
import local.skippy.droid.features.teleprompter.TeleprompterModule
import local.skippy.droid.features.voice.VoicePillModule
import local.skippy.droid.layers.CommandDispatcher
import local.skippy.droid.layers.ContextEngine
import local.skippy.droid.layers.DeviceLayer
import local.skippy.droid.layers.FeatureModule
import local.skippy.droid.layers.GlassesLayer
import local.skippy.droid.layers.PassthroughCamera
import local.skippy.droid.layers.SoundLayer
import local.skippy.droid.layers.TransportLayer
import local.skippy.droid.layers.VoiceEngine
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    // Tailscale address of the Skippy Tel Network PC.
    // Emulator lane uses port 3004; real phone uses 3003. Change in lockstep
    // with SkippyTel-side port binding. MagicDNS resolves `skippy-pc` on tailnet.
    private val pcUrl = "http://skippy-pc:3004"

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

    /**
     * Session 7b — passthrough host. Owns the embedded HTTP server, scene
     * renderer, frame-stream consumer, and pose publisher. Registered as
     * a FeatureModule at [HudZone.Viewport] so any mounted view paints
     * into the 1540×960 center rectangle.
     */
    private lateinit var passthroughHost: PassthroughHost

    /**
     * Session 11e — in-process mock passthrough producer (debug builds only).
     * Self-registers as "Mock Star Map" on [MockPassthroughView.producerPort]
     * and streams synthetic cyan/amber stars over MJPEG. Kept in a lateinit so
     * [onStop] can tear it down cleanly.
     */
    private var mockPassthrough: MockPassthroughView? = null

    /** SkippyTel live service manifest — polls GET /services every 60s. */
    private lateinit var serviceRegistry: ServiceRegistry

    /** Session 9 — non-speech per-ear chime primitive (confirm / error / notify). */
    private lateinit var sound: SoundLayer

    /** Session 9 — voice-controlled teleprompter state. */
    private lateinit var teleprompter: TeleprompterEngine

    private lateinit var displayManager: DisplayManager
    private var glassesPresentation: GlassesPresentation? = null

    /**
     * Glasses display mode + shading state. Mutated from voice-intent handlers
     * ("fullscreen", "hud", "shades", "eyes open", "I'm listening"). Observable
     * via Compose mutableStateOf — Compositor recomposes automatically on change.
     * Survives phone rotation because SkippyDroid uses moveTaskToBack(true).
     */
    private val glassesDisplay = GlassesDisplayEngine()

    /**
     * Observable mirror of `glassesPresentation != null`. The phone-mirror
     * `setContent` block reads this to decide between a black backdrop
     * (glasses own the camera — the phone only shows chrome) and a live
     * camera preview (no glasses — the phone is the stand-in viewfinder).
     *
     * Toggled in exactly two places: [showGlasses] sets it `true`, and
     * [DisplayManager.DisplayListener.onDisplayRemoved] sets it `false`.
     * `mutableStateOf` so Compose observes the flip and recomposes the
     * backdrop without any manual invalidation.
     */
    private var glassesAttached by mutableStateOf(false)

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
        sound         = SoundLayer().also { it.start() }
        teleprompter  = TeleprompterEngine()

        // One-input dispatcher — constructed AFTER engines exist so registered
        // intent handlers can capture them by reference.
        cmd = CommandDispatcher()
        registerIntents(cmd)
        wireDispatcherChimes(cmd, sound)
        voice.dispatcher = cmd

        // Session 7b — passthrough viewport. Constructed here so the module
        // list below can include it at HudZone.Viewport.
        passthroughHost = PassthroughHost(applicationContext, glassesLayer, cmd)

        // Service discovery — polls SkippyTel GET /services every 60s.
        // Uses pcUrl (real phone via Tailscale) or 10.0.2.2:3003 for emulator.
        val serviceUrl = if (pcUrl.contains("skippy-pc")) pcUrl else "http://10.0.2.2:3003"
        serviceRegistry = ServiceRegistry(serviceUrl).also { it.start() }

        // Persist the resolved URL so the Service Grid Widget can reach SkippyTel
        // without needing the Activity to be running.
        getSharedPreferences("skippy_prefs", MODE_PRIVATE).edit()
            .putString("skippytel_url", serviceUrl)
            .apply()

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
            // Session 7 reserved BottomEnd slot — context-mode tag.
            ContextModeModule(contextEngine),
            // Session 9 — Captain's-own-words pill pulled out of the Activity.
            VoicePillModule(voice),
            // Session 9 — transient notification toast (TopCenter, 3.5s fade).
            NotificationModule(sound),
            // Session 9 — voice-controlled teleprompter (BottomCenter, hidden until loaded).
            TeleprompterModule(teleprompter),
            // Session 7b — Viewport host for mounted passthrough apps.
            passthroughHost,
            // Session 11e — TopStart panel listing registered passthrough views
            // plus live SkippyTel services from the manifest.
            ServicesPanelModule(passthroughHost, serviceRegistry),
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
            // The phone IS the glasses mirror, always on — every HUD element is now
            // a FeatureModule anchored to a HudZone by the Compositor. No hardcoded
            // pills, no DEBUG chrome cluttering the corners.
            //
            // letterboxColor = MirrorLetterbox paints blue bars around the 16:10
            // glasses canvas on the phone's wider-than-16:10 screen, so what the
            // Captain sees on the phone is exactly what the glasses render — no
            // horizontal stretch. The GlassesPresentation keeps the default
            // (Black = additive-transparent) so the glasses display itself never
            // shows a letterbox.
            //
            // Backdrop logic: when the VITURE glasses are attached they've
            // claimed the Camera2 pipeline (PassthroughCamera policy) and
            // the phone mirror is chrome-only — black backdrop. When glasses
            // are NOT attached the phone becomes the stand-in viewfinder:
            // we drop a CameraPassthrough under the HUD so the rear camera
            // paints the same "look through the lenses" view the Captain
            // would see through the real glasses, with the HUD composited
            // on top. The PassthroughCamera class handles the camera hand-
            // off to whichever surface is currently attached; we just wire
            // the attach/detach callbacks to the phone side.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(HudPalette.MirrorLetterbox)
            ) {
                Compositor(
                    modules,
                    contextEngine,
                    isGlasses = true,
                    letterboxColor = HudPalette.MirrorLetterbox,
                    backdrop = {
                        if (glassesAttached) {
                            // Glasses own the camera. Phone mirror backdrop
                            // stays black (matches what the glasses' additive
                            // display reads as — the Captain sees the world
                            // through the lenses, not on the phone).
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(HudPalette.Black)
                            )
                        } else {
                            // No glasses — phone becomes a viewfinder.
                            CameraPassthrough(
                                onSurfaceAvailable = passthrough::attachPhone,
                                onSurfaceDestroyed = passthrough::detachPhone,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    },
                )
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
                Log.d("Local.Skippy", "onDisplayAdded: id=$displayId presCount=${presDisplays.size}")
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
                        glassesAttached = false
                        // If the Activity is currently backgrounded (Captain
                        // is in SkippyChat), onStop already ran and skipped
                        // the engine teardown because glasses were attached.
                        // Now that the glasses are gone AND we're not the
                        // foreground app, there's no reason to keep GPS /
                        // voice / camera / passthrough alive. Drop them.
                        if (!isFinishing) {
                            val visible = lifecycle.currentState
                                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                            if (!visible) stopEnginesIfRunning()
                        }
                    }
                }
            }
        }

        override fun onDisplayChanged(displayId: Int) {}
    }

    private fun showGlasses(display: android.view.Display) {
        Log.d("Local.Skippy", "showGlasses: displayId=${display.displayId} name=${display.name}")
        glassesPresentation?.dismiss()
        glassesPresentation = GlassesPresentation(
            activity      = this,
            display       = display,
            modules       = modules,
            passthrough   = passthrough,
            contextEngine = contextEngine,
            displayEngine = glassesDisplay,
        ).also { it.show() }
        glassesAttached = true
        Log.d("Local.Skippy", "showGlasses: presentation shown")
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
            id          = "local.skippy.navigate",
            phrases     = listOf("navigate to "),
            description = "Start walking navigation to a destination",
        ) { destination ->
            if (destination.isEmpty()) {
                Log.w("Local.Skippy", "voice navigate with empty destination")
                return@register
            }
            val loc = device.location
            if (loc == null) {
                Log.w("Local.Skippy", "voice navigate '$destination' but no GPS fix yet")
                return@register
            }
            Log.d("Local.Skippy", "voice navigate: $destination")
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
            id          = "local.skippy.cancel",
            phrases     = listOf("cancel", "stop navigation", "never mind"),
            description = "Cancel the active navigation route",
        ) {
            Log.d("Local.Skippy", "voice cancel")
            navEngine.cancel()
        }

        // ── teleprompter ────────────────────────────────────────────────────
        // "prompt faster" / "prompt slower" match BEFORE "prompt <text>" so
        // the modifier forms don't get interpreted as new scripts to load.
        d.register(
            id          = "local.skippy.teleprompt_faster",
            phrases     = listOf("prompt faster", "teleprompt faster", "scroll faster"),
            description = "Speed up the teleprompter scroll rate",
        ) {
            teleprompter.faster()
        }
        d.register(
            id          = "local.skippy.teleprompt_slower",
            phrases     = listOf("prompt slower", "teleprompt slower", "scroll slower"),
            description = "Slow down the teleprompter scroll rate",
        ) {
            teleprompter.slower()
        }
        d.register(
            id          = "local.skippy.teleprompt_pause",
            phrases     = listOf("prompt pause", "pause prompt", "pause teleprompt"),
            description = "Pause the teleprompter at the current word",
        ) {
            teleprompter.pause()
        }
        d.register(
            id          = "local.skippy.teleprompt_resume",
            phrases     = listOf("prompt resume", "resume prompt", "resume teleprompt"),
            description = "Resume teleprompter scrolling",
        ) {
            teleprompter.resume()
        }
        d.register(
            id          = "local.skippy.teleprompt_close",
            phrases     = listOf("prompt close", "close prompt", "close teleprompt"),
            description = "Close the teleprompter",
        ) {
            teleprompter.close()
        }
        // "prompt <script text>" — load and auto-scroll. This phrase must be
        // registered AFTER the modifier variants so the substring matcher
        // doesn't grab e.g. "prompt faster" as a script reading "faster".
        d.register(
            id          = "local.skippy.teleprompt_load",
            phrases     = listOf("prompt ", "teleprompt "),
            description = "Load text into the teleprompter and start scrolling",
        ) { script ->
            if (script.isEmpty()) {
                Log.w("Local.Skippy", "teleprompt load with empty script")
                return@register
            }
            teleprompter.load(script)
        }

        // ── explain (SkippyTel /intent/unmatched) ───────────────────────────
        // "explain <topic>" / "skippy explain <topic>" — escalate to the PC's
        // LLM router and render the reply through the existing teleprompter.
        // The teleprompter's 18-word scrolling window handles multi-paragraph
        // replies without ellipsis or scrollbars; voice verbs "prompt pause",
        // "prompt faster", etc. already control it. Empty topic → no-op.
        d.register(
            id          = "local.skippy.explain",
            phrases     = listOf("skippy explain ", "explain "),
            description = "Ask SkippyTel's LLM to explain a topic; reply lands in the teleprompter",
        ) { topic ->
            val query = topic.trim()
            if (query.isEmpty()) {
                Log.w("Local.Skippy", "explain with empty topic")
                sound.error()
                return@register
            }
            // Confirm chime the moment we accept the command — the Captain
            // hears "I heard you, I'm thinking" even while the LLM chews on
            // it. The teleprompter loads when (and only if) a reply arrives.
            sound.confirm()
            Log.d("Local.Skippy", "explain: '$query'")
            lifecycleScope.launch {
                val reply = withContext(Dispatchers.IO) {
                    transport.postIntentUnmatched(
                        text    = query,
                        source  = "voice",
                        context = mapOf("mode" to contextEngine.currentMode.name.lowercase()),
                    )
                }
                if (reply == null) {
                    Log.w("Local.Skippy", "explain: no reply (offline / timeout / malformed)")
                    sound.error()
                    return@launch
                }
                Log.d("Local.Skippy", "explain: ${reply.reply.length} chars, tier=${reply.tier}")
                teleprompter.load(reply.reply)
            }
        }

        // ── services.list / services.open (Session 11e) ─────────────────────
        // Enumerate currently-registered passthrough views, or activate one
        // by (fuzzy-tolerated) name. The list is a live read of the registry
        // so newly-registered Mac producers (DJ Organizer, Star Map) are
        // immediately addressable without re-registering voice intents.
        d.register(
            id          = "local.skippy.services_list",
            phrases     = listOf(
                "show services", "list services",
                "available apps", "what apps", "what services",
            ),
            description = "Enumerate registered passthrough views",
        ) {
            val views = passthroughHost.registry.list()
            val active = passthroughHost.registry.active?.view?.id
            if (views.isEmpty()) {
                Log.i("Local.Skippy.Services", "no services registered")
            } else {
                val lines = views.joinToString("\n") { v ->
                    val mark = if (v.id == active) "▸" else " "
                    "  $mark ${v.name} (${v.id})"
                }
                Log.i("Local.Skippy.Services", "registered services (${views.size}):\n$lines")
            }
            sound.confirm()
        }

        d.register(
            id          = "local.skippy.services_open",
            phrases     = listOf("open ", "launch ", "mount "),
            description = "Mount a registered passthrough view by name",
        ) { tail ->
            val query = tail.trim().lowercase()
            if (query.isEmpty()) {
                Log.w("Local.Skippy.Services", "open with empty name")
                sound.error()
                return@register
            }
            val views = passthroughHost.registry.list()
            // 1. Exact-id or exact-lowercased-name.
            var match = views.firstOrNull { it.id == query || it.name.lowercase() == query }
            // 2. Substring on name (either direction) — handles "star map"
            //    matching "Mock Star Map" and "mock star map" matching "Star Map".
            if (match == null) {
                match = views.firstOrNull { v ->
                    val lname = v.name.lowercase()
                    lname.contains(query) || query.contains(lname)
                }
            }
            if (match == null) {
                Log.w("Local.Skippy.Services", "no service matching '$query' in ${views.map { it.name }}")
                sound.error()
                return@register
            }
            Log.i("Local.Skippy.Services", "activate '${match.name}' (${match.id}) for query '$query'")
            passthroughHost.activate(match.id)
            sound.confirm()
        }

        // ── Glasses display modes ───────────────────────────────────────────
        // Five voice keywords control the glasses display independently of
        // which phone app is in the foreground. SkippyDroid stays alive via
        // moveTaskToBack so GlassesPresentation keeps rendering.

        d.register(
            id          = "local.skippy.glasses.fullscreen",
            phrases     = listOf("fullscreen", "go fullscreen"),
            description = "Glasses: hide HUD chrome, service fills the full display",
        ) {
            glassesDisplay.mode = GlassesDisplayEngine.Mode.FULLSCREEN
            sound.confirm()
        }

        d.register(
            id          = "local.skippy.glasses.hud",
            phrases     = listOf("hud mode", "back to hud", "hud"),
            description = "Glasses: restore HUD chrome around the service viewport",
        ) {
            glassesDisplay.mode = GlassesDisplayEngine.Mode.HUD
            sound.confirm()
        }

        d.register(
            id          = "local.skippy.glasses.shades",
            phrases     = listOf("put on shades", "shades"),
            description = "Glasses: darken display with shading overlay",
        ) {
            glassesDisplay.shaded = true
            sound.confirm()
        }

        d.register(
            id          = "local.skippy.glasses.eyes_open",
            phrases     = listOf("eyes open", "shades off"),
            description = "Glasses: remove shading overlay",
        ) {
            glassesDisplay.shaded = false
            sound.confirm()
        }

        d.register(
            id          = "local.skippy.glasses.listening",
            phrases     = listOf("i'm listening", "i am listening"),
            description = "Glasses: clear shading and activate voice recognizer",
        ) {
            glassesDisplay.shaded = false
            if (!voice.isListening) voice.start()
        }

        // ── inventory / help ────────────────────────────────────────────────
        // The dispatcher owns the reserved verbs; we just observe the request.
        // Phase 1: log the live verb set so the Captain sees it in adb logcat.
        // Phase 2: TTS the list through an on-glasses speech channel.
        d.onInventoryRequest = { live ->
            val lines = live.joinToString("\n") { "  • ${it.id}: ${it.description}" }
            Log.d("Local.Skippy.Inventory", "live intents (${live.size}):\n$lines")
        }

        // ── unmatched ───────────────────────────────────────────────────────
        // Logged at DEBUG so we can see what verbs the Captain is trying that
        // aren't wired yet. Phase 2 will escalate unmatched text to the PC MCP.
        d.onUnmatched = { text, source ->
            Log.d("Local.Skippy.Dispatcher", "unmatched [$source]: '$text'")
        }
    }

    /**
     * Layer a per-ear chime on top of dispatch events: left-ear error chime
     * when no intent matched, right-ear confirm chime when one did. Phase 1
     * wires this purely by observation — we don't change dispatcher behavior,
     * we just replicate the hook pattern on top of [onInventoryRequest] and
     * [onUnmatched] plus a small decoration around [CommandDispatcher.register].
     */
    private fun wireDispatcherChimes(d: CommandDispatcher, s: SoundLayer) {
        // Error chime on unmatched — extend the existing callback.
        val priorUnmatched = d.onUnmatched
        d.onUnmatched = { text, source ->
            s.error()
            priorUnmatched?.invoke(text, source)
        }
        // Confirm chime on inventory request (there's no global "matched"
        // hook yet; individual handlers can call s.confirm() themselves if
        // they want to ack silently-handled commands). For Phase 1 we leave
        // per-intent chimes to the handlers — the unmatched chime alone is
        // enough to tell the Captain "I heard something, but not a verb."
        val priorInventory = d.onInventoryRequest
        d.onInventoryRequest = { live ->
            s.confirm()
            priorInventory?.invoke(live)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    //
    // Engine lifecycle is gated by the `enginesRunning` flag rather than
    // being bound 1:1 to onStart/onStop. That way the rotation handoff
    // (landscape → SkippyChat → back to landscape) doesn't tear down and
    // re-initialize GPS / voice / camera / passthrough on every flip, and
    // — more importantly — when the VITURE is rendering the HUD on an
    // external display, the HUD keeps updating even while SkippyChat owns
    // the phone screen. Captain's doctrine: the glasses are stuck with
    // their HUD forever; it never goes dark because a different app took
    // the phone.
    //
    // Shutdown paths:
    //   • onStop with no glasses attached → stop engines (save battery).
    //   • onStop with glasses attached    → keep engines warm.
    //   • onDestroy (task killed)         → unconditional full stop.

    private var enginesRunning = false

    override fun onStart() {
        super.onStart()
        startEnginesIfNeeded()

        // Scan for presentation displays here (not onCreate) so Presentation.show()
        // has a live window to render into.
        val presDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        Log.d("Local.Skippy", "onStart: found ${presDisplays.size} presentation display(s)")
        presDisplays.firstOrNull()?.let { showGlasses(it) }
    }

    override fun onStop() {
        super.onStop()
        if (glassesPresentation != null) {
            // VITURE still has a live HUD on the external display.
            // Freezing the engines here would freeze the HUD data feeds;
            // the glasses would look like they'd crashed. Keep warm.
            Log.d("Local.Skippy", "onStop: glasses attached — keeping engines warm")
            return
        }
        stopEnginesIfRunning()
    }

    override fun onDestroy() {
        super.onDestroy()
        displayManager.unregisterDisplayListener(displayListener)
        glassesPresentation?.dismiss()
        glassesPresentation = null
        stopEnginesIfRunning()     // make sure we shut down even if onStop skipped it
        passthrough.detachPhone()
        sound.stop()
        teleprompter.close()
    }

    private fun startEnginesIfNeeded() {
        if (enginesRunning) return
        device.start()
        glassesLayer.start()
        transport.start()
        contextEngine.start()
        // Voice only starts if RECORD_AUDIO is already granted; otherwise
        // it starts from the requestMicPermission callback once the
        // Captain grants it.
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            voice.start()
        }

        // Session 7b — boot the passthrough host's embedded server.
        passthroughHost.onEnable()

        // Session 11e — debug-only mock producer. Self-registers as
        // "Mock Star Map" once the host server is up so the ServicesPanel
        // has something to show immediately. Release builds skip this;
        // real Mac-side StarMap will replace it via the same /register path.
        if (BuildConfig.DEBUG && mockPassthrough == null) {
            mockPassthrough = MockPassthroughView().also { it.start() }
            Log.i("Local.Skippy", "MockPassthroughView started on :47824")
        }
        enginesRunning = true
    }

    private fun stopEnginesIfRunning() {
        if (!enginesRunning) return
        device.stop()
        glassesLayer.stop()
        transport.stop()
        contextEngine.stop()
        voice.stop()
        mockPassthrough?.stop()
        mockPassthrough = null
        passthroughHost.onDisable()
        enginesRunning = false
    }

    // ── Rotation handoff ──────────────────────────────────────────────────────
    //
    // Captain's doctrine: rotation IS the launcher. Each of the four physical
    // rotations dispatches to a different surface. The `display.rotation` API
    // exposes 4 distinct values; `Configuration.orientation` only exposes 2,
    // which is why this used to flip flop between landscape and portrait only.
    //
    // Slot map:
    //   ROTATION_0            USB at bottom  natural portrait    → SkippyChat
    //   SLOT_HUD_ROTATION     USB on right   landscape           → THIS app (stay)
    //   SLOT_BROWSER_ROTATION USB at top     upside-down         → Browser (Chrome)
    //   SLOT_SERVICES_ROTATION USB on left   landscape           → Service selector
    //
    // Service selector (ROTATION_270 / USB-left): Captain rotates left to browse
    // registered passthrough producers (DJ Block Planner, etc.) and mount one
    // fullscreen. Launches `local.skippy.services` when installed; stays put if
    // that package is absent (graceful stub until the app exists).
    //
    // SLOT_HUD_ROTATION value below pins which landscape rotation puts USB on
    // the right — observed empirically from `adb emu rotate` on the SkippyS23
    // AVD. Flip the two named constants if the test on real hardware
    // disagrees with the AVD.
    //
    // Handoff strategy: when our current rotation isn't our slot we launch
    // the appropriate target (singleTask sibling, Chrome, or the Android
    // launcher) and `moveTaskToBack(true)` ourselves. That preserves all of
    // SkippyDroid's expensive warm state (camera2 session, VoiceEngine, GPS
    // listener, passthrough server, teleprompter contents) across mode
    // flips — `finish()` + full re-init on every rotation would be ruinous.
    //
    // Caveat for the Chrome and Home slots: those external apps don't honor
    // our rotation listener. Once Captain is in Chrome, rotating the device
    // does NOT switch back to a Skippy app — Captain returns manually
    // (recents, gesture, app icon), and our `onResume` re-runs `maybeHandoff`
    // to pick up whatever rotation is current at the moment we resume.
    //
    // Both manifests declare `screenOrientation="sensor"` +
    // `launchMode="singleTask"` + `configChanges` covering orientation, so a
    // rotation does NOT recreate the activity — it fires
    // `onConfigurationChanged`. We also check on `onResume` for
    // cold-launch-in-the-wrong-rotation and return-from-background paths.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        maybeHandoff()
    }

    override fun onResume() {
        super.onResume()
        maybeHandoff()
    }

    /**
     * Read the current display rotation and dispatch to the matching slot.
     * No-op when the current rotation IS our slot.
     *
     * `display` (Activity.getDisplay) is API 30+. Both apps target minSdk 30
     * so it's always non-null at runtime; the `?:` fallback to
     * `windowManager.defaultDisplay` is paranoia for unusual configurations
     * (e.g. activity not yet attached to a window — should not happen post-
     * onCreate but cheap to guard).
     */
    @Suppress("DEPRECATION")
    private fun maybeHandoff() {
        val rotation = display?.rotation ?: windowManager.defaultDisplay.rotation
        when (rotation) {
            SLOT_HUD_ROTATION      -> return                          // we ARE the HUD slot
            Surface.ROTATION_0     -> launchSibling(SKIPPY_CHAT_PKG, "Chat")
            SLOT_BROWSER_ROTATION  -> launchBrowser()
            SLOT_SERVICES_ROTATION -> launchServiceSelector()
            else                   -> Log.w("Local.Skippy", "rotation=$rotation has no slot; staying")
        }
    }

    private fun launchSibling(pkg: String, label: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            Log.i("Local.Skippy", "rotation slot wants $pkg ($label) but it's not installed; staying")
            return
        }
        Log.d("Local.Skippy", "rotation → handing off to $pkg ($label)")
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        startActivity(launch)
        crossfadeOut()
        moveTaskToBack(true)
    }

    private fun launchBrowser() {
        // USB-top (ROTATION_180) = browser slot.
        // Try real Chrome first; fall back to ACTION_VIEW for emulator/other hosts.
        val chrome = packageManager.getLaunchIntentForPackage("com.android.chrome")
        val intent = chrome ?: Intent(Intent.ACTION_VIEW, Uri.parse("about:blank"))
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        Log.d("Local.Skippy", "rotation → browser slot (${if (chrome != null) "Chrome" else "system browser"})")
        try {
            startActivity(intent)
            crossfadeOut()
            moveTaskToBack(true)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w("Local.Skippy", "no browser available for browser slot; staying", e)
        }
    }

    private fun launchServiceSelector() {
        // USB-left (ROTATION_270) = service selector slot.
        // Launches local.skippy.services when installed; that app shows the
        // registered passthrough producers (DJ Block Planner, etc.) and lets
        // Captain mount one fullscreen on the glasses display.
        // Stub: stays put gracefully until the app is built.
        val intent = packageManager.getLaunchIntentForPackage(SKIPPY_SERVICES_PKG)
        if (intent == null) {
            Log.i("Local.Skippy", "service selector ($SKIPPY_SERVICES_PKG) not installed; staying in HUD")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        Log.d("Local.Skippy", "rotation → service selector")
        startActivity(intent)
        crossfadeOut()
        moveTaskToBack(true)
    }

    /**
     * Crossfade transition to mask the task swap. Replaces the older
     * `overridePendingTransition(0, 0)` (instant cut) — the fade takes ~200ms
     * and is much less jarring as Captain rotates through the slots. API 34
     * deprecated this in favour of `overrideActivityTransition` but the old
     * API still works identically; not worth a version-gated split.
     */
    @Suppress("DEPRECATION")
    private fun crossfadeOut() {
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private companion object {
        const val SKIPPY_CHAT_PKG     = "local.skippy.chat"
        const val SKIPPY_SERVICES_PKG = "local.skippy.services"  // not yet built

        // Four-slot rotation doctrine (must agree with SkippyChat's MainActivity):
        //   ROTATION_0   USB-down  → SkippyChat
        //   ROTATION_90  USB-right → SkippyDroid HUD  (this app)
        //   ROTATION_180 USB-up    → Browser
        //   ROTATION_270 USB-left  → Service selector (local.skippy.services)
        //
        // Values observed empirically on SkippyS23 AVD via `adb emu rotate`.
        // Flip HUD/SERVICES pair if real S23 hardware disagrees.
        const val SLOT_HUD_ROTATION      = Surface.ROTATION_90
        const val SLOT_BROWSER_ROTATION  = Surface.ROTATION_180
        const val SLOT_SERVICES_ROTATION = Surface.ROTATION_270
    }
}
