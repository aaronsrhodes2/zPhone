package com.skippy.droid

import android.content.Intent
import android.content.res.Configuration
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.skippy.droid.BuildConfig
import com.skippy.droid.compositor.Compositor
import com.skippy.droid.compositor.GlassesPresentation
import com.skippy.droid.compositor.HudPalette
import com.skippy.droid.layers.CameraPassthrough
import com.skippy.droid.compositor.passthrough.MockPassthroughView
import com.skippy.droid.compositor.passthrough.PassthroughHost
import com.skippy.droid.features.battery.BatteryModule
import com.skippy.droid.features.clock.ClockModule
import com.skippy.droid.features.compass.CompassModule
import com.skippy.droid.features.context.ContextModeModule
import com.skippy.droid.features.coordinates.CoordinatesModule
import com.skippy.droid.features.navigation.NavigationEngine
import com.skippy.droid.features.navigation.NavigationModule
import com.skippy.droid.features.notifications.NotificationModule
import com.skippy.droid.features.services.ServicesPanelModule
import com.skippy.droid.features.sidebars.LeftBarModule
import com.skippy.droid.features.sidebars.RightBarModule
import com.skippy.droid.features.speed.SpeedModule
import com.skippy.droid.features.teleprompter.TeleprompterEngine
import com.skippy.droid.features.teleprompter.TeleprompterModule
import com.skippy.droid.features.voice.VoicePillModule
import com.skippy.droid.layers.CommandDispatcher
import com.skippy.droid.layers.ContextEngine
import com.skippy.droid.layers.DeviceLayer
import com.skippy.droid.layers.FeatureModule
import com.skippy.droid.layers.GlassesLayer
import com.skippy.droid.layers.PassthroughCamera
import com.skippy.droid.layers.SoundLayer
import com.skippy.droid.layers.TransportLayer
import com.skippy.droid.layers.VoiceEngine
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    // Tailscale address of the Skippy Tel Network PC.
    // SkippyTel runs on port 3003 (separate service from the monorepo's
    // flask-api on 5001). MagicDNS resolves `skippy-pc` inside the tailnet.
    private val pcUrl = "http://skippy-pc:3003"

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

    /** Session 9 — non-speech per-ear chime primitive (confirm / error / notify). */
    private lateinit var sound: SoundLayer

    /** Session 9 — voice-controlled teleprompter state. */
    private lateinit var teleprompter: TeleprompterEngine

    private lateinit var displayManager: DisplayManager
    private var glassesPresentation: GlassesPresentation? = null

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
            // Session 11e — TopStart panel listing registered passthrough views.
            ServicesPanelModule(passthroughHost),
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
        Log.d("Skippy", "showGlasses: displayId=${display.displayId} name=${display.name}")
        glassesPresentation?.dismiss()
        glassesPresentation = GlassesPresentation(this, display, modules, passthrough, contextEngine)
            .also { it.show() }
        glassesAttached = true
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

        // ── teleprompter ────────────────────────────────────────────────────
        // "prompt faster" / "prompt slower" match BEFORE "prompt <text>" so
        // the modifier forms don't get interpreted as new scripts to load.
        d.register(
            id          = "teleprompt_faster",
            phrases     = listOf("prompt faster", "teleprompt faster", "scroll faster"),
            description = "Speed up the teleprompter scroll rate",
        ) {
            teleprompter.faster()
        }
        d.register(
            id          = "teleprompt_slower",
            phrases     = listOf("prompt slower", "teleprompt slower", "scroll slower"),
            description = "Slow down the teleprompter scroll rate",
        ) {
            teleprompter.slower()
        }
        d.register(
            id          = "teleprompt_pause",
            phrases     = listOf("prompt pause", "pause prompt", "pause teleprompt"),
            description = "Pause the teleprompter at the current word",
        ) {
            teleprompter.pause()
        }
        d.register(
            id          = "teleprompt_resume",
            phrases     = listOf("prompt resume", "resume prompt", "resume teleprompt"),
            description = "Resume teleprompter scrolling",
        ) {
            teleprompter.resume()
        }
        d.register(
            id          = "teleprompt_close",
            phrases     = listOf("prompt close", "close prompt", "close teleprompt"),
            description = "Close the teleprompter",
        ) {
            teleprompter.close()
        }
        // "prompt <script text>" — load and auto-scroll. This phrase must be
        // registered AFTER the modifier variants so the substring matcher
        // doesn't grab e.g. "prompt faster" as a script reading "faster".
        d.register(
            id          = "teleprompt_load",
            phrases     = listOf("prompt ", "teleprompt "),
            description = "Load text into the teleprompter and start scrolling",
        ) { script ->
            if (script.isEmpty()) {
                Log.w("Skippy", "teleprompt load with empty script")
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
            id          = "explain",
            phrases     = listOf("skippy explain ", "explain "),
            description = "Ask SkippyTel's LLM to explain a topic; reply lands in the teleprompter",
        ) { topic ->
            val query = topic.trim()
            if (query.isEmpty()) {
                Log.w("Skippy", "explain with empty topic")
                sound.error()
                return@register
            }
            // Confirm chime the moment we accept the command — the Captain
            // hears "I heard you, I'm thinking" even while the LLM chews on
            // it. The teleprompter loads when (and only if) a reply arrives.
            sound.confirm()
            Log.d("Skippy", "explain: '$query'")
            lifecycleScope.launch {
                val reply = withContext(Dispatchers.IO) {
                    transport.postIntentUnmatched(
                        text    = query,
                        source  = "voice",
                        context = mapOf("mode" to contextEngine.currentMode.name.lowercase()),
                    )
                }
                if (reply == null) {
                    Log.w("Skippy", "explain: no reply (offline / timeout / malformed)")
                    sound.error()
                    return@launch
                }
                Log.d("Skippy", "explain: ${reply.reply.length} chars, tier=${reply.tier}")
                teleprompter.load(reply.reply)
            }
        }

        // ── services.list / services.open (Session 11e) ─────────────────────
        // Enumerate currently-registered passthrough views, or activate one
        // by (fuzzy-tolerated) name. The list is a live read of the registry
        // so newly-registered Mac producers (DJ Organizer, Star Map) are
        // immediately addressable without re-registering voice intents.
        d.register(
            id          = "services.list",
            phrases     = listOf(
                "show services", "list services",
                "available apps", "what apps", "what services",
            ),
            description = "Enumerate registered passthrough views",
        ) {
            val views = passthroughHost.registry.list()
            val active = passthroughHost.registry.active?.view?.id
            if (views.isEmpty()) {
                Log.i("Skippy.Services", "no services registered")
            } else {
                val lines = views.joinToString("\n") { v ->
                    val mark = if (v.id == active) "▸" else " "
                    "  $mark ${v.name} (${v.id})"
                }
                Log.i("Skippy.Services", "registered services (${views.size}):\n$lines")
            }
            sound.confirm()
        }

        d.register(
            id          = "services.open",
            phrases     = listOf("open ", "launch ", "mount "),
            description = "Mount a registered passthrough view by name",
        ) { tail ->
            val query = tail.trim().lowercase()
            if (query.isEmpty()) {
                Log.w("Skippy.Services", "open with empty name")
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
                Log.w("Skippy.Services", "no service matching '$query' in ${views.map { it.name }}")
                sound.error()
                return@register
            }
            Log.i("Skippy.Services", "activate '${match.name}' (${match.id}) for query '$query'")
            passthroughHost.activate(match.id)
            sound.confirm()
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
        Log.d("Skippy", "onStart: found ${presDisplays.size} presentation display(s)")
        presDisplays.firstOrNull()?.let { showGlasses(it) }
    }

    override fun onStop() {
        super.onStop()
        if (glassesPresentation != null) {
            // VITURE still has a live HUD on the external display.
            // Freezing the engines here would freeze the HUD data feeds;
            // the glasses would look like they'd crashed. Keep warm.
            Log.d("Skippy", "onStop: glasses attached — keeping engines warm")
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
            Log.i("Skippy", "MockPassthroughView started on :47824")
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

    // ── Orientation handoff ───────────────────────────────────────────────────
    //
    // The Captain switches between the two phone modes purely by rotating the
    // handset: landscape → this app (glasses mirror), portrait → SkippyChat.
    // Both manifests declare `screenOrientation="sensor"` + `launchMode=
    // "singleTask"` + `configChanges` covering orientation, so a rotation
    // does NOT recreate the activity — it fires `onConfigurationChanged`.
    //
    // Handoff strategy: when we detect we're in the "wrong" orientation we
    // launch the sibling app (which is also singleTask, so its existing
    // instance — if any — is brought forward) and call `moveTaskToBack(true)`
    // on ourselves. That preserves ALL of SkippyDroid's expensive warm state
    // (camera session, VoiceEngine, GPS listener, passthrough server,
    // teleprompter contents) across mode flips — far cheaper than finish()
    // + full re-init on every rotation.
    //
    // We check on `onResume` (handles both cold-launch-in-portrait and
    // returning from the background in the wrong orientation) and on
    // `onConfigurationChanged` (handles live rotation while visible).
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        maybeHandoff(newConfig.orientation)
    }

    override fun onResume() {
        super.onResume()
        maybeHandoff(resources.configuration.orientation)
    }

    private fun maybeHandoff(orientation: Int) {
        // SkippyDroid is the landscape mode. Anything else (portrait,
        // undefined, square) is SkippyChat's turn.
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) return

        val launch = packageManager.getLaunchIntentForPackage(SKIPPY_CHAT_PKG)
        if (launch == null) {
            Log.i("Skippy", "portrait but $SKIPPY_CHAT_PKG not installed; staying in SkippyDroid")
            return
        }
        Log.d("Skippy", "portrait detected → handing off to $SKIPPY_CHAT_PKG")
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        startActivity(launch)
        // Suppress the rotation animation flash. API 34 introduced
        // `overrideActivityTransition` but the old call still works
        // and we don't need a version-gated split for a zero-duration
        // no-op transition.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        moveTaskToBack(true)
    }

    private companion object {
        const val SKIPPY_CHAT_PKG = "com.skippy.chat"
    }
}
