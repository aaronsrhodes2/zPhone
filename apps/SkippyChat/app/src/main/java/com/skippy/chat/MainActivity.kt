package com.skippy.chat

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.skippy.chat.compositor.ChatPalette
import com.skippy.chat.model.ChatViewModel
import com.skippy.chat.transport.SkippyTelClient

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
                com.skippy.chat.ui.ChatScreen(
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

    // ── Orientation handoff ───────────────────────────────────────────────────
    //
    // Mirror image of the logic in SkippyDroid's MainActivity. When the
    // Captain rotates the phone to landscape, SkippyChat is in the "wrong"
    // orientation — we launch SkippyDroid (singleTask, so its existing
    // instance is brought forward if warm) and `moveTaskToBack(true)` so
    // our scrollback + OkHttp client stay intact for when they rotate back.
    //
    // Checked on `onResume` (cold-launch-in-landscape, return-from-background)
    // and `onConfigurationChanged` (live rotation while visible).
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        maybeHandoff(newConfig.orientation)
    }

    override fun onResume() {
        super.onResume()
        maybeHandoff(resources.configuration.orientation)
    }

    private fun maybeHandoff(orientation: Int) {
        // SkippyChat is the portrait mode. Landscape is SkippyDroid's turn.
        if (orientation != Configuration.ORIENTATION_LANDSCAPE) return

        val launch = packageManager.getLaunchIntentForPackage(SKIPPY_DROID_PKG)
        if (launch == null) {
            Log.i("Skippy.Chat", "landscape but $SKIPPY_DROID_PKG not installed; staying in chat")
            return
        }
        Log.d("Skippy.Chat", "landscape detected → handing off to $SKIPPY_DROID_PKG")
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        startActivity(launch)
        // See SkippyDroid's MainActivity for the rationale on the
        // deprecation suppression — zero-duration no-op transition,
        // old API still works identically.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        moveTaskToBack(true)
    }

    private companion object {
        const val SKIPPY_DROID_PKG = "com.skippy.droid"
    }
}
