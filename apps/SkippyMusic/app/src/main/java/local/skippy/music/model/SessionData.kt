package local.skippy.music.model

/**
 * Mirror of SkippyTel's /music/session now_playing object.
 * stream_url is a relative path like "/music/stream/Artist/Album/Track.mp3".
 */
data class NowPlaying(
    val artist: String,
    val title: String,
    val album: String,
    val fileId: String,
    val streamUrl: String,
)

/**
 * Mirror of SkippyTel's /music/session response (unwrapped from envelope).
 *
 * [mode]           — "dj" | "playlist" | "single"
 * [status]         — "playing" | "paused" | "stopped"
 * [queueRemaining] — tracks left in playlist queue (0 in DJ mode)
 * [source]         — "local" (corrected_music) | "drive"
 */
data class MusicSession(
    val sessionId: String?,
    val mode: String?,
    val status: String,
    val nowPlaying: NowPlaying?,
    val queueRemaining: Int,
    val source: String,
)
