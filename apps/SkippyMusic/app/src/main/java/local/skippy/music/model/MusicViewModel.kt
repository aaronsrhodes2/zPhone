package local.skippy.music.model

import android.app.Application
import android.content.ComponentName
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.skippy.music.MusicService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Drives MusicService via MediaController.
 *
 * ExoPlayer now lives in [MusicService] (a MediaSessionService) so playback
 * survives when the Activity is finished. This ViewModel connects to it via
 * [MediaController] and handles:
 *
 *   - Starting / connecting to a SkippyTel music session (HTTP)
 *   - Forwarding controls (play/pause, skip, seek, previous) to the controller
 *   - Polling GET /music/session every 5 s for metadata (mode, queue, source)
 *   - Tracking the previous track for ⏮ double-tap via [onMediaItemTransition]
 *
 * Auto-advance (track ended → fetch next) lives in [MusicService] so it works
 * even when this ViewModel is cleared.
 */
class MusicViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "MusicVM"
    }

    private val skippyTelUrl: String =
        app.getSharedPreferences("skippy_prefs", android.content.Context.MODE_PRIVATE)
            .getString("skippytel_url", "http://10.0.2.2:3003") ?: "http://10.0.2.2:3003"

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ── Exposed state ─────────────────────────────────────────────────────

    private val _session     = MutableStateFlow<MusicSession?>(null)
    val session: StateFlow<MusicSession?> = _session.asStateFlow()

    private val _playerState = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _error       = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    sealed class PlayerState {
        object Idle      : PlayerState()
        object Buffering : PlayerState()
        object Playing   : PlayerState()
        object Paused    : PlayerState()
        object Ended     : PlayerState()
    }

    // ── MediaController ───────────────────────────────────────────────────

    private var controller: MediaController? = null

    // Pending action queued before the controller finishes connecting
    private var pendingAction: (() -> Unit)? = null

    // Track the NowPlaying that was current before the last item transition —
    // used by ⏮ double-tap to go back one song.
    private var prevNowPlaying: NowPlaying? = null
    // The NowPlaying we last saw become current (updated on each transition)
    private var currentNowPlaying: NowPlaying? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            _playerState.value = when (state) {
                Player.STATE_BUFFERING -> PlayerState.Buffering
                Player.STATE_READY     -> if (controller?.isPlaying == true) PlayerState.Playing else PlayerState.Paused
                Player.STATE_ENDED     -> PlayerState.Ended
                else                   -> PlayerState.Idle
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (_playerState.value != PlayerState.Buffering && _playerState.value != PlayerState.Ended) {
                _playerState.value = if (isPlaying) PlayerState.Playing else PlayerState.Paused
            }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // currentNowPlaying was the track before this transition → it becomes prev
            prevNowPlaying    = currentNowPlaying
            currentNowPlaying = mediaItem?.toNowPlaying()
            // Update now-playing display from embedded metadata
            currentNowPlaying?.let { np ->
                _session.value = _session.value?.copy(nowPlaying = np)
            }
        }
    }

    init {
        connectController()
        startPolling()
    }

    private fun connectController() {
        val token = SessionToken(
            getApplication(),
            ComponentName(getApplication(), MusicService::class.java),
        )
        val future = MediaController.Builder(getApplication(), token).buildAsync()
        future.addListener({
            try {
                controller = future.get()
                controller?.addListener(playerListener)
                Log.i(TAG, "MediaController connected")
                pendingAction?.invoke()
                pendingAction = null
            } catch (e: Exception) {
                Log.e(TAG, "MediaController connect failed: $e")
                _error.value = "Couldn't connect to music service"
            }
        }, getApplication<Application>().mainExecutor)
    }

    // ── Session entry points ──────────────────────────────────────────────

    /**
     * POST /music/session to start a new session, then load the first track.
     * If the controller isn't ready yet, queues the action.
     */
    fun startSession(
        mode: String   = "dj",
        prompt: String = "",
        artist: String = "",
        title: String  = "",
    ) {
        _error.value = null
        val action: () -> Unit = {
            viewModelScope.launch {
                val sess = withContext(Dispatchers.IO) {
                    postSession(buildBody(mode, prompt, artist, title))
                }
                if (sess != null) {
                    _session.value = sess
                    sess.nowPlaying?.let { loadAndPlay(it) }
                } else {
                    _error.value = "Couldn't start session — is SkippyTel running?"
                }
            }
            Unit
        }
        if (controller != null) action() else pendingAction = action
    }

    /**
     * Attach to any active session on SkippyTel. If nothing is playing,
     * auto-starts a DJ session.
     */
    fun connectToExisting() {
        _error.value = null
        val action: () -> Unit = {
            viewModelScope.launch {
                val sess = withContext(Dispatchers.IO) { getSession() }
                if (sess != null && sess.status == "playing" && sess.nowPlaying != null) {
                    _session.value = sess
                    loadAndPlay(sess.nowPlaying)
                } else {
                    startSession("dj")
                }
            }
            Unit
        }
        if (controller != null) action() else pendingAction = action
    }

    // ── Controls ──────────────────────────────────────────────────────────

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
            viewModelScope.launch { withContext(Dispatchers.IO) { controlSession("pause") } }
        } else {
            c.play()
            viewModelScope.launch { withContext(Dispatchers.IO) { controlSession("resume") } }
        }
    }

    fun skip() {
        viewModelScope.launch {
            prevNowPlaying = currentNowPlaying   // save before switching
            val sess = withContext(Dispatchers.IO) { skipSession() }
            if (sess?.nowPlaying != null) {
                _session.value = sess
                loadAndPlay(sess.nowPlaying)
            }
        }
    }

    /** Single ⏮ tap — restart the current track from the top. */
    fun seekToStart() {
        controller?.seekTo(0)
        if (controller?.isPlaying == false) controller?.play()
    }

    /** Double ⏮ tap within 5 s — go back to the previous track. */
    fun previousTrack() {
        val prev = prevNowPlaying
        if (prev != null) {
            prevNowPlaying    = currentNowPlaying
            currentNowPlaying = prev
            _session.value    = _session.value?.copy(nowPlaying = prev)
            loadAndPlay(prev)
        } else {
            seekToStart()
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun loadAndPlay(np: NowPlaying) {
        val fullUrl = if (np.streamUrl.startsWith("http")) np.streamUrl
                      else "$skippyTelUrl${np.streamUrl}"
        Log.i(TAG, "loadAndPlay: $fullUrl")
        val mediaItem = MediaItem.Builder()
            .setUri(fullUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setArtist(np.artist)
                    .setTitle(np.title)
                    .setAlbumTitle(np.album)
                    .build()
            )
            .build()
        controller?.setMediaItem(mediaItem)
        controller?.prepare()
        controller?.play()
    }

    /** Poll GET /music/session every 5 s to keep mode/queue/source fresh. */
    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                delay(5_000L)
                val sess = withContext(Dispatchers.IO) { getSession() }
                if (sess != null) {
                    // Preserve the now-playing from MediaController metadata — it's more
                    // up-to-date than the polling response during a track transition.
                    _session.value = sess.copy(
                        nowPlaying = _session.value?.nowPlaying ?: sess.nowPlaying
                    )
                }
            }
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    private fun buildBody(mode: String, prompt: String, artist: String, title: String): String =
        JSONObject().apply {
            put("mode", mode)
            if (prompt.isNotBlank()) put("prompt", prompt)
            if (artist.isNotBlank()) put("artist", artist)
            if (title.isNotBlank())  put("title",  title)
        }.toString()

    private fun postSession(jsonBody: String): MusicSession? = try {
        val req = Request.Builder()
            .url("$skippyTelUrl/music/session")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { Log.w(TAG, "POST /music/session → ${resp.code}"); null }
            else parseSession(resp.body?.string())
        }
    } catch (e: Exception) { Log.e(TAG, "postSession: $e"); null }

    private fun getSession(): MusicSession? = try {
        val req = Request.Builder().url("$skippyTelUrl/music/session").get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else parseSession(resp.body?.string())
        }
    } catch (e: Exception) { null }

    private fun skipSession(): MusicSession? = try {
        val req = Request.Builder()
            .url("$skippyTelUrl/music/session/skip")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else parseSession(resp.body?.string())
        }
    } catch (e: Exception) { Log.e(TAG, "skip: $e"); null }

    private fun controlSession(action: String) = try {
        val req = Request.Builder()
            .url("$skippyTelUrl/music/session/control")
            .post(JSONObject().put("action", action).toString()
                .toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { /* fire and forget */ }
    } catch (e: Exception) { Log.w(TAG, "control($action): $e") }

    private fun parseSession(json: String?): MusicSession? {
        if (json == null) return null
        return try {
            val root = JSONObject(json)
            val data = root.optJSONObject("data") ?: root
            val np   = data.optJSONObject("now_playing")
            MusicSession(
                sessionId      = data.optString("session_id").takeIf { it.isNotBlank() },
                mode           = data.optString("mode").takeIf { it.isNotBlank() },
                status         = data.optString("status", "stopped"),
                nowPlaying     = np?.toNowPlaying(),
                queueRemaining = data.optInt("queue_remaining", 0),
                source         = data.optString("source", "local"),
            )
        } catch (e: Exception) { Log.e(TAG, "parseSession: $e"); null }
    }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        controller?.release()
        super.onCleared()
    }
}

// ── Extension helpers ─────────────────────────────────────────────────────────

private fun JSONObject.toNowPlaying() = NowPlaying(
    artist    = optString("artist", "Unknown"),
    title     = optString("title",  "Unknown"),
    album     = optString("album",  ""),
    fileId    = optString("file_id", ""),
    streamUrl = optString("stream_url", ""),
)

private fun MediaItem.toNowPlaying(): NowPlaying? {
    val meta = mediaMetadata
    val url  = localConfiguration?.uri?.toString() ?: return null
    return NowPlaying(
        artist    = meta.artist?.toString() ?: "Unknown",
        title     = meta.title?.toString()  ?: "Unknown",
        album     = meta.albumTitle?.toString() ?: "",
        fileId    = "",
        streamUrl = url,
    )
}
