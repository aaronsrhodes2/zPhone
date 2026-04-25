package local.skippy.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.skippy.music.model.MusicViewModel

// ── Skippy palette ────────────────────────────────────────────────────────────
private val Black   = Color(0xFF000000)
private val Pink    = Color(0xFFFF44AA)
private val DimPink = Color(0xFF330022)
private val White   = Color(0xFFFFFFFF)
private val Gray    = Color(0xFF888888)
private val Dark    = Color(0xFF1A1A2E)

@Composable
fun MusicScreen(
    viewModel: MusicViewModel,
    onBack: () -> Unit,
) {
    val session     by viewModel.session.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val error       by viewModel.error.collectAsState()

    // Double-tap state for the ⏮ back button.
    // First tap → seekToStart(); second tap within 5 s → previousTrack().
    var lastBackTapMs by remember { mutableLongStateOf(0L) }

    val isPlaying   = playerState == MusicViewModel.PlayerState.Playing
    val isBuffering = playerState == MusicViewModel.PlayerState.Buffering

    val nowPlaying  = session?.nowPlaying
    val mode        = session?.mode ?: "dj"
    val source      = session?.source ?: "local"
    val queueLeft   = session?.queueRemaining ?: 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {

        // ── Header ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "SKIPPY MUSIC",
                color = Pink,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
            )
            Text(
                text = when (mode) {
                    "dj"       -> "DJ MODE"
                    "playlist" -> "PLAYLIST  ·  $queueLeft left"
                    "single"   -> "SINGLE → DJ"
                    else       -> mode.uppercase()
                },
                color = Gray,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
            )
        }

        // ── Now Playing block ─────────────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (nowPlaying != null) {
                // Artist
                Text(
                    text = nowPlaying.artist,
                    color = Gray,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                // Title — the hero element
                Text(
                    text = nowPlaying.title,
                    color = White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (nowPlaying.album.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = nowPlaying.album,
                        color = Gray,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                // Nothing playing yet — show status
                Text(
                    text = when {
                        error != null  -> error!!
                        isBuffering    -> "Connecting…"
                        else           -> "Starting Bilby DJ…"
                    },
                    color = if (error != null) Color(0xFFFF4444) else Gray,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Buffering progress bar
            if (isBuffering) {
                Spacer(Modifier.height(20.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(0.55f),
                    color = Pink,
                    trackColor = DimPink,
                )
            }
        }

        // ── Source badge ──────────────────────────────────────────────────
        Text(
            text = "♫  $source",
            color = Gray.copy(alpha = 0.4f),
            fontSize = 10.sp,
            letterSpacing = 3.sp,
        )

        // ── Transport controls ────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⏮ Back button — single tap restarts current track,
            //   double-tap within 5 s loads the previous track.
            ControlButton(label = "⏮", sublabel = "BACK", tint = Gray, size = 56.dp) {
                val now = System.currentTimeMillis()
                if (now - lastBackTapMs < 5_000L) {
                    // Second tap within 5 s → previous track
                    viewModel.previousTrack()
                    lastBackTapMs = 0L          // reset so a third tap restarts again
                } else {
                    // First tap → restart current track from the top
                    viewModel.seekToStart()
                    lastBackTapMs = now
                }
            }
            // Play / Pause — prominent center button
            ControlButton(
                label    = if (isPlaying) "⏸" else "▶",
                sublabel = if (isPlaying) "PAUSE" else "PLAY",
                tint     = Pink,
                size     = 72.dp,
            ) {
                viewModel.togglePlayPause()
            }
            // Skip
            ControlButton(label = "⏭", sublabel = "SKIP", tint = Gray, size = 56.dp) {
                viewModel.skip()
            }
        }
    }
}

@Composable
private fun ControlButton(
    label: String,
    sublabel: String,
    tint: Color,
    size: Dp = 56.dp,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(size),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Dark,
                contentColor   = tint,
            ),
        ) {
            Text(
                text     = label,
                fontSize = (size.value * 0.38f).sp,
                color    = tint,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text          = sublabel,
            color         = Gray,
            fontSize      = 9.sp,
            letterSpacing = 1.5.sp,
        )
    }
}
