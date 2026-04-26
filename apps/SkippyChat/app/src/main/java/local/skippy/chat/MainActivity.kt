package local.skippy.chat

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import local.skippy.chat.audio.AudioRouter
import local.skippy.chat.audio.SpeechInputEngine
import local.skippy.chat.compositor.ChatPalette
import local.skippy.chat.dropship.DropShipWatcher
import local.skippy.chat.model.ChatViewModel
import local.skippy.chat.telemetry.UiTelemetry
import local.skippy.chat.transport.SkippyTelClient

/**
 * Single-Activity SkippyChat host.
 *
 * Wires:
 *   - [SkippyTelClient] against `http://skippy-pc:3004` — tailnet MagicDNS.
 *   - [ChatViewModel] holding the in-memory scrollback.
 *   - [SpeechInputEngine] for always-on STT via Android SpeechRecognizer.
 *   - [ChatScreen] as the sole Composable content.
 *
 * Hardware controls:
 *   - Volume Down (single press) → toggle mic mute on [SpeechInputEngine].
 *     The key event is consumed (returns true) so it doesn't change actual
 *     media volume while acting as a mute switch.
 *
 * Permissions:
 *   RECORD_AUDIO is requested at runtime on first launch. If denied, STT
 *   stays silent but everything else works normally.
 */
class MainActivity : ComponentActivity() {

    // Tailscale IP of skippy-pc — works from both the emulator (which can't
    // resolve MagicDNS hostnames) and a real S23 on any network.
    // If the PC's Tailscale IP ever changes: `tailscale ip -4` on the PC.
    private val client         = SkippyTelClient(baseUrl = "http://100.122.71.14:3003")
    private val viewModel      = ChatViewModel(client)
    private val speechEngine   = SpeechInputEngine(this)
    private val audioRouter    = AudioRouter(this)
    private val pocketDetector = local.skippy.chat.sensors.PocketDetector(this)

    // ── Rotation lock ─────────────────────────────────────────────────────
    //
    // Doctrine: screen-off = "going into pocket" = lock current rotation.
    // screen-on = "pulled out" = re-orient based on glasses connection.
    // Headphone button = manual override toggle.
    //
    // SCREEN_ORIENTATION_LOCKED (API 18) freezes whatever angle is current.
    // On screen-on: glasses connected → sensor landscape; else free rotation.
    //
    // This is registered/unregistered in onStart/onStop so it's active even
    // when the activity is in the background (screen turns off with another
    // app in front — we still want the lock to fire so SkippyChat is in the
    // right orientation when the Captain next opens it).

    private var orientationManuallyLocked = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Freeze current angle — the Captain is about to pocket the phone.
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                    Log.d(TAG, "screen off → orientation locked")
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (orientationManuallyLocked) return  // respect manual override
                    // Release or steer based on glasses.
                    if (audioRouter.hasExternalOutput()) {
                        // Viture XR or any external audio = glasses mode → landscape
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        Log.d(TAG, "screen on + glasses → sensor landscape")
                    } else {
                        // No external audio → free rotation, let the sensor decide
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        Log.d(TAG, "screen on + no glasses → free rotation")
                    }
                }
            }
        }
    }

    // Runtime permission launcher — requested once on first cold start.
    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Log.d(TAG, "RECORD_AUDIO granted — starting speech engine")
                speechEngine.start()
            } else {
                Log.w(TAG, "RECORD_AUDIO denied — STT disabled")
            }
        }

    // SMS + Contacts permissions — requested once on first cold start.
    // All three are needed: SEND_SMS to dispatch, RECEIVE_SMS for the
    // BroadcastReceiver, READ_CONTACTS for name→number resolution.
    private val requestSmsPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val send    = grants[Manifest.permission.SEND_SMS]    == true
            val receive = grants[Manifest.permission.RECEIVE_SMS] == true
            val contacts= grants[Manifest.permission.READ_CONTACTS] == true
            Log.d(TAG, "SMS perms — send=$send receive=$receive contacts=$contacts")
        }

    // DropShip photo permission — READ_MEDIA_IMAGES (API 33+) or
    // READ_EXTERNAL_STORAGE (API 30-32).  Once granted, start the watcher.
    private val requestPhotoPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "photo permission granted=$granted")
            if (granted) DropShipWatcher.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Force app-level night mode (API 31+) so the system keyboard renders dark.
        // The theme attributes handle API 30; this handles API 31+ where the OS
        // controls per-app night mode independently of the system setting.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (getSystemService(android.app.UiModeManager::class.java))
                .setApplicationNightMode(android.app.UiModeManager.MODE_NIGHT_YES)
        }
        super.onCreate(savedInstanceState)
        // Anchor to portrait on cold start — SkippyChat is always the landing
        // surface. onResume also re-anchors so any slot return snaps home.
        if (savedInstanceState == null) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val skippyTelUrl = getSharedPreferences("skippy_prefs", MODE_PRIVATE)
            .getString("skippytel_url", "http://10.0.2.2:3003") ?: "http://10.0.2.2:3003"
        UiTelemetry.init(skippyTelUrl, "skippy_chat")
        setContent {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ChatPalette.Black),
                color = ChatPalette.Black,
            ) {
                local.skippy.chat.ui.ChatScreen(
                    viewModel    = viewModel,
                    client       = client,
                    speechEngine = speechEngine,
                    audioRouter  = audioRouter,
                )
            }
        }

        // Hot-deploy note: when Claude Code pushes a new APK via
        //   adb shell am start -n .../MainActivity --es update_note "..."
        // the message surfaces as the first SYSTEM bubble of the new session,
        // confirming exactly what was changed and deployed.
        intent.getStringExtra("update_note")
            ?.takeIf { it.isNotEmpty() }
            ?.let { note -> viewModel.postSystemNote("⚡ $note") }
    }

    override fun onResume() {
        super.onResume()
        // Re-anchor to portrait every time SkippyChat returns to the foreground.
        // This ensures returning from a rotation slot (SkippyDroid, services)
        // always snaps back to the home orientation without manual interaction.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        orientationManuallyLocked = false   // clear headset-button lock on return
    }

    override fun onStart() {
        super.onStart()
        client.start()
        audioRouter.start()
        pocketDetector.start()
        startSpeechEngineIfPermitted()
        requestSmsPermissionsIfNeeded()
        startDropShipWatcherIfPermitted()
        // Register screen-off/on receiver for rotation lock.
        // These actions aren't deliverable via manifest; must register dynamically.
        registerReceiver(
            screenStateReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )
    }

    override fun onStop() {
        super.onStop()
        client.stop()
        audioRouter.stop()
        speechEngine.stop()
        pocketDetector.stop()
        try { unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
    }

    // ── Volume Down = mic mute toggle ─────────────────────────────────────
    //
    // Volume Down is on the left side of the S23, easy to press blind.
    // Single press toggles mute; we return true to consume the event so
    // the system volume level is not changed.
    //
    // Volume Up is left alone — use it normally for media / ringer volume.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            speechEngine.isMuted = !speechEngine.isMuted
            Log.d(TAG, "mic mute → ${speechEngine.isMuted}")
            return true   // consumed — don't change volume
        }
        // Caps Lock → "Paragraph" key: insert a newline into the draft.
        // The physical keyboard sends KEYCODE_CAPS_LOCK; we intercept it
        // here and forward it as a paragraph-break signal to the ViewModel,
        // which ChatScreen collects and converts to '\n' in the draft.
        if (keyCode == KeyEvent.KEYCODE_CAPS_LOCK) {
            viewModel.onParagraphKey()
            Log.d(TAG, "paragraph key → newline")
            return true   // consumed — don't toggle system caps lock
        }
        // Headphone button (glasses or inline remote) → manual rotation lock toggle.
        // Short press while glasses are connected: lock the current angle so handing
        // the phone to someone or flipping it on a surface won't rotate the HUD.
        // Second press releases back to sensor mode.
        if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            orientationManuallyLocked = !orientationManuallyLocked
            if (orientationManuallyLocked) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                Log.d(TAG, "headset button → orientation manually locked")
            } else {
                requestedOrientation = if (audioRouter.hasExternalOutput())
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                Log.d(TAG, "headset button → orientation released")
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ── Rotation handoff ──────────────────────────────────────────────────
    //
    // Doctrine: SkippyChat is the *home* orientation (portrait / ROTATION_0).
    // Intentional rotation by the user drives handoff to sibling apps.
    //
    // ONLY fire from onConfigurationChanged — not from onResume. This keeps
    // SkippyChat as the stable starting surface; it never auto-evicts itself
    // just because the phone happened to be tilted when it last went to sleep.
    //
    // Cold-start orientation is locked to portrait (onCreate, first launch only)
    // so the Captain always lands in SkippyChat on a fresh open.

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Guard: only handoff when the screen-off lock is NOT active.
        // If the rotation change is caused by us setting requestedOrientation
        // programmatically (screen-on handler), don't treat it as a user gesture.
        if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LOCKED) return
        maybeHandoff()
    }

    @Suppress("DEPRECATION")
    private fun maybeHandoff() {
        val rotation = display?.rotation ?: windowManager.defaultDisplay.rotation
        when (rotation) {
            android.view.Surface.ROTATION_0 -> return           // portrait → home, stay
            SLOT_HUD_ROTATION               -> launchSibling(SKIPPY_DROID_PKG, "HUD")
            SLOT_ESCAPE_ROTATION            -> launchAndroidHome()  // upside-down → Android desktop
            SLOT_SERVICES_ROTATION          -> launchServiceSelector()
            else -> Log.w(TAG, "rotation=$rotation has no slot; staying")
        }
    }

    private fun launchSibling(pkg: String, label: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: run {
            Log.i(TAG, "rotation slot wants $pkg ($label) but not installed; staying")
            return
        }
        Log.d(TAG, "rotation → handing off to $pkg ($label)")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(launch)
        crossfadeOut()
        moveTaskToBack(true)
    }

    /**
     * ROTATION_180 (upside-down) = escape hatch to the Android backup launcher.
     *
     * Resolves all home apps, picks the first one that is NOT SkippyChat, and
     * launches it directly. This lets the Captain reach Settings, install apps,
     * etc. without removing SkippyChat as the default home.
     *
     * Falls back to Samsung launcher → AOSP launcher → Android Settings
     * if the query-based resolution fails.
     */
    private fun launchAndroidHome() {
        Log.d(TAG, "rotation → Android home escape hatch")

        // Query all registered home apps, pick the one that isn't us.
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homes = packageManager.queryIntentActivities(homeIntent, 0)
        val backup = homes.firstOrNull { it.activityInfo.packageName != packageName }

        if (backup != null) {
            val launch = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                component = android.content.ComponentName(
                    backup.activityInfo.packageName,
                    backup.activityInfo.name,
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            try {
                startActivity(launch)
                crossfadeOut()
                moveTaskToBack(true)
                return
            } catch (e: Exception) {
                Log.w(TAG, "backup home launch failed: ${e.message}")
            }
        }

        // Hard fallbacks in order of preference.
        val fallbacks = listOf(
            "com.sec.android.app.launcher",         // Samsung One UI
            "com.google.android.apps.nexuslauncher", // Pixel launcher
            "com.android.launcher3",                 // AOSP / emulator
            "com.android.launcher",                  // older AOSP
        )
        for (pkg in fallbacks) {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            try {
                startActivity(intent)
                crossfadeOut()
                moveTaskToBack(true)
                return
            } catch (_: Exception) {}
        }

        // Last resort: open Settings so the Captain can navigate out.
        Log.w(TAG, "no backup launcher found — opening Settings")
        startActivity(
            Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    private fun launchServiceSelector() {
        val intent = packageManager.getLaunchIntentForPackage(SKIPPY_SERVICES_PKG) ?: run {
            Log.i(TAG, "service selector ($SKIPPY_SERVICES_PKG) not installed; staying")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        Log.d(TAG, "rotation → service selector")
        startActivity(intent)
        crossfadeOut()
        moveTaskToBack(true)
    }

    @Suppress("DEPRECATION")
    private fun crossfadeOut() {
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ── Permission helpers ────────────────────────────────────────────────

    private fun startSpeechEngineIfPermitted() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED -> speechEngine.start()
            else -> requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestSmsPermissionsIfNeeded() {
        val needed = arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS,
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) requestSmsPermissions.launch(needed.toTypedArray())
    }

    private fun startDropShipWatcherIfPermitted() {
        // API 33+ uses READ_MEDIA_IMAGES; API 30-32 uses READ_EXTERNAL_STORAGE
        val photoPermission = if (android.os.Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        when (ContextCompat.checkSelfPermission(this, photoPermission)) {
            PackageManager.PERMISSION_GRANTED -> DropShipWatcher.start(this)
            else                              -> requestPhotoPermission.launch(photoPermission)
        }
    }

    private companion object {
        const val TAG                 = "Local.Skippy.Chat"
        const val SKIPPY_DROID_PKG    = "local.skippy.droid"
        const val SKIPPY_SERVICES_PKG = "local.skippy.services"
        // Rotation slots — one full turn of the phone maps to four surfaces:
        //   0°  portrait upright     → SkippyChat (home, no handoff)
        //   90° landscape USB-right  → SkippyDroid HUD / glasses
        //  180° portrait upside-down → Android backup launcher (escape hatch)
        //  270° landscape USB-left   → Services panel
        const val SLOT_HUD_ROTATION      = android.view.Surface.ROTATION_90
        const val SLOT_ESCAPE_ROTATION   = android.view.Surface.ROTATION_180
        const val SLOT_SERVICES_ROTATION = android.view.Surface.ROTATION_270
    }
}
