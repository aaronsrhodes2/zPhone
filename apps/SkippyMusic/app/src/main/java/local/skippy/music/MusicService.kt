package local.skippy.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Foreground MediaSessionService that keeps ExoPlayer alive when the app is
 * backgrounded or the Activity is finished.
 *
 * Responsibilities:
 *   - Own the ExoPlayer instance
 *   - Expose a MediaSession so MusicViewModel's MediaController can drive it
 *   - Auto-advance to the next track (POST /music/session/advance) when
 *     the current track ends — this happens even when the app is closed
 *   - Show a persistent playback notification (handled by media3 automatically)
 *
 * All SkippyTel session management OTHER than advance (startSession, skip,
 * seekToStart, previousTrack) is done by MusicViewModel via MediaController
 * commands, which are forwarded here to ExoPlayer transparently.
 */
class MusicService : MediaSessionService() {

    companion object {
        private const val TAG = "MusicService"
        const val NOTIFICATION_CHANNEL_ID = "skippy_music_playback"
    }

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    // Background scope for advance() HTTP call — survives Activity lifecycle
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val skippyTelUrl: String by lazy {
        getSharedPreferences("skippy_prefs", Context.MODE_PRIVATE)
            .getString("skippytel_url", "http://10.0.2.2:3003") ?: "http://10.0.2.2:3003"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)   // pause on headphone unplug
            .build()
            .also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            Log.i(TAG, "Track ended — advancing session")
                            scope.launch { advance() }
                        }
                    }
                })
            }

        mediaSession = MediaSession.Builder(this, player).build()
        Log.i(TAG, "Service created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        mediaSession.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ── Auto-advance ──────────────────────────────────────────────────────

    /**
     * Called when ExoPlayer fires STATE_ENDED. Fetches the next track from
     * SkippyTel and loads it into ExoPlayer — even if the Activity is gone.
     */
    private suspend fun advance() {
        try {
            val req = Request.Builder()
                .url("$skippyTelUrl/music/session/advance")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "advance → ${resp.code}"); return
                }
                val root = JSONObject(resp.body?.string() ?: return)
                val data = root.optJSONObject("data") ?: root
                val np   = data.optJSONObject("now_playing") ?: run {
                    Log.i(TAG, "advance: no next track, session stopped"); return
                }
                val streamUrl = np.optString("stream_url", "").takeIf { it.isNotBlank() } ?: return
                val fullUrl   = if (streamUrl.startsWith("http")) streamUrl else "$skippyTelUrl$streamUrl"

                val mediaItem = MediaItem.Builder()
                    .setUri(fullUrl)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setArtist(np.optString("artist", "Unknown"))
                            .setTitle(np.optString("title",  "Unknown"))
                            .setAlbumTitle(np.optString("album", ""))
                            .build()
                    )
                    .build()

                withContext(Dispatchers.Main) {
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.play()
                    Log.i(TAG, "advance → playing: ${np.optString("artist")} — ${np.optString("title")}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "advance failed: $e")
        }
    }

    // ── Notification channel ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Skippy Music",
                    NotificationManager.IMPORTANCE_LOW,   // silent — no sound/vibration
                ).apply { description = "Now-playing notification for Bilby DJ" }
            )
        }
    }
}
