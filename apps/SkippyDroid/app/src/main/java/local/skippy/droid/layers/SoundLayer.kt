package local.skippy.droid.layers

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Layer 4b — SoundLayer.
 *
 * Per-ear non-speech audio primitive. Renders short synthesized chimes
 * (confirm / error / notify) into a 16-bit PCM [AudioTrack] so the Captain
 * gets audible feedback without invoking the TTS pipeline or a media asset.
 *
 * ── Doctrine ──────────────────────────────────────────────────────────────
 *   - **Per-ear** — every emit specifies [Ear.LEFT], [Ear.RIGHT], or
 *     [Ear.BOTH]. Future-facing the per-ear sound notification library
 *     Captain sketched in the Pattern A/C feature pass: left ear = "self"
 *     events (your actions acked), right ear = "world" events (PC replies,
 *     incoming messages). Phase 1 only distinguishes confirm/error.
 *   - **Short** — every chime ≤ 200 ms. Longer signals go through TTS
 *     (speech) or through a future NotificationModule (visible cue).
 *   - **No files** — synthesized in-process with sine waves so the APK
 *     doesn't grow with every new tone. Cheap to re-tune; any palette
 *     re-hue is a literal change.
 *   - **Non-blocking** — [play] returns immediately; the AudioTrack is
 *     written on [scope]. If the Captain fires two chimes in quick
 *     succession the second may briefly overlap the first — that's fine
 *     on a HUD-grade feedback layer (worst case: two ticks audible).
 *
 * ── Usage ─────────────────────────────────────────────────────────────────
 * ```
 * val sound = SoundLayer()
 * sound.start()
 * ...
 * sound.confirm()    // voice command matched
 * sound.error()      // voice command unrecognized
 * sound.notify()     // notification arrived
 * ...
 * sound.stop()       // tears down coroutine scope
 * ```
 *
 * Wire into MainActivity lifecycle (onCreate → start, onDestroy → stop).
 */
class SoundLayer {

    /** Target ear for a chime. */
    enum class Ear { LEFT, RIGHT, BOTH }

    /**
     * A short chime spec. Rendered as a sine wave of [freqHz] for
     * [durationMs] with a linear attack/decay envelope to avoid clicks.
     */
    data class Chime(
        val freqHz: Double,
        val durationMs: Int,
        val ear: Ear = Ear.BOTH,
        val amplitude: Float = 0.35f,    // ≤ 0.5 keeps headroom below clipping
    )

    // ── Presets (doctrine — tune here, not per-caller) ─────────────────────

    companion object {
        private const val TAG = "Local.Skippy.Sound"
        private const val SAMPLE_RATE = 44_100

        /** Voice-matched / dispatcher-acked: rising two-tone, both ears. */
        val CHIME_CONFIRM = listOf(
            Chime(freqHz = 660.0, durationMs = 70, ear = Ear.BOTH),
            Chime(freqHz = 880.0, durationMs = 90, ear = Ear.BOTH),
        )

        /** Voice-unmatched / error: single low beep, left ear (self-error). */
        val CHIME_ERROR = listOf(
            Chime(freqHz = 220.0, durationMs = 130, ear = Ear.LEFT),
        )

        /** Notification arrived from outside world: single high tick, right ear. */
        val CHIME_NOTIFY = listOf(
            Chime(freqHz = 1320.0, durationMs = 60, ear = Ear.RIGHT),
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running = false

    // ── Lifecycle ─────────────────────────────────────────────────────────

    fun start() {
        running = true
    }

    fun stop() {
        running = false
        scope.cancel()
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Fire the "command acknowledged" chime (rising two-tone, both ears). */
    fun confirm() = play(CHIME_CONFIRM)

    /** Fire the "command unrecognized" chime (low beep, left ear). */
    fun error() = play(CHIME_ERROR)

    /** Fire the "something arrived" chime (high tick, right ear). */
    fun notify() = play(CHIME_NOTIFY)

    /**
     * Play an arbitrary sequence of [chimes] back-to-back. Returns immediately;
     * the job runs on [scope]. Safe to call from any thread.
     */
    fun play(chimes: List<Chime>): Job = scope.launch {
        if (!running) return@launch
        try {
            emitSequence(chimes)
        } catch (t: Throwable) {
            Log.w(TAG, "play threw: ${t.message}")
        }
    }

    // ── Synthesis ─────────────────────────────────────────────────────────

    private fun emitSequence(chimes: List<Chime>) {
        val track = buildTrack() ?: return
        try {
            track.play()
            for (c in chimes) track.writeChime(c)
        } finally {
            try { track.stop() } catch (_: Throwable) {}
            try { track.release() } catch (_: Throwable) {}
        }
    }

    private fun buildTrack(): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null

        return try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf.coerceAtLeast(8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioTrack build failed: ${t.message}")
            @Suppress("DEPRECATION")
            try {
                AudioTrack(
                    AudioManager.STREAM_NOTIFICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf.coerceAtLeast(8192),
                    AudioTrack.MODE_STREAM,
                )
            } catch (t2: Throwable) {
                Log.w(TAG, "AudioTrack fallback failed: ${t2.message}")
                null
            }
        }
    }

    private fun AudioTrack.writeChime(c: Chime) {
        val totalSamples = (SAMPLE_RATE * c.durationMs / 1000).coerceAtLeast(32)
        // Stereo: interleaved L/R, 16-bit signed.
        val buf = ShortArray(totalSamples * 2)
        val twoPiF = 2.0 * PI * c.freqHz / SAMPLE_RATE
        // Linear attack/decay envelope — 12% attack, 20% release, sustain between.
        val attack = (totalSamples * 0.12).toInt().coerceAtLeast(1)
        val release = (totalSamples * 0.20).toInt().coerceAtLeast(1)

        val leftGain  = if (c.ear == Ear.RIGHT) 0f else 1f
        val rightGain = if (c.ear == Ear.LEFT) 0f else 1f

        for (i in 0 until totalSamples) {
            val env: Float = when {
                i < attack                  -> i.toFloat() / attack
                i >= totalSamples - release -> (totalSamples - i).toFloat() / release
                else                         -> 1f
            }
            val s = sin(twoPiF * i).toFloat() * c.amplitude * env
            // Clamp to int16 range to be safe.
            val sL = (s * leftGain  * Short.MAX_VALUE).toInt().coerceIn(-32_767, 32_767).toShort()
            val sR = (s * rightGain * Short.MAX_VALUE).toInt().coerceIn(-32_767, 32_767).toShort()
            buf[i * 2]     = sL
            buf[i * 2 + 1] = sR
        }

        // Write in chunks so we don't overflow the AudioTrack internal buffer.
        var written = 0
        while (written < buf.size) {
            val chunk = min(buf.size - written, 4096)
            val n = write(buf, written, chunk)
            if (n <= 0) break
            written += n
        }
    }
}
