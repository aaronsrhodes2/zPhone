package local.skippy.chat.model

/**
 * Input mode for the SkippyChat text entry surface.
 *
 * NORMAL   — 5-second idle timer auto-sends. Each typed pause is an implicit
 *            "done for now". No deliberate send action needed.
 *
 * PLANNING — Accumulate-then-ship. The timer is suspended; text grows across
 *            multiple paragraphs until the Captain says "ship it". Mirrors
 *            the Claude Desktop extended-thinking scratchpad, but for wearable
 *            use where hands are occupied and the Captain thinks out loud.
 *
 * Transitions are keyword-driven (see [KeywordScanner]). The ViewModel owns
 * this state so it survives recomposition.
 */
enum class InputMode {
    /**
     * Vibe mode — chill, hands-free. The 5-second idle timer fires ONLY
     * after the user has actually spoken or typed something (draft not blank).
     * Silence with nothing to say = nothing happens.
     */
    VIBE,

    /**
     * Planning mode — accumulate thoughts across paragraphs without
     * auto-sending. "Ship it" sends the whole plan at once.
     */
    PLANNING,
}
