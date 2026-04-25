package local.skippy.music.model

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Owns the ExoPlayer instance and the /music/session API calls.
 *
 * Session lifecycle:
 *   1. Activity calls [startSession] (new session) or [connectToExisting]
 *      (attach to whatever is already playing on SkippyTel).
 *   2. [loadAndPlay] feeds the stream URL into ExoPlayer.
 *   3. When ExoPlayer hits STATE_ENDED, [advance] is called automatically —
 *      SkippyTel picks the next DJ track and we switch streams.
 *   4. Controls: [togglePlayPause], [skip], [stop].
 *
 * ExoPlayer is released in [onCleared]. The ViewModel survives screen rotation.
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

    private val _session = MutableStateFlow<MusicSession?>(null)
    val session: StateFlow<MusicSession?> = _session.asStateFlow()

    private val _playerState = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    sealed class PlayerState {
        object Idle      : PlayerState()
        object Buffering : PlayerState()
        object Playing   : PlayerState()
        object Paused    : PlayerState()
        object Ended     : PlayerState()
    }

    // ── ExoPlayer ─────────────────────────────────────────────────────────

    val player: ExoPlayer = ExoPlayer.Builder(app).build().also { p ->
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> _playerState.value = PlayerState.Buffering
                    Player.STATE_READY     -> _playerState.value =
                        if (p.isPlaying) PlayerState.Playing else PlayerState.Paused
                    Player.STATE_ENDED     -> {
                        _playerState.value = PlayerState.Ended
                        viewModelScope.launch { advance() }
                    }
                    Player.STATE_IDLE      -> _playerState.value = PlayerState.Idle
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Only update if we're not in a transient state
                if (_playerState.value != PlayerState.Buffering &&
                    _playerState.value != PlayerState.Ended) {
                    _playerState.value =
                        if (isPlaying) PlayerState.Playing else PlayerState.Paused
                }
            }
        })
    }

    init {
        startPolling()
    }

    // ── Session entry points ──────────────────────────────────────────────

    /**
     * POST /music/session to start a new session, then begin playback.
     *
     * [mode]   — "dj" | "playlist" | "single"
     * [prompt] — text description for playlist mode ("90s hip hop")
     * [artist] — artist for single mode
     * [title]  — track title for single mode
     */
    fun startSession(
        mode: String   = "dj",
        prompt: String = "",
        artist: String = "",
        title: String  = "",
    ) {
        _error.value = null
        viewModelScope.launch {
            val sess = withContext(Dispatchers.IO) {
                postSession(buildBody(mode, prompt, artist, title))
            }
            if (sess != null) {
                _session.value = sess
                sess.nowPlaying?.let { loadAndPlay(it.streamUrl) }
            } else {
                _error.value = "Couldn't start session — is SkippyTel running?"
            }
        }
    }

    /**
     * Connect to the existing session on SkippyTel (if any).
     * If nothing is playing, auto-starts a DJ session.
     */
    fun connectToExisting() {
        _error.value = null
        viewModelScope.launch {
            val sess = withContext(Dispatchers.IO) { getSession() }
            if (sess != null && sess.status == "playing" && sess.nowPlaying != null) {
                _session.value = sess
                loadAndPlay(sess.nowPlaying.streamUrl)
            } else {
                // Nothing running — start the DJ
                startSession("dj")
            }
        }
    }

    // ── Controls ──────────────────────────────────────────────────────────

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
            viewModelScope.launch {
                withContext(Dispatchers.IO) { controlSession("pause") }
            }
        } else {
            player.play()
            viewModelScope.launch {
                withContext(Dispatchers.IO) { controlSession("resume") }
            }
        }
    }

    fun skip() {
        viewModelScope.launch {
            val sess = withContext(Dispatchers.IO) { skipSession() }
            if (sess?.nowPlaying != null) {
                _session.value = sess
                loadAndPlay(sess.nowPlaying.streamUrl)
            }
        }
    }

    fun stop() {
        player.stop()
        _playerState.value = PlayerState.Idle
        viewModelScope.launch {
            withContext(Dispatchers.IO) { controlSession("stop") }
            _session.value = null
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun loadAndPlay(streamUrl: String) {
        // Prepend base URL if the path is relative (normal case from SkippyTel)
        val fullUrl = if (streamUrl.startsWith("http")) streamUrl
                      else "$skippyTelUrl$streamUrl"
        Log.i(TAG, "Loading stream: $fullUrl")
        player.setMediaItem(MediaItem.fromUri(fullUrl))
        player.prepare()
        player.play()
    }

    private suspend fun advance() {
        Log.i(TAG, "Track ended — calling /music/session/advance")
        val sess = withContext(Dispatchers.IO) { advanceSession() }
        if (sess?.nowPlaying != null) {
            _session.value = sess
            loadAndPlay(sess.nowPlaying.streamUrl)
        } else {
            Log.i(TAG, "advance returned no next track — stopping")
            _playerState.value = PlayerState.Idle
            _session.value = sess
        }
    }

    /**
     * Poll GET /music/session every 5 s to keep metadata fresh.
     * Does NOT re-trigger playback — just updates the UI state.
     */
    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                delay(5_000L)
                val sess = withContext(Dispatchers.IO) { getSession() }
                if (sess != null) _session.value = sess
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

    private fun advanceSession(): MusicSession? = try {
        val req = Request.Builder()
            .url("$skippyTelUrl/music/session/advance")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else parseSession(resp.body?.string())
        }
    } catch (e: Exception) { Log.e(TAG, "advance: $e"); null }

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
            // SkippyTel wraps responses in {"spec_version":"1","data":{...}}
            val data = root.optJSONObject("data") ?: root
            val np   = data.optJSONObject("now_playing")
            MusicSession(
                sessionId      = data.optString("session_id").takeIf { it.isNotBlank() },
                mode           = data.optString("mode").takeIf { it.isNotBlank() },
                status         = data.optString("status", "stopped"),
                nowPlaying     = if (np != null) NowPlaying(
                    artist    = np.optString("artist", "Unknown"),
                    title     = np.optString("title",  "Unknown"),
                    album     = np.optString("album",  ""),
                    fileId    = np.optString("file_id", ""),
                    streamUrl = np.optString("stream_url", ""),
                ) else null,
                queueRemaining = data.optInt("queue_remaining", 0),
                source         = data.optString("source", "local"),
            )
        } catch (e: Exception) { Log.e(TAG, "parseSession: $e"); null }
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
