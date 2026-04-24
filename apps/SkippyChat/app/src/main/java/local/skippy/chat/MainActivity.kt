package local.skippy.chat

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import local.skippy.chat.compositor.ChatPalette
import local.skippy.chat.model.ChatViewModel
import local.skippy.chat.transport.SkippyTelClient

/**
 * Single-Activity SkippyChat host.
 *
 * Wires:
 *   - [SkippyTelClient] against `http://skippy-pc:3003` — tailnet
 *     MagicDNS hostname, plaintext allowed by the network-security
 *     config (Tailscale IS the auth).
 *   - [ChatViewModel] holding the in-memory scrollback.
 *   - [ChatScreen] as the sole Composable content.
 *
 * Lifecycle: health loop starts in `onStart`, stops in `onStop`.
 * The ViewModel lives for the Activity's full lifetime and is
 * deliberately NOT a real ViewModelProvider-backed thing — Phase 1
 * scrollback is process-scoped, not config-change-resilient.
 *
 * Orientation: the manifest declares `sensor` (not `portrait`) so we
 * receive `onConfigurationChanged` when the Captain rotates to
 * landscape — and use that as the signal to hand off to SkippyDroid
 * (glasses mirror). `configChanges` covers orientation so the activity
 * itself is NOT recreated on rotation; the scrollback survives the
 * round trip.
 */
class MainActivity : ComponentActivity() {

    private val client = SkippyTelClient(baseUrl = "http://skippy-pc:3003")
    private val viewModel = ChatViewModel(client)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ChatPalette.Black),
                color = ChatPalette.Black,
            ) {
                local.skippy.chat.ui.ChatScreen(
                    viewModel = viewModel,
                    client = client,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        client.start()
    }

    override fun onStop() {
        super.onStop()
        client.stop()
    }

    // ── Rotation handoff ──────────────────────────────────────────────────────
    //
    // Mirror image of the logic in SkippyDroid's MainActivity. Captain's
    // doctrine: rotation IS the launcher; each of the four rotations is its
    // own slot. SkippyChat owns ROTATION_0 (USB at bottom, natural portrait).
    // Anything else hands off:
    //
    //   SLOT_HUD_ROTATION    USB on right    → SkippyDroid (HUD mirror)
    //   SLOT_CHROME_ROTATION USB on left     → Chrome
    //   ROTATION_180         USB at top      → Android home (reserved)
    //
    // The two SLOT_* constants must agree with SkippyDroid's MainActivity —
    // change them in lockstep.
    //
    // `moveTaskToBack(true)` (rather than finish()) keeps the OkHttp client,
    // health-loop coroutine, and ChatViewModel scrollback intact for when
    // the Captain rotates back to portrait — far cheaper than rebuilding
    // everything per flip.
    //
    // Checked on `onResume` (cold-launch-in-the-wrong-rotation, return from
    // background) and `onConfigurationChanged` (live rotation while visible).
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
            android.view.Surface.ROTATION_0   -> return                       // we ARE the Chat slot
            SLOT_HUD_ROTATION                 -> launchSibling(SKIPPY_DROID_PKG, "HUD")
            SLOT_CHROME_ROTATION              -> launchChrome()
            android.view.Surface.ROTATION_180 -> launchHome()
            else                              -> Log.w(TAG, "rotation=$rotation has no slot; staying")
        }
    }

    private fun launchSibling(pkg: String, label: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            Log.i(TAG, "rotation slot wants $pkg ($label) but it's not installed; staying")
            return
        }
        Log.d(TAG, "rotation → handing off to $pkg ($label)")
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        startActivity(launch)
        crossfadeOut()
        moveTaskToBack(true)
    }

    private fun launchChrome() {
        val chrome = packageManager.getLaunchIntentForPackage("com.android.chrome")
        val intent = chrome ?: Intent(Intent.ACTION_VIEW, Uri.parse("about:blank"))
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        Log.d(TAG, "rotation → handing off to ${if (chrome != null) "Chrome" else "system browser"}")
        try {
            startActivity(intent)
            crossfadeOut()
            moveTaskToBack(true)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "no browser available for Chrome slot; staying", e)
        }
    }

    private fun launchHome() {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        Log.d(TAG, "rotation → handing off to Android home (USB-top slot)")
        startActivity(home)
        crossfadeOut()
        moveTaskToBack(true)
    }

    @Suppress("DEPRECATION")
    private fun crossfadeOut() {
        // ~200ms crossfade across the singleTask handoff — replaces the older
        // (0,0) instant cut for a smoother rotation experience. See
        // SkippyDroid.MainActivity.crossfadeOut for full rationale.
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private companion object {
        const val TAG = "Local.Skippy.Chat"
        const val SKIPPY_DROID_PKG = "local.skippy.droid"

        // Must agree with SkippyDroid's MainActivity. Captain's intent:
        // USB-right = HUD, USB-left = Chrome. Empirical observation on the
        // SkippyS23 AVD via `adb emu rotate` locks the rotation values to
        // these constants; flip them if real-hardware behavior diverges.
        const val SLOT_HUD_ROTATION = android.view.Surface.ROTATION_90
        const val SLOT_CHROME_ROTATION = android.view.Surface.ROTATION_270
    }
}
