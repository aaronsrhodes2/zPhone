package local.skippy.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
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
    private val client       = SkippyTelClient(baseUrl = "http://100.122.71.14:3003")
    private val viewModel    = ChatViewModel(client)
    private val speechEngine = SpeechInputEngine(this)
    private val audioRouter  = AudioRouter(this)

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

    override fun onStart() {
        super.onStart()
        client.start()
        audioRouter.start()
        startSpeechEngineIfPermitted()
        requestSmsPermissionsIfNeeded()
        startDropShipWatcherIfPermitted()
    }

    override fun onStop() {
        super.onStop()
        client.stop()
        audioRouter.stop()
        speechEngine.stop()
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
        return super.onKeyDown(keyCode, event)
    }

    // ── Rotation handoff ──────────────────────────────────────────────────
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        maybeHandoff()
    }

    override fun onResume() {
        super.onResume()
        maybeHandoff()
    }

    @Suppress("DEPRECATION")
    private fun maybeHandoff() {
        val rotation = display?.rotation ?: windowManager.defaultDisplay.rotation
        when (rotation) {
            android.view.Surface.ROTATION_0 -> return
            SLOT_HUD_ROTATION               -> launchSibling(SKIPPY_DROID_PKG, "HUD")
            SLOT_BROWSER_ROTATION           -> launchBrowser()
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

    private fun launchBrowser() {
        val chrome = packageManager.getLaunchIntentForPackage("com.android.chrome")
        val intent = chrome ?: Intent(Intent.ACTION_VIEW, Uri.parse("about:blank"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        Log.d(TAG, "rotation → browser slot")
        try {
            startActivity(intent)
            crossfadeOut()
            moveTaskToBack(true)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "no browser for browser slot; staying", e)
        }
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
        const val SLOT_HUD_ROTATION      = android.view.Surface.ROTATION_90
        const val SLOT_BROWSER_ROTATION  = android.view.Surface.ROTATION_180
        const val SLOT_SERVICES_ROTATION = android.view.Surface.ROTATION_270
    }
}
