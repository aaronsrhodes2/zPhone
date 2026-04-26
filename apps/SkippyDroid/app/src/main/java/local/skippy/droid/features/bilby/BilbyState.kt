package local.skippy.droid.features.bilby

/**
 * Domain model for the Bilby music module.
 *
 * Polled from two SkippyTel endpoints:
 *   GET /music/session    — session + now_playing (2 s interval)
 *   GET /bilby/karaoke    — karaoke line (500 ms interval, degrades to null)
 */

enum class PlayStatus { PLAYING, PAUSED, NONE }

/**
 * Current karaoke line state.
 *
 * [elapsedPct] is 0.0–1.0 fraction through the current LRC line.
 * Used to drive a thin progress bar under the lyric text.
 */
data class KaraokeState(
    val line: String,
    val nextLine: String?,
    val elapsedPct: Float,
)

/**
 * Full now-playing snapshot consumed by [BilbyModule].
 */
data class NowPlaying(
    val title: String,
    val artist: String,
    val bpmInt: Int?,           // null until NML metadata arrives
    val key: String?,           // null until NML metadata arrives
    val status: PlayStatus,
    val queueRemaining: Int = 0,
    val karaoke: KaraokeState? = null,
)
