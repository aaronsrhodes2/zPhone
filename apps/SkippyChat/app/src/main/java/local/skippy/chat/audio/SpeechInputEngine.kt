package local.skippy.chat.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * Continuous speech-to-text engine for SkippyChat.
 *
 * Adapted from SkippyDroid's VoiceEngine — same continuous-loop pattern,
 * same main-thread requirement. Key differences:
 *
 *   1. No [CommandDispatcher]. Finalized transcripts go to [onTranscript]
 *      callback, which ChatScreen wires to its draft + keyword scanner.
 *
 *   2. [isMuted] toggle. Volume Down key in MainActivity flips this.
 *      When muted: recognizer is paused but [running] stays true so
 *      unmuting immediately resumes without recreating the engine.
 *      Visual: [rmsLevel] drops to 0, [isListening] becomes false.
 *
 * Must be constructed and called on the main thread (SpeechRecognizer
 * requirement). MainActivity calls [start]/[stop] in onStart/onStop.
 */
class SpeechInputEngine(private val context: Context) {

    companion object {
        private const val TAG = "Local.Skippy.STT"
        private const val RESTART_DELAY_MS  = 120L
        private const val ERROR_BACKOFF_MS  = 400L
        private const val RMS_DB_FLOOR      = -2f
        private const val RMS_DB_CEIL       = 10f
    }

    // ── Observable state (Compose-readable) ──────────────────────────────

    var isListening by mutableStateOf(false)
        private set

    /** Live partial transcript — updates word by word while speaking. */
    var partialTranscript by mutableStateOf("")
        private set

    /** Normalized mic amplitude [0,1] — drives the mic dot pulse in the UI. */
    var rmsLevel by mutableStateOf(0f)
        private set

    /**
     * BCP-47 language tag for recognition. Default "en-US".
     * Change to "es-ES" to activate the installed Spanish SODA pack.
     *
     * Setting this mid-session cancels the current utterance and schedules
     * an immediate restart with the new language in the RecognizerIntent.
     * The SpeechRecognizer itself is NOT recreated — language is per-intent,
     * not per-recognizer instance in the Google stack.
     *
     * Must be called on the main thread (same requirement as start/stop).
     *
     * Same backing-field trick as [isMuted] — `by mutableStateOf` and a
     * non-default setter cannot coexist in Kotlin/Compose.
     */
    private val _targetLanguage = mutableStateOf("en-US")
    var targetLanguage: String
        get() = _targetLanguage.value
        set(value) {
            if (_targetLanguage.value == value) return
            _targetLanguage.value = value
            Log.d(TAG, "targetLanguage → $value")
            if (running && !isMuted) {
                recognizer?.cancel()
                sessionActive = false
                scheduleRestart(0)
            }
        }

    /**
     * Mute toggle — mapped to Volume Down key in MainActivity.
     * Setting to true pauses the recognizer; false resumes it.
     *
     * Can't use `by mutableStateOf` + custom setter together in Kotlin/Compose,
     * so we hold a private MutableState and expose get/set manually.
     */
    private val _isMuted = mutableStateOf(false)
    var isMuted: Boolean
        get() = _isMuted.value
        set(value) {
            _isMuted.value = value
            if (value) {
                // Pause: cancel the current session but stay in "running" state.
                recognizer?.cancel()
                sessionActive = false
                partialTranscript = ""
                rmsLevel = 0f
                isListening = false
                Log.d(TAG, "muted")
            } else {
                // Resume: kick off a new session.
                if (running) {
                    isListening = true
                    scheduleRestart(0)
                }
                Log.d(TAG, "unmuted")
            }
        }

    // ── Transcript callback ───────────────────────────────────────────────

    /**
     * Called on the main thread with each finalized utterance.
     * ChatScreen registers this in a [androidx.compose.runtime.DisposableEffect]
     * to append the transcript to the current draft.
     */
    var onTranscript: ((String) -> Unit)? = null

    // ── Private ───────────────────────────────────────────────────────────

    private val main         = Handler(Looper.getMainLooper())
    // Lazy so getSystemService isn't called before Activity.onCreate().
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var recognizer    : SpeechRecognizer? = null
    private var running       = false
    private var sessionActive = false
    /** Volume saved before muting; restored in onReadyForSpeech. */
    private var savedNotificationVol = -1
    private var savedRingVol         = -1
    /**
     * Consecutive LANGUAGE_PACK_ERROR (code 13 from SODA) count.
     * After 3 strikes we force the explicit Google cloud recognizer —
     * isOnDeviceRecognitionAvailable() lies on emulators where the SODA
     * pack hasn't finished downloading.
     */
    private var packErrors    = 0
    private var forceCloud    = false

    // ── Lifecycle ─────────────────────────────────────────────────────────

    fun start() {
        if (running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "start(): RECORD_AUDIO not granted")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(TAG, "start(): SpeechRecognizer unavailable")
            return
        }
        running = true
        if (!isMuted) {
            isListening = true
            createRecognizer()
            beginSession()
        }
        Log.d(TAG, "SpeechInputEngine started (muted=$isMuted)")
    }

    fun stop() {
        running = false
        isListening = false
        sessionActive = false
        main.removeCallbacksAndMessages(null)
        recognizer?.let {
            try { it.cancel()  } catch (_: Throwable) {}
            try { it.destroy() } catch (_: Throwable) {}
        }
        recognizer = null
        partialTranscript = ""
        rmsLevel = 0f
        Log.d(TAG, "SpeechInputEngine stopped")
    }

    // ── Recognition session ───────────────────────────────────────────────

    private fun createRecognizer() {
        // Only use the on-device recognizer if the language pack is actually
        // ready. On emulators the pack is often missing (LANGUAGE_PACK_ERROR),
        // which causes a tight restart loop. isOnDeviceRecognitionAvailable()
        // returns false when the pack isn't downloaded — fall through to the
        // cloud recognizer in that case. On a real S23 with the pack installed
        // this path stays true and we keep local inference.
        val useOnDevice = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        recognizer = if (useOnDevice) {
            Log.d(TAG, "createRecognizer: on-device")
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                ?: SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            Log.d(TAG, "createRecognizer: cloud (on-device unavailable)")
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer?.setRecognitionListener(listener)
    }

    private fun beginSession() {
        if (!running || sessionActive || isMuted) return
        val r = recognizer ?: run { createRecognizer(); recognizer } ?: return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        sessionActive = true
        // Silence the SpeechRecognizer start-beep. The beep fires on
        // STREAM_NOTIFICATION / STREAM_RING before onReadyForSpeech.
        // We restore the saved volumes there. Requires MODIFY_AUDIO_SETTINGS.
        try {
            savedNotificationVol = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            savedRingVol         = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_RING,         0, 0)
        } catch (e: Exception) {
            Log.w(TAG, "beep suppression failed: ${e.message}")
        }
        try {
            r.startListening(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "startListening threw: ${t.message}")
            sessionActive = false
            restoreBeepVolumes()
            scheduleRestart(ERROR_BACKOFF_MS)
        }
    }

    private fun restoreBeepVolumes() {
        try {
            if (savedNotificationVol >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, savedNotificationVol, 0)
                savedNotificationVol = -1
            }
            if (savedRingVol >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_RING, savedRingVol, 0)
                savedRingVol = -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "restoreBeepVolumes failed: ${e.message}")
        }
    }

    private fun scheduleRestart(delayMs: Long = RESTART_DELAY_MS) {
        if (!running || isMuted) return
        main.postDelayed({
            if (!running || isMuted) return@postDelayed
            sessionActive = false
            beginSession()
        }, delayMs)
    }

    // ── RecognitionListener ───────────────────────────────────────────────

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            // Beep window is past — restore volumes now.
            restoreBeepVolumes()
            partialTranscript = ""
        }
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            rmsLevel = ((rmsdB - RMS_DB_FLOOR) / (RMS_DB_CEIL - RMS_DB_FLOOR))
                .coerceIn(0f, 1f)
        }

        override fun onEndOfSpeech() { rmsLevel = 0f }

        override fun onError(error: Int) {
            val quiet = error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == 13 /* ERROR_SERVER_DISCONNECTED — no Google account / offline */
            val msg = errorName(error)
            if (quiet) Log.v(TAG, "onError: $msg") else Log.w(TAG, "onError: $msg")

            restoreBeepVolumes()   // ensure volumes never stay stuck muted
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                running = false; isListening = false; return
            }
            partialTranscript = ""
            scheduleRestart(if (quiet) RESTART_DELAY_MS else ERROR_BACKOFF_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialTranscript = firstResult(partialResults) ?: return
        }

        override fun onResults(results: Bundle?) {
            val text = firstResult(results) ?: ""
            partialTranscript = ""
            if (text.isNotBlank()) {
                Log.d(TAG, "heard: $text")
                onTranscript?.invoke(text)
            }
            scheduleRestart()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO                   -> "AUDIO"
        SpeechRecognizer.ERROR_CLIENT                  -> "CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK                 -> "NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT         -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH                -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY         -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER                  -> "SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT          -> "SPEECH_TIMEOUT"
        13                                             -> "SERVER_DISCONNECTED"
        14                                             -> "TOO_MANY_REQUESTS"
        else                                            -> "UNKNOWN($code)"
    }
}
