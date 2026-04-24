package com.skippy.chat.compositor

import androidx.compose.ui.graphics.Color

/**
 * SkippyChat palette — HEX-for-HEX copy of SkippyDroid's [HudPalette].
 *
 * Why a fresh file instead of importing?
 * Cross-app imports are forbidden by the Skippy monorepo quarantine
 * doctrine (Sessions 7 / 7b). The cost of duplicating an object of
 * eight color literals is near zero; the benefit is that SkippyChat
 * and SkippyDroid stay build-independent and can be versioned
 * separately without coupling. If the canonical palette ever shifts,
 * both files get updated in lockstep — grep for the HEX values.
 *
 * Semantics are unchanged from [com.skippy.droid.compositor.HudPalette].
 * SkippyChat uses a narrower subset in practice:
 *   Black       — canvas (status bar, scrollback background)
 *   White       — user messages (Captain's own words)
 *   Green       — assistant messages (Skippy's replies, "OK" accent)
 *   Cyan        — reachability pill when ONLINE
 *   Amber       — offline banner + reachability pill when UNKNOWN
 *   Red         — send-failed banner + reachability pill when OFFLINE
 *   Violet      — tier="cloud" marker next to assistant replies
 *   DimGreen    — idle borders, placeholder text
 */
object ChatPalette {
    /** Pure black — scrollback background. */
    val Black      = Color(0xFF000000)

    /** Primary accent, assistant replies, "OK" state. */
    val Green      = Color(0xFF00FF00)

    /** User messages, Captain's own words. */
    val White      = Color(0xFFFFFFFF)

    /** Numeric metrics (latency ms in dev sheet). */
    val Amber      = Color(0xFFFFCC00)

    /** Reachability pill — ONLINE. */
    val Cyan       = Color(0xFF00CCFF)

    /** Cloud-tier marker on assistant replies. */
    val Violet     = Color(0xFF818CF8)

    /** Send failed, offline. */
    val Red        = Color(0xFFFF3344)

    /** Idle borders, placeholder text. */
    val DimGreen   = Color(0xFF003300)

    /** Lighter idle border variant. */
    val DimGreenHi = Color(0xFF005500)
}
