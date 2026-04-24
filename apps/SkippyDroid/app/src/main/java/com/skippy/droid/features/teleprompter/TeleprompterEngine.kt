package com.skippy.droid.features.teleprompter

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * TeleprompterEngine — state for a slow-scrolling text window the Captain
 * reads through the glasses while hands are busy (driving comms, stage
 * recital, code walkthrough dictation).
 *
 * ── Voice-controlled ──────────────────────────────────────────────────────
 * Only input source that changes state is voice through
 * [com.skippy.droid.layers.CommandDispatcher]:
 *   - "prompt <...>" / "teleprompt <...>" — load new text and start scrolling
 *   - "prompt faster"                     — bump wpm up
 *   - "prompt slower"                     — bump wpm down
 *   - "prompt pause" / "pause prompt"     — freeze at current position
 *   - "prompt resume" / "resume prompt"   — continue scrolling
 *   - "prompt close" / "close prompt"     — clear and hide the module
 *
 * Text is set externally (future: pushed from Mac via Tailscale; Phase 1
 * only ingests through voice).
 *
 * ── Progress model ────────────────────────────────────────────────────────
 * `progressWords` is a float advancing at [wpm] / 60 words per second while
 * running. The module reads it and windows the text accordingly. When
 * `progressWords >= totalWords` the prompter auto-pauses at the end.
 */
class TeleprompterEngine {

    /** Current script. Empty when the prompter is hidden. */
    var text: String by mutableStateOf("")
        private set

    /** Words-per-minute scroll rate. Sensible default for glance reading. */
    var wpm: Int by mutableStateOf(DEFAULT_WPM)
        private set

    /** Fractional word index into [text]. Used by the module to window glyphs. */
    var progressWords: Float by mutableStateOf(0f)
        private set

    /** True while scrolling; pause/resume flip this. */
    var running: Boolean by mutableStateOf(false)
        private set

    /** True while any text is loaded (i.e. the module should render). */
    val visible: Boolean get() = text.isNotEmpty()

    private val totalWords: Int get() =
        if (text.isEmpty()) 0 else text.trim().split(Regex("\\s+")).size

    private val scope = CoroutineScope(Dispatchers.Main)
    private var job: Job? = null

    companion object {
        const val DEFAULT_WPM = 160
        const val MIN_WPM     = 40
        const val MAX_WPM     = 400
        const val WPM_STEP    = 25

        /** Scroll tick interval. 60 ms keeps state updates cheap; advance is
         *  computed from wpm × tickSeconds so any tick rate renders smoothly. */
        private const val TICK_MS = 60L
    }

    // ── Intent handlers (wired via CommandDispatcher) ─────────────────────

    /** Load new text and auto-start scrolling from the top. */
    fun load(newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) {
            close()
            return
        }
        text = trimmed
        progressWords = 0f
        wpm = DEFAULT_WPM
        resume()
    }

    /** Bump wpm up one step. No-op at ceiling. */
    fun faster() {
        wpm = (wpm + WPM_STEP).coerceAtMost(MAX_WPM)
    }

    /** Bump wpm down one step. No-op at floor. */
    fun slower() {
        wpm = (wpm - WPM_STEP).coerceAtLeast(MIN_WPM)
    }

    /** Freeze at current position. */
    fun pause() {
        running = false
        job?.cancel()
        job = null
    }

    /** Resume from current position. Auto-called by [load]. */
    fun resume() {
        if (text.isEmpty()) return
        if (running) return
        running = true
        job = scope.launch {
            val tickSeconds = TICK_MS / 1000f
            while (isActive && running) {
                val delta = wpm / 60f * tickSeconds
                progressWords = (progressWords + delta).coerceAtMost(totalWords.toFloat())
                if (progressWords >= totalWords) {
                    running = false
                    break
                }
                delay(TICK_MS)
            }
        }
    }

    /** Clear text and hide the module. */
    fun close() {
        text = ""
        progressWords = 0f
        running = false
        job?.cancel()
        job = null
    }
}
