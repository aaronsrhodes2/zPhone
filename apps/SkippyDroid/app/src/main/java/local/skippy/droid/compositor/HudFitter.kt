package local.skippy.droid.compositor

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/**
 * HUD typography + fit helpers.
 *
 * Doctrine (April 21 2026 — Captain's lock):
 *
 *   - Every HUD surface fits 1920×1200 per eye AND the Captain's S23 (1080×2340 portrait).
 *   - No ellipsis (`…`) cutoff. Ever.
 *   - No scrollbars. Ever.
 *   - Sentence wrapping is fine.
 *   - Text size scales with the viewport so the same code produces a legible
 *     render on the glasses AND on the phone mirror without per-file tweaks.
 *
 * Two fitting primitives:
 *
 *   1. [hudSp] — fixed-glance chrome (clock, battery, speed, compass, coords).
 *      Fixed multiplier of a viewport-derived base size.
 *
 *   2. [SizeJustifiedText] — size-justified paragraphs. The font size is
 *      auto-fit to fill a rectangular slot end-to-end with no cutoff.
 *      Used for inbound speech, nav error, chat messages, voice echo.
 *
 * Modules never hard-code `sp` values.
 */

/** Shared HUD font — monospace keeps glyphs on a uniform grid for glance legibility. */
val hudFont: FontFamily = FontFamily.Monospace

/**
 * Viewport-scaled text size for fixed-glance chrome.
 *
 * Base is derived from the **shorter** screen dimension (width on phone
 * portrait, height on glasses landscape), so rotation or aspect change doesn't
 * explode the sizes. ~1.15% of the shorter side gives ~14 sp on 1200p glasses,
 * ~12 sp on a phone at 1080 dp, and stays legible at both.
 *
 * @param multiplier 0.8 for secondary text (battery %, accuracy),
 *                   1.0 for default,
 *                   1.3 for primary call-outs.
 */
@Composable
fun hudSp(multiplier: Float = 1f): TextUnit {
    val config  = LocalConfiguration.current
    val shorter = minOf(config.screenHeightDp, config.screenWidthDp)
    val base    = (shorter * 0.0115f).coerceIn(11f, 22f)
    return (base * multiplier).sp
}

/** Cardinal direction glyphs — 16-way compass rose. Never truncated with `…`. */
val compassGlyphs: List<String> = listOf(
    "N",  "NNE", "NE", "ENE",
    "E",  "ESE", "SE", "SSE",
    "S",  "SSW", "SW", "WSW",
    "W",  "WNW", "NW", "NNW",
)

/** Return the glyph for a heading in degrees (0° = N, 90° = E, 180° = S, 270° = W). */
fun cardinalGlyph(deg: Double): String =
    compassGlyphs[((deg + 11.25) / 22.5).toInt().mod(16)]

// ── Size-justified text ──────────────────────────────────────────────────────

/**
 * Renders [text] at the **exact font size** that makes the wrapped paragraph
 * fill the outer [BoxWithConstraints]'s rectangle end-to-end with no cutoff.
 *
 * How it works:
 *   - Uses [BoxWithConstraints] to read the parent's pixel budget.
 *   - Binary-searches `sp` values between [minSp] and [maxSp].
 *   - For each candidate, measures the text with [rememberTextMeasurer]
 *     constrained to the slot width, and asks Compose whether the resulting
 *     layout's height fits and whether there's any visual overflow.
 *   - Picks the largest size that *fits both width and height*.
 *
 * Short strings render big; long paragraphs shrink proportionally so the
 * rectangle always looks "full." No ellipsis — ever.
 *
 * @param text     Content to render. Wraps naturally.
 * @param modifier Applied to the outer BoxWithConstraints; MUST give it a
 *                 bounded size (e.g. `Modifier.fillMaxWidth().height(80.dp)`)
 *                 or the slot has no height constraint and the search picks
 *                 [maxSp] every time.
 * @param color    Text colour.
 * @param fontFamily  Defaults to [hudFont] (monospace).
 * @param fontWeight  Defaults to Normal.
 * @param textAlign   Defaults to Center.
 * @param minSp       Minimum allowed font size in sp. Below this the slot
 *                    is genuinely too small for legibility — caller should
 *                    be using a smaller zone.
 * @param maxSp       Maximum allowed font size in sp.
 */
@Composable
fun SizeJustifiedText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = HudPalette.White,
    fontFamily: FontFamily = hudFont,
    fontWeight: FontWeight = FontWeight.Normal,
    textAlign: TextAlign = TextAlign.Center,
    minSp: Float = 8f,
    maxSp: Float = 96f,
) {
    BoxWithConstraints(modifier = modifier) {
        val measurer = rememberTextMeasurer()
        val density  = LocalDensity.current
        val slotW    = constraints.maxWidth
        val slotH    = constraints.maxHeight

        // Guard: if the slot is unbounded in any axis, don't search — just render
        // at a safe default. BoxWithConstraints gives Int.MAX_VALUE when the parent
        // didn't constrain that axis.
        val bounded = slotW != Constraints.Infinity && slotH != Constraints.Infinity
                      && slotW > 0 && slotH > 0

        val chosenSp: TextUnit = if (!bounded || text.isEmpty()) {
            hudSp(1.0f)
        } else {
            remember(text, slotW, slotH, fontFamily, fontWeight, textAlign) {
                // Binary search: largest sp such that the measured paragraph
                // fits inside slotW × slotH with no visual overflow.
                var lo = minSp
                var hi = maxSp
                var best = minSp
                repeat(12) {   // 12 iterations → precision < 0.03 sp — plenty
                    val mid   = (lo + hi) / 2f
                    val style = TextStyle(
                        fontSize   = mid.sp,
                        fontFamily = fontFamily,
                        fontWeight = fontWeight,
                        textAlign  = textAlign,
                    )
                    val layout = measurer.measure(
                        text        = text,
                        style       = style,
                        constraints = Constraints(maxWidth = slotW),
                        density     = density,
                    )
                    val fits = !layout.hasVisualOverflow
                               && layout.size.height <= slotH
                               && layout.size.width  <= slotW
                    if (fits) {
                        best = mid
                        lo = mid
                    } else {
                        hi = mid
                    }
                }
                best.sp
            }
        }

        Text(
            text       = text,
            color      = color,
            fontSize   = chosenSp,
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            textAlign  = textAlign,
        )
    }
}
