package local.skippy.droid.layers

import kotlin.math.max
import kotlin.math.min

/**
 * Layer 4 — local STT phrase primer (heuristic / zero-dep).
 *
 * The stock Android [android.speech.SpeechRecognizer] transcriber we
 * drive from [VoiceEngine] runs `LANGUAGE_MODEL_FREE_FORM` with no
 * knowledge of Skippy's registered verbs. It routinely mangles
 * close-but-wrong versions of our keywords:
 *
 *   - "knavigate to johnny's market"     →   intent `navigate`
 *   - "can sell"                          →   intent `cancel`
 *   - "tell a prompt faster"              →   intent `teleprompt_faster`
 *
 * Each miss becomes a wasted `onUnmatched → /intent/unmatched` trip, or
 * (when SkippyTel is offline) a silent drop.
 *
 * [PhraseBiaser] is a pure-Kotlin correction pass that runs **after** the
 * dispatcher's exact substring scan fails and **before** [onUnmatched].
 * It slides a window the size of each registered phrase across the raw
 * transcript, scores each window with Levenshtein distance, and — if
 * exactly one phrase beats its edit budget with a clear margin over the
 * runner-up — returns the correction so the dispatcher can rewrite the
 * utterance and re-run its substring scan.
 *
 * ── What this is NOT ──────────────────────────────────────────────────
 *   - NOT a speech recognizer. It's a post-hoc text correction layer.
 *   - NOT a grammar-constrained decoder. Vosk would be that — see the
 *     Phase 2 seam in Session 11's plan entry. This is Phase 1.
 *   - NOT network-dependent. Works fully offline; the whole point is
 *     that local voice commands keep working when SkippyTel is down.
 *
 * ── Thresholds ────────────────────────────────────────────────────────
 * All tuning lives in [PhraseBiaserConfig]. No magic numbers in logic.
 * Phrases shorter than [PhraseBiaserConfig.MIN_PHRASE_LEN_FOR_FUZZY]
 * skip fuzzy entirely — we don't want "go" fuzzing to "so".
 *
 * ── Observability ─────────────────────────────────────────────────────
 * Corrections are logged INFO with before → after + distance. Ambiguous
 * misses are logged DEBUG. [counters] is a public snapshot that a future
 * HUD widget can read to display "STT correction rate" — no UI today.
 *
 * ── Thread safety ─────────────────────────────────────────────────────
 * [fuzzyMatch] is pure; [counters] increments are racy but only used for
 * rate display, never for logic. Call from the dispatcher's single
 * thread (main) in practice.
 */
object PhraseBiaser {

    private const val TAG = "Local.Skippy.PhraseBiaser"

    /**
     * Successful match describing what to rewrite.
     *
     * @property correctedPhrase The registered phrase, verbatim.
     * @property originalSpan    The slice of `raw` that should be replaced.
     * @property distance        Levenshtein distance for the winning window.
     * @property phraseLen       Length of [correctedPhrase] (for debug/logging).
     */
    data class Match(
        val correctedPhrase: String,
        val originalSpan: IntRange,
        val distance: Int,
        val phraseLen: Int,
    )

    /** Running hit/miss counters — in-memory, not persisted. */
    data class Counters(
        var hits: Long = 0L,
        var misses: Long = 0L,
        var ambiguous: Long = 0L,
    )

    /** Public snapshot for HUD display. Mutated by [fuzzyMatch]. */
    val counters = Counters()

    /**
     * Attempt to fuzzy-correct [raw] against [phrases].
     *
     * Returns null if:
     *   - [raw] is blank or no phrase clears its edit budget, OR
     *   - Two or more phrases tie within [PhraseBiaserConfig.AMBIGUITY_MARGIN]
     *     (we refuse to guess between "cancel" and "navigate cancel").
     *
     * Caller is responsible for the downstream rewrite + re-dispatch.
     */
    fun fuzzyMatch(raw: String, phrases: List<String>): Match? {
        val text = raw.lowercase().trim()
        if (text.isEmpty()) return null

        val candidates = phrases
            .map { it.lowercase() }
            .distinct()
            .filter { it.length >= PhraseBiaserConfig.MIN_PHRASE_LEN_FOR_FUZZY }

        if (candidates.isEmpty()) {
            counters.misses++
            return null
        }

        // Score every candidate against its best sliding window.
        data class Scored(val phrase: String, val distance: Int, val span: IntRange)
        val scored = candidates.mapNotNull { phrase ->
            val budget = max(
                PhraseBiaserConfig.MIN_ABSOLUTE_BUDGET,
                (phrase.length * PhraseBiaserConfig.EDIT_RATIO).toInt()
            )
            bestWindowMatch(text, phrase, budget)
                ?.let { (dist, range) -> Scored(phrase, dist, range) }
        }.sortedBy { it.distance }

        if (scored.isEmpty()) {
            counters.misses++
            return null
        }

        val best = scored[0]
        val second = scored.getOrNull(1)
        if (second != null &&
            (second.distance - best.distance) < PhraseBiaserConfig.AMBIGUITY_MARGIN
        ) {
            counters.ambiguous++
            return null
        }

        counters.hits++
        return Match(
            correctedPhrase = best.phrase,
            originalSpan = best.span,
            distance = best.distance,
            phraseLen = best.phrase.length,
        )
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /**
     * Find the best-scoring sliding window of [text] that matches [phrase]
     * within [budget] edits. Returns (distance, span) of the winner, or null
     * if no window makes the budget.
     *
     * Window sizes explored: [phrase.length - budget, phrase.length + budget].
     * At phrase.length ≤ 30 and budget ≤ 6 this is trivially fast.
     */
    private fun bestWindowMatch(
        text: String,
        phrase: String,
        budget: Int,
    ): Pair<Int, IntRange>? {
        val n = text.length
        val plen = phrase.length
        val minWin = max(1, plen - budget)
        val maxWin = min(n, plen + budget)

        var bestDist = Int.MAX_VALUE
        var bestRange: IntRange? = null

        for (winSize in minWin..maxWin) {
            var start = 0
            while (start + winSize <= n) {
                val window = text.substring(start, start + winSize)
                val d = levenshtein(window, phrase, budget)
                if (d in 0..budget && d < bestDist) {
                    bestDist = d
                    bestRange = start until start + winSize
                    if (d == 0) return bestDist to bestRange!!  // can't do better
                }
                start++
            }
        }
        return bestRange?.let { bestDist to it }
    }

    /**
     * Classic two-row Levenshtein. Returns -1 if the final distance
     * exceeds [budget]. No mid-row early-exit — the obvious `rowMin >
     * budget` short-circuit is unsound because the next row's
     * curr[j-1]+1 transitions can still descend (curr[0] = i resets).
     * Phrases are ≤ 30 chars so full DP is trivial.
     */
    private fun levenshtein(a: String, b: String, budget: Int): Int {
        val m = a.length
        val n = b.length
        if (kotlin.math.abs(m - n) > budget) return -1

        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        val d = prev[n]
        return if (d <= budget) d else -1
    }
}

/**
 * Tuning surface for [PhraseBiaser]. Kept separate so it can be adjusted
 * without touching algorithm code.
 *
 * ── Budget derivation ────────────────────────────────────────────────
 * For a phrase of length `L`, the edit budget is:
 *
 *     max(MIN_ABSOLUTE_BUDGET, floor(L * EDIT_RATIO))
 *
 * With defaults (MIN=2, ratio=0.20):
 *   - "cancel"        (6)   →  budget 2  ("can sell", "canzel", "canxel")
 *   - "navigate to"   (11)  →  budget 2  ("knavigate to")
 *   - "teleprompt faster" (17) → budget 3 ("tell a prompt faster")
 *
 * ── Ambiguity guard ──────────────────────────────────────────────────
 * If the runner-up phrase's distance is within [AMBIGUITY_MARGIN] of
 * the best, we refuse the correction. "navigate or cancel" shouldn't
 * silently resolve to either intent.
 */
object PhraseBiaserConfig {
    /** Phrases shorter than this are NOT fuzzy-matched. Exact-match only. */
    const val MIN_PHRASE_LEN_FOR_FUZZY = 4

    /** Floor on the edit budget. Prevents 3-char phrases from getting 0. */
    const val MIN_ABSOLUTE_BUDGET = 2

    /** Max allowed edit distance as a fraction of phrase length. */
    const val EDIT_RATIO = 0.20

    /** Best must beat second-best by at least this many edits. */
    const val AMBIGUITY_MARGIN = 1
}
