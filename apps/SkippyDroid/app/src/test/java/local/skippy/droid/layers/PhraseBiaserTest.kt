package local.skippy.droid.layers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table-driven tests for [PhraseBiaser.fuzzyMatch].
 *
 * In production the dispatcher runs its exact-substring scan BEFORE
 * invoking the biaser (CommandDispatcher.kt:181-196), so the biaser only
 * ever sees transcripts where no phrase exact-matches. Every test input
 * below is shaped to that contract — a realistic mis-transcription where
 * the correct trigger phrase has at least one character off.
 *
 * Phrase list below mirrors the PRIMARY trigger of each registered
 * intent. Secondary/fallback phrases like "navigate" (bare) are omitted
 * because in production the substring scan catches them first; including
 * them here would only force [AMBIGUITY_MARGIN] ties that the biaser
 * correctly refuses to guess through.
 */
class PhraseBiaserTest {

    /** Primary trigger phrases — one per real intent, no prefix-overlaps. */
    private val phrases = listOf(
        "navigate to",
        "cancel",
        "stop navigation",
        "cancel navigation",
        "teleprompt faster",
        "teleprompt slower",
        "teleprompt pause",
        "teleprompt resume",
        "teleprompt close",
        "teleprompt load",
        "explain",
        "what is",
        "tell me about",
    )

    // ── Corrections that should succeed ──────────────────────────────────

    @Test fun nabigateGetsFixed() {
        // "navigate" mis-transcribed with v→b; 1 sub, under budget.
        val m = PhraseBiaser.fuzzyMatch("nabigate to johnny's market", phrases)
        assertNotNull("expected a match for 'nabigate to ...'", m)
        assertEquals("navigate to", m!!.correctedPhrase)
    }

    @Test fun canSellBecomesCancel() {
        // space-insert mid-word — classic STT split.
        val m = PhraseBiaser.fuzzyMatch("can sell", phrases)
        assertNotNull(m)
        assertEquals("cancel", m!!.correctedPhrase)
    }

    @Test fun stoppNavigation() {
        val m = PhraseBiaser.fuzzyMatch("stopp navigation", phrases)
        assertNotNull(m)
        assertEquals("stop navigation", m!!.correctedPhrase)
    }

    @Test fun telepromFasterGetsFixed() {
        // Missing "pt" chunk — 2 inserts, well under the 3-edit budget
        // for a 17-char phrase. ("tell a prompt faster" is a 4-edit
        // transformation — over budget; that case would require a wider
        // EDIT_RATIO or a second-pass LM, neither in Phase 1.)
        val m = PhraseBiaser.fuzzyMatch("teleprom faster", phrases)
        assertNotNull("expected teleprompt faster correction", m)
        assertEquals("teleprompt faster", m!!.correctedPhrase)
    }

    // ── Cases where the biaser should NOT fire ──────────────────────────

    @Test fun tooShortInputReturnsNull() {
        assertNull(PhraseBiaser.fuzzyMatch("go", phrases))
    }

    @Test fun emptyStringReturnsNull() {
        assertNull(PhraseBiaser.fuzzyMatch("", phrases))
        assertNull(PhraseBiaser.fuzzyMatch("   ", phrases))
    }

    @Test fun emptyPhraseListReturnsNull() {
        assertNull(PhraseBiaser.fuzzyMatch("navigate to anywhere", emptyList()))
    }

    @Test fun wildlyUnrelatedInputReturnsNull() {
        // NB: this input deliberately avoids any short-word substring
        // that could legitimately fuzz to a verb. "whats the weather..."
        // is NOT a good unrelated test — "whats" legitimately biases to
        // "what is" at d=2 (insert ' i'), which is the feature working:
        // Captain said "what is the weather..." and STT dropped chars.
        val m = PhraseBiaser.fuzzyMatch(
            "i want some ice cream please",
            phrases
        )
        assertNull("expected no correction, got $m", m)
    }

    // ── Semantics: correction span is local, not whole-string ────────────

    @Test fun spanCoversOnlyTheFuzzyRegion() {
        // Mis-transcription at the front; args ("johnny's market") must
        // NOT be inside the span the biaser rewrites.
        val raw = "nabigate to johnny's market"
        val m = PhraseBiaser.fuzzyMatch(raw, phrases)
        assertNotNull(m)
        assertTrue(
            "span must stop before the args; got ${m!!.originalSpan}",
            m.originalSpan.last < raw.indexOf("johnny")
        )
    }

    @Test fun rewriteYieldsDispatchableString() {
        val raw = "nabigate to the gas station"
        val m = PhraseBiaser.fuzzyMatch(raw, phrases)
        assertNotNull(m)
        val rewritten = raw.replaceRange(m!!.originalSpan, m.correctedPhrase)
        assertTrue(
            "rewritten='$rewritten' should contain '${m.correctedPhrase}'",
            rewritten.contains(m.correctedPhrase)
        )
        val idx = rewritten.indexOf(m.correctedPhrase)
        val args = rewritten.substring(idx + m.correctedPhrase.length).trim()
        assertEquals("the gas station", args)
    }

    // ── Counters increment sensibly ──────────────────────────────────────

    @Test fun countersReflectOutcomes() {
        val before = PhraseBiaser.counters.copy()
        PhraseBiaser.fuzzyMatch("nabigate to test", phrases)   // hit
        PhraseBiaser.fuzzyMatch("zzzzzzzzzzzz", phrases)       // miss
        val after = PhraseBiaser.counters
        assertTrue("hits should increase", after.hits > before.hits)
        assertTrue("misses should increase", after.misses > before.misses)
    }
}
