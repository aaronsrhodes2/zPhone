package com.skippy.droid.layers

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Layer 4+ — CommandDispatcher.
 *
 * The single choke-point through which every input source (voice, future
 * keyboard, future POST `/api/input-text`, future PC MCP callback) is
 * routed. Modules register intents with phrase triggers; the dispatcher
 * matches incoming fuzzy text to an intent and invokes its handler with
 * whatever text followed the trigger.
 *
 * ── Design doctrine ───────────────────────────────────────────────────────
 * Fuzzy text at the edge, typed intent at the core. The raw `String` that
 * enters [dispatch] gets *classified* (match an [Intent.id]) and the
 * downstream handler receives structured-enough input (an args string,
 * optionally typed tier) to call typed domain code like
 * `NavigationEngine.navigateTo(dest: String, mode: TravelMode)`.
 *
 * ── What modules register ─────────────────────────────────────────────────
 *   NavigationModule  : "navigate to *" → onNavigate(dest)
 *                       "cancel" / "stop navigation" / "never mind" → onCancel
 *   (future) Battery  : "battery status" → onSpeak(current percentages)
 *   (future) Clock    : "what time is it" → onSpeak(current time)
 *
 * ── Reserved intents ──────────────────────────────────────────────────────
 *   `help` / `inventory` / `what can i do` — dispatcher-owned, not
 *   registrable by modules. Invokes [onInventoryRequest] with the live
 *   intent list so the UI/TTS layer can enumerate current verbs.
 *
 * ── Source-agnostic by design ─────────────────────────────────────────────
 * [dispatch] accepts a [Source] tag so downstream layers can treat an API
 * request differently from a voice utterance if they need to (e.g. skip
 * TTS for API-sourced intents). Phase 1 doesn't differentiate.
 *
 * ── Future tier routing (sketched, not built here) ────────────────────────
 * When the PC MCP ships, the dispatcher will also recognize *routing
 * modifier* prefixes like "put on your thinking cap" and strip them from
 * the text, tagging the outbound request with a tier field. That logic
 * lives in a future `routeTier()` helper that wraps [dispatch].
 */
class CommandDispatcher {

    /** Where the text came from. Phase 1 only differentiates for logging. */
    enum class Source { VOICE, KEYBOARD, API, MCP_CALLBACK }

    /**
     * A registered command — trigger phrases plus the typed handler.
     *
     * @property id          Stable identifier (e.g. `"navigate"`). Unique.
     * @property phrases     Lowercase substrings; any match fires the intent.
     *                       The first phrase's position in the text determines
     *                       where the args substring starts (for verbs like
     *                       "navigate to X" where X is everything after).
     * @property description Human-readable one-liner for `help` output.
     * @property handler     Called with the residual text after the trigger
     *                       phrase is stripped, trimmed. Empty string is fine.
     */
    data class Intent(
        val id: String,
        val phrases: List<String>,
        val description: String,
        val handler: (args: String) -> Unit,
    )

    /** Outcome of a single [dispatch] call. */
    data class DispatchResult(
        val matched: Intent?,
        /** Residual text passed to the handler. Empty if nothing matched. */
        val args: String,
        /** True iff the match was the reserved help/inventory intent. */
        val wasInventory: Boolean = false,
    )

    /** Phrases that trigger the reserved `inventory` intent. */
    private val inventoryPhrases = listOf(
        "help", "inventory", "what can i do", "what can you do",
        "list commands", "what commands"
    )

    /** Registered module intents. Not thread-safe — register on main thread. */
    private val intents = mutableListOf<Intent>()

    /**
     * Last dispatched match (observable) — useful for the chat pill's
     * "heard: X → navigate" echo. Null until the first dispatch.
     */
    var lastMatched: Intent? by mutableStateOf(null)
        private set

    /**
     * Fires when the reserved `help` / `inventory` verb is recognized.
     * Wired by [com.skippy.droid.MainActivity] to TTS the live verb list
     * and/or display it in a corner.
     */
    var onInventoryRequest: ((List<Intent>) -> Unit)? = null

    /**
     * Fires when [dispatch] finds no match for the input. Useful for:
     *   - Logging (see what verbs the Captain is trying that aren't wired)
     *   - Future: escalating to PC MCP for natural-language handling
     */
    var onUnmatched: ((text: String, source: Source) -> Unit)? = null

    // ── Registration ──────────────────────────────────────────────────────

    /**
     * Register a new intent. Throws [IllegalArgumentException] if the id
     * collides with an existing one or the phrases shadow a reserved verb.
     */
    fun register(intent: Intent) {
        require(intents.none { it.id == intent.id }) {
            "Intent id '${intent.id}' already registered"
        }
        val reservedHits = intent.phrases.filter { p ->
            inventoryPhrases.any { r -> p.contains(r, ignoreCase = true) }
        }
        require(reservedHits.isEmpty()) {
            "Intent '${intent.id}' shadows reserved phrase(s) $reservedHits"
        }
        intents += intent
        Log.d(TAG, "registered intent: ${intent.id} (${intent.phrases.size} phrases)")
    }

    /** Convenience overload for the common case. */
    fun register(
        id: String,
        phrases: List<String>,
        description: String,
        handler: (args: String) -> Unit,
    ) = register(Intent(id, phrases, description, handler))

    /** Remove a previously-registered intent by id. No-op if not present. */
    fun unregister(id: String) {
        intents.removeAll { it.id == id }
    }

    /**
     * Current live intent list — used by `help` to tell the Captain what
     * verbs are available right now (which depends on which modules are
     * enabled + in active mode).
     */
    fun inventory(): List<Intent> = intents.toList()

    // ── Dispatch ──────────────────────────────────────────────────────────

    /**
     * Classify [rawText] and fire the matching intent's handler.
     *
     * Matching strategy (cheap on purpose — the edge is supposed to be
     * fuzzy; smarter matching belongs in the PC MCP tier):
     *   1. Lowercase + trim the input.
     *   2. Check reserved inventory phrases first (prevents a module from
     *      accidentally stealing `help`).
     *   3. Scan registered intents in registration order; first phrase
     *      substring match wins. Args = everything after the trigger phrase.
     *   4. No match → [onUnmatched] fires (logged, optionally escalated).
     *
     * Returns a [DispatchResult] so callers can log / echo. The handler
     * has already been invoked by the time this returns.
     */
    fun dispatch(rawText: String, source: Source = Source.VOICE): DispatchResult {
        val text = rawText.lowercase().trim()
        if (text.isEmpty()) return DispatchResult(null, "")

        // 1. Reserved inventory verbs — dispatcher owns these.
        for (phrase in inventoryPhrases) {
            if (text.contains(phrase)) {
                Log.d(TAG, "[$source] inventory request: '$text'")
                val live = inventory()
                onInventoryRequest?.invoke(live)
                lastMatched = null
                return DispatchResult(null, "", wasInventory = true)
            }
        }

        // 2. Registered module intents.
        for (intent in intents) {
            for (phrase in intent.phrases) {
                val idx = text.indexOf(phrase)
                if (idx < 0) continue
                val args = text.substring(idx + phrase.length).trim()
                Log.d(TAG, "[$source] matched '${intent.id}' via '$phrase' args='$args'")
                lastMatched = intent
                try {
                    intent.handler(args)
                } catch (t: Throwable) {
                    Log.w(TAG, "handler for '${intent.id}' threw: ${t.message}")
                }
                return DispatchResult(intent, args)
            }
        }

        // 2b. Fuzzy-biased retry against live inventory.
        //
        // The stock Android free-form recognizer routinely mangles
        // close-but-wrong versions of our keywords. PhraseBiaser slides
        // an edit-distance window over the transcript, rewrites the
        // offending span to the registered phrase, and re-runs the
        // substring scan — so args-extraction (line 186) stays identical
        // to a clean transcript. Only fires when exactly one phrase wins;
        // ambiguous inputs fall through to onUnmatched unchanged.
        val biased = PhraseBiaser.fuzzyMatch(text, intents.flatMap { it.phrases })
        if (biased != null) {
            val rewritten = text.replaceRange(biased.originalSpan, biased.correctedPhrase)
            Log.i(
                TAG,
                "[$source] PhraseBiaser: '$text' → '$rewritten' " +
                "(phrase='${biased.correctedPhrase}' d=${biased.distance})"
            )
            for (intent in intents) {
                for (phrase in intent.phrases) {
                    val idx = rewritten.indexOf(phrase)
                    if (idx < 0) continue
                    val args = rewritten.substring(idx + phrase.length).trim()
                    lastMatched = intent
                    try {
                        intent.handler(args)
                    } catch (t: Throwable) {
                        Log.w(TAG, "handler for '${intent.id}' threw: ${t.message}")
                    }
                    return DispatchResult(intent, args)
                }
            }
            // Biaser returned a match but post-rewrite substring scan
            // didn't find it — shouldn't happen if inventory() is stable
            // across the call. Log and fall through to onUnmatched.
            Log.w(TAG, "[$source] PhraseBiaser rewrite '$rewritten' didn't re-match; falling through")
        }

        // 3. No match — fire unmatched callback for logging / future MCP escalation.
        Log.v(TAG, "[$source] no match: '$text'")
        onUnmatched?.invoke(text, source)
        return DispatchResult(null, "")
    }

    companion object {
        private const val TAG = "Skippy.Dispatcher"
    }
}
