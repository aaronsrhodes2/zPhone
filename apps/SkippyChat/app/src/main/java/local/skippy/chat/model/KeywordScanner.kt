package local.skippy.chat.model

/**
 * Detects command keywords in the Captain's draft text and returns the
 * action to take plus the cleaned draft (keyword stripped out).
 *
 * Detection is case-insensitive substring match. The keyword is removed
 * from the draft before the action fires — the sent message never contains
 * the command word.
 *
 * Priority order (highest first):
 *   CLEAR_ALL > DROP_PARAGRAPH > SEND_NOW > MODE_CHANGE
 *
 * "never mind" and "wait wait" drop only the last paragraph — useful in
 * PLANNING mode where a bad idea was just spoken but the rest of the plan
 * is still good. "cancel that" / "scratch that" / "forget it" nuke the
 * entire draft (nuclear option).
 *
 * Phase 2 note: Whisper transcripts arrive as plain lowercase text with no
 * punctuation, so all keywords are written without apostrophes / commas.
 * "i'm listening" and "eyes open" live in SkippyDroid's VoiceEngine and
 * are NOT duplicated here; SkippyChat only needs chat-specific controls.
 */
object KeywordScanner {

    sealed class Result {
        /** No keyword found — use draft as-is. */
        object None : Result()

        /** Nuke the entire draft, return to NORMAL mode. */
        object ClearAll : Result()

        /**
         * Drop the last paragraph (everything after the final `\n\n`).
         * If there is only one paragraph, clears the draft entirely.
         * Mode is unchanged.
         */
        data class DropLastParagraph(val cleanedDraft: String) : Result()

        /** Send the cleaned draft immediately (skip timer). */
        data class SendNow(val cleanedDraft: String) : Result()

        /** Switch input mode, keep the cleaned draft. */
        data class SwitchMode(val mode: InputMode, val cleanedDraft: String) : Result()

        /**
         * Switch recognizer to Spanish (es-ES). Chat bubbles will show the
         * translated-to-English text, not the raw Spanish transcript.
         */
        object EnterSpanish : Result()

        /** Switch recognizer back to English (en-US). */
        object ExitSpanish : Result()

        /**
         * Dispatch an SMS to [recipient] with [body].
         *
         * Triggered by voice patterns like:
         *   "send a text to Randall Bronte, I'll be there at seven"
         *   "text mom, love you"
         *
         * The comma is required as the recipient/body delimiter so the
         * contact name can contain spaces. [recipient] and [body] are
         * already stripped of the trigger phrase.
         */
        data class SendSms(val recipient: String, val body: String) : Result()

        /**
         * Query Bilby (the music service) for what's currently playing.
         * Fires against GET /bilby/status and posts the result as a
         * SYSTEM bubble in the feed.
         */
        object BilbyNowPlaying : Result()

        /**
         * Tell Bilby to skip to the next track.
         * Fires against POST /bilby/next.
         */
        object BilbyNext : Result()

        /**
         * A dynamic voice trigger from the SkippyTel service manifest matched.
         *
         * [serviceId]      — the service id (e.g. "dropship", "bilby")
         * [triggerPhrase]  — the exact phrase that matched (for logging)
         *
         * The handler should:
         *   1. Look up the service in the manifest (via ViewModel).
         *   2. If [ServiceManifest.companionApp] is set, launch that package.
         *   3. Otherwise fall through to a system bubble confirming the action.
         */
        data class ServiceIntent(val serviceId: String, val triggerPhrase: String) : Result()

        /**
         * Raise media volume by one voice step via [AudioRouter].
         * No-op when no external audio output is connected (phone speaker locked).
         */
        object VolumeUp : Result()

        /**
         * Lower media volume by one voice step via [AudioRouter].
         * No-op when no external audio output is connected.
         */
        object VolumeDown : Result()
    }

    // ── Dynamic triggers ──────────────────────────────────────────────────

    /**
     * Voice trigger phrase → service id, loaded from SkippyTel `GET /services`.
     * Updated by [ChatViewModel.loadServices] whenever the manifest refreshes.
     *
     * Phrases are already sorted longest-first by the ViewModel so that more
     * specific phrases (e.g. "open star map") can't be shadowed by shorter ones
     * ("open star") if both were registered.
     *
     * Excludes `"*"` catch-all entries — those are handled by the AI tier.
     */
    // @Volatile ensures that writes from the IO thread (loadServices coroutine) are
    // immediately visible to the main thread reading this in scan(). Without it, the
    // JVM is free to cache the value in a register and miss updates across threads.
    @Volatile
    var dynamicTriggers: Map<String, String> = emptyMap()

    // ── Keyword lists ─────────────────────────────────────────────────────
    // List longest phrases before shorter ones within each group so that
    // "let's make a plan" is consumed before "plan" could ever match
    // something shorter.

    /** Remove the whole draft. */
    private val CLEAR_ALL_KEYWORDS = listOf(
        "cancel that", "scratch that", "forget it",
    )

    /**
     * Remove only the last paragraph. "never mind" sits here rather than
     * in CLEAR_ALL so the rest of a multi-paragraph plan survives.
     * "wait wait" catches the natural stutter when the Captain changes
     * their mind mid-sentence.
     */
    private val DROP_PARAGRAPH_KEYWORDS = listOf(
        "never mind", "wait wait",
    )

    /** Send immediately — skip the 5-second NORMAL timer. */
    private val SEND_NOW_KEYWORDS = listOf(
        "send now", "send it", "go ahead",
        "ship it",          // works in PLANNING and NORMAL
    )

    private val ENTER_PLANNING_KEYWORDS = listOf(
        "let's make a plan", "lets make a plan",
        "let's plan it out", "lets plan it out",
        "planning mode", "solid gold",
    )

    private val EXIT_PLANNING_KEYWORDS = listOf(
        "done planning", "back to normal",
        "let's vibe", "lets vibe", "vibe mode",
    )

    /**
     * Activate Spanish recognition mode (targets es-ES SODA pack).
     * Variants cover what the English recognizer is likely to transcribe
     * when the Captain says the trigger phrase while still in English mode.
     */
    private val ENTER_SPANISH_KEYWORDS = listOf(
        "habla español",   // natural — "speak Spanish" in Spanish
        "español",         // single-word trigger
        "espanol",         // common no-accent spelling
        "spanish mode", "spanish please",
    )

    /**
     * Return to English recognition.
     * These phrases will be recognized even in Spanish mode because
     * Google's recognizer handles common English words across locales.
     */
    private val EXIT_SPANISH_KEYWORDS = listOf(
        "english only", "back to english", "english mode",
        "solo inglés", "solo ingles",   // "English only" in Spanish
    )

    /**
     * Volume control — handled before Bilby/SMS so natural phrases like
     * "volume up" never fall through to a send or service lookup.
     */
    private val VOLUME_UP_KEYWORDS = listOf(
        "volume up", "louder", "turn it up", "turn up the volume",
    )
    private val VOLUME_DOWN_KEYWORDS = listOf(
        "volume down", "quieter", "turn it down", "softer", "lower the volume",
    )

    /**
     * Bilby now-playing query. Very short, natural phrases.
     * "what's playing" is a common one-shot voice command that should
     * never accidentally trigger a send.
     */
    private val BILBY_NOW_PLAYING_KEYWORDS = listOf(
        "what's playing", "whats playing",
        "what is playing",
        "what's on", "whats on",
        "what song is this",
        "bilby what", "bilby status",
        "now playing",
    )

    /**
     * Bilby skip / next track.
     */
    private val BILBY_NEXT_KEYWORDS = listOf(
        "bilby next", "next track", "skip track", "skip song",
        "next song",
    )

    /**
     * SMS dispatch. Comma separates recipient from body so multi-word
     * contact names work ("send a text to Randall Bronte, see you at seven").
     *
     * Regex captures:
     *   group 1 — recipient (everything between trigger and the first comma)
     *   group 2 — body (everything after the comma)
     *
     * Pattern variants:
     *   "send a text to [name], [body]"
     *   "send text to [name], [body]"
     *   "send a message to [name], [body]"
     *   "send sms to [name], [body]"
     *   "text [name], [body]"
     */
    private val SMS_PATTERN = Regex(
        """^(?:send\s+(?:a\s+)?(?:text|sms|message)(?:\s+message)?\s+to\s+|text\s+)(.+?),\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Scan [draft] for command keywords given the current [mode].
     * Returns the appropriate [Result]; [Result.None] if nothing matched.
     */
    fun scan(draft: String, mode: InputMode): Result {
        val lower = draft.lowercase()

        // 0. Language-mode switches — highest priority so they're never
        //    accidentally swallowed by a CLEAR_ALL or SEND_NOW keyword.
        for (kw in EXIT_SPANISH_KEYWORDS) {
            if (lower.contains(kw)) return Result.ExitSpanish
        }
        for (kw in ENTER_SPANISH_KEYWORDS) {
            if (lower.contains(kw)) return Result.EnterSpanish
        }

        // 0.2. Volume controls — checked before everything else so "volume up"
        //      is never accidentally treated as a draft to be sent.
        for (kw in VOLUME_UP_KEYWORDS)   { if (lower.contains(kw)) return Result.VolumeUp   }
        for (kw in VOLUME_DOWN_KEYWORDS) { if (lower.contains(kw)) return Result.VolumeDown }

        // 0.3. Bilby music commands — before clear/send so they're never swallowed.
        for (kw in BILBY_NEXT_KEYWORDS) {
            if (lower.contains(kw)) return Result.BilbyNext
        }
        for (kw in BILBY_NOW_PLAYING_KEYWORDS) {
            if (lower.contains(kw)) return Result.BilbyNowPlaying
        }

        // 0.4. Dynamic service triggers (from SkippyTel /services manifest).
        // Checked AFTER Bilby (hardcoded Bilby keywords take precedence) but
        // BEFORE SMS so "open gallery" isn't accidentally nuked by a CLEAR_ALL.
        // Phrases are longest-first so more specific triggers shadow shorter ones.
        for ((phrase, serviceId) in dynamicTriggers) {
            if (lower.contains(phrase)) return Result.ServiceIntent(serviceId, phrase)
        }

        // 0.5. SMS dispatch — before clear so "send a text" isn't nuked.
        SMS_PATTERN.find(draft.trim())?.let { m ->
            val recipient = m.groupValues[1].trim()
            val body      = m.groupValues[2].trim()
            if (recipient.isNotEmpty() && body.isNotEmpty()) {
                return Result.SendSms(recipient = recipient, body = body)
            }
        }

        // 1. Nuclear clear
        for (kw in CLEAR_ALL_KEYWORDS) {
            if (lower.contains(kw)) return Result.ClearAll
        }

        // 2. Drop last paragraph
        for (kw in DROP_PARAGRAPH_KEYWORDS) {
            if (lower.contains(kw)) {
                return Result.DropLastParagraph(cleanedDraft = dropLastParagraph(draft, kw))
            }
        }

        // 3. Mode-exit
        for (kw in EXIT_PLANNING_KEYWORDS) {
            if (lower.contains(kw)) {
                return Result.SwitchMode(
                    mode = InputMode.VIBE,
                    cleanedDraft = draft.replace(kw, "", ignoreCase = true).trim(),
                )
            }
        }

        // 4. Mode-enter
        for (kw in ENTER_PLANNING_KEYWORDS) {
            if (lower.contains(kw)) {
                return Result.SwitchMode(
                    mode = InputMode.PLANNING,
                    cleanedDraft = draft.replace(kw, "", ignoreCase = true).trim(),
                )
            }
        }

        // 5. Send now (includes "ship it")
        for (kw in SEND_NOW_KEYWORDS) {
            if (lower.contains(kw)) {
                return Result.SendNow(
                    cleanedDraft = draft.replace(kw, "", ignoreCase = true).trim(),
                )
            }
        }

        return Result.None
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Remove the last paragraph from [draft] (where paragraphs are
     * separated by `\n\n`). Also strips the triggering [keyword] from
     * whatever paragraph remains at the end after the drop.
     *
     * Examples:
     *   "idea one\n\nidea two never mind" → "idea one"
     *   "only one paragraph never mind"   → ""  (clears fully)
     */
    private fun dropLastParagraph(draft: String, keyword: String): String {
        // Strip the keyword first so it doesn't confuse the paragraph split
        val stripped = draft.replace(keyword, "", ignoreCase = true).trim()
        val parts = stripped.split("\n\n")
        return if (parts.size <= 1) {
            ""  // Only one paragraph — treat as full clear
        } else {
            parts.dropLast(1).joinToString("\n\n").trim()
        }
    }
}
