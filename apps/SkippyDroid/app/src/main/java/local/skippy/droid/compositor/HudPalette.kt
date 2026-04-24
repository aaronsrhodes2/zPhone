package local.skippy.droid.compositor

import androidx.compose.ui.graphics.Color

/**
 * Canonical HUD color palette — the ONLY source of color for every Skippy
 * surface (phone mirror, glasses display, mounted passthrough apps).
 *
 * Why an object?
 * Additive-light displays (VITURE Luma Ultra) turn black into transparency;
 * only saturated, high-luma colors dominate the eye. Ad-hoc [Color.Green],
 * [Color.Cyan], etc. from the Compose stdlib are close but not tuned for
 * the glasses — slightly off-hex hues wash out or bleed against passthrough.
 * Every module references [HudPalette] so we have a single choke-point for
 * future calibration sweeps.
 *
 * Usage rule (Session 7 doctrine):
 * No module is allowed to hand-roll a [Color] literal. No `Color.Green`,
 * no `Color(0xFF00AAFF)` sprinkled in a feature file. If a use case
 * doesn't fit the palette, extend THIS file — don't smuggle a new hue
 * in sideways.
 *
 * Semantics (what each hue means):
 *   Black      - Background / transparency on additive displays. No gradients.
 *   Green      - Primary accent, selection, OK, "self" marker.
 *   White      - Primary content text (titles, Captain's own words).
 *   Amber      - Numeric metrics (speed, BPM, battery percent, time).
 *   Cyan       - Identifiers (clock, device IDs, hashes, coordinates).
 *   Violet     - Special class tag (instrumental, alt mode, context flag).
 *   Red        - Alert, listening, live — pulsing allowed.
 *   DimGreen   - Borders, placeholders, idle outlines (non-interactive chrome).
 *
 * Hard rule: no text ever sits on a colored fill. Colored backgrounds dilute
 * the passthrough and ruin the additive-light read. Compose-on-black only.
 */
object HudPalette {
    /** Pure black — reads as transparent on the glasses' additive display. */
    val Black      = Color(0xFF000000)

    /** Primary accent, selection, OK, "self" marker. */
    val Green      = Color(0xFF00FF00)

    /** Primary content text (titles, Captain's own words). */
    val White      = Color(0xFFFFFFFF)

    /** Numeric metrics — speed, BPM, battery percent, time. */
    val Amber      = Color(0xFFFFCC00)

    /** Identifiers — clock, device IDs, hashes, coordinates. */
    val Cyan       = Color(0xFF00CCFF)

    /** Special class tag — instrumental, alt mode, context flag. */
    val Violet     = Color(0xFF818CF8)

    /** Alert, listening, live — pulsing allowed. */
    val Red        = Color(0xFFFF3344)

    /** Borders, placeholders, idle outlines. Darker end of the range. */
    val DimGreen   = Color(0xFF003300)

    /** Borders, placeholders, idle outlines. Lighter end of the range. */
    val DimGreenHi = Color(0xFF005500)

    /**
     * Chrome-only — paints the letterbox bars around the 16:10 glasses canvas
     * on the phone mirror. The S23's ~2.17:1 aspect is wider than the
     * glasses' 1.6:1; without this the HUD would stretch horizontally and
     * no longer match what the Captain sees through the lenses.
     *
     * **Never** appears inside the canvas. Not a content color. Exists as a
     * palette entry purely so we retain the "all color through HudPalette"
     * choke-point — content stays on Compose-on-black, chrome gets its own
     * named slot.
     */
    val MirrorLetterbox = Color(0xFF0044AA)
}
