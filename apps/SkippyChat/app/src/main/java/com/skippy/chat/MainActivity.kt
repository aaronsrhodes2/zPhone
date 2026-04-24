package com.skippy.chat

import android.os.Bundle
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
 * Orientation is pinned to portrait in the manifest so rotation
 * can't destroy the activity anyway.
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
}
