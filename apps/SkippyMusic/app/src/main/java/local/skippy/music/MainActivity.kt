package local.skippy.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import local.skippy.music.model.MusicViewModel
import local.skippy.music.ui.MusicScreen

/**
 * Entry point for SkippyMusic.
 *
 * Launch modes:
 *   1. From launcher (no extras) — connects to any running session or starts DJ.
 *   2. From SkippyChat / SkippyDroid (extras):
 *        mode          — "dj" | "playlist" | "single"
 *        prompt        — playlist description (playlist mode)
 *        artist        — artist name (single mode)
 *        title         — track title  (single mode)
 *        trigger_phrase— raw voice phrase that triggered the launch (for logs)
 *
 * launchMode="singleTop" means if the app is already running and SkippyChat
 * fires another "let bilby dj" command, onNewIntent() handles it instead of
 * creating a second instance.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MusicViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only start / connect on fresh create — not on rotation
        if (savedInstanceState == null) {
            handleLaunchIntent(intent)
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF000000),
                    surface    = Color(0xFF1A1A2E),
                ),
            ) {
                MusicScreen(
                    viewModel = viewModel,
                    onBack    = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // App already in foreground — start new session from the fresh intent
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent) {
        val mode    = intent.getStringExtra("mode")           ?: ""
        val prompt  = intent.getStringExtra("prompt")         ?: ""
        val artist  = intent.getStringExtra("artist")         ?: ""
        val title   = intent.getStringExtra("title")          ?: ""
        val trigger = intent.getStringExtra("trigger_phrase") ?: ""

        // Infer mode from voice trigger when no explicit mode extra was provided
        val resolvedMode = when {
            mode.isNotBlank()                       -> mode
            "playlist" in trigger                   -> "playlist"
            "play some" in trigger                  -> "playlist"
            "play music" in trigger                 -> "playlist"
            artist.isNotBlank() || title.isNotBlank() -> "single"
            else                                    -> "dj"
        }

        if (resolvedMode != "dj" || prompt.isNotBlank() || artist.isNotBlank() || title.isNotBlank()) {
            // Explicit request — start the specified session
            viewModel.startSession(resolvedMode, prompt, artist, title)
        } else {
            // Default: attach to running session or auto-start DJ
            viewModel.connectToExisting()
        }
    }
}
