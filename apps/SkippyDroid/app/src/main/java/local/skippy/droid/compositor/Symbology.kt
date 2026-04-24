package local.skippy.droid.compositor

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Sidebar symbology primitives — Session 7 Passthrough HUD Standard.
 *
 * ── Doctrine ──────────────────────────────────────────────────────────────
 * These are the ONLY composables allowed inside [HudZone.LeftBar] or
 * [HudZone.RightBar]. The sidebars are SYMBOLOGY-ONLY: no `Text`, no `Image`,
 * no glyph characters. Shape IS the data.
 *
 * Each primitive:
 *   - Is a pure `@Composable` backed by a Compose [Canvas].
 *   - Accepts ONE scalar (or a small data struct) whose rendered silhouette
 *     is the datum — no text interpretation required from the Captain.
 *   - Accepts a [Modifier] for sizing; the caller (e.g. [LeftBarModule])
 *     budgets the slot via `Modifier.weight(1f).fillMaxWidth()`.
 *   - Uses ONLY [HudPalette] colors (accepted as parameters so modules can
 *     wire in e.g. threshold-colored battery states).
 *
 * If you need a new shape, add it HERE, not inside the feature module.
 */

// ── VerticalFillBar ──────────────────────────────────────────────────────

/**
 * A vertical meter: thin outline rectangle, filled from the bottom up to
 * [fraction] of its height. Used by RightBar top half for phone battery.
 *
 * Shape legend:
 *   ▯  empty      fraction = 0.0
 *   ▨  half-full  fraction = 0.5
 *   ▮  full       fraction = 1.0
 *
 * @param fraction Clamped to [0f, 1f]; NaN renders as empty.
 * @param color    Palette color for the fill. The outline uses [HudPalette.DimGreen].
 */
@Composable
fun VerticalFillBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val safe = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    Canvas(modifier = modifier) {
        // Inset the bar slightly so the outline has breathing room inside the slot.
        val insetX = size.width * 0.2f
        val barLeft = insetX
        val barRight = size.width - insetX
        val barTop = size.height * 0.05f
        val barBottom = size.height * 0.95f
        val barHeight = barBottom - barTop

        // Outline (always visible, even at 0%)
        drawRect(
            color = HudPalette.DimGreen,
            topLeft = Offset(barLeft, barTop),
            size = Size(barRight - barLeft, barHeight),
            style = Stroke(width = 3f),
        )

        if (safe > 0f) {
            val fillH = barHeight * safe
            drawRect(
                color = color,
                topLeft = Offset(barLeft, barBottom - fillH),
                size = Size(barRight - barLeft, fillH),
            )
        }
    }
}

// ── SignalStack ──────────────────────────────────────────────────────────

/**
 * A classic cellular-bar glyph: [maxBars] rectangles of ascending height,
 * laid out left-to-right along the bottom of the slot. The leftmost
 * [bars] of them are lit in [color]; the remainder are [HudPalette.DimGreen].
 *
 * Used by RightBar bottom half for Tailscale reachability tier.
 *
 * Shape legend (maxBars = 4):
 *   ▁▃▅▇   bars = 4  (full signal)
 *   ▁▃▅░   bars = 3
 *   ▁▃░░   bars = 2
 *   ░░░░   bars = 0  (unreachable — all dim)
 *
 * @param bars     Number of lit bars. Clamped to [0, maxBars].
 * @param maxBars  Total number of bars in the stack. Default 4.
 * @param color    Palette color for the lit bars.
 */
@Composable
fun SignalStack(
    bars: Int,
    maxBars: Int = 4,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val lit = bars.coerceIn(0, maxBars)
    Canvas(modifier = modifier) {
        val totalWidth = size.width * 0.8f            // leave 10% padding on each side
        val horizontalInset = (size.width - totalWidth) / 2f
        val gap = totalWidth * 0.15f / (maxBars - 1).coerceAtLeast(1)
        val barWidth = (totalWidth - gap * (maxBars - 1)) / maxBars

        val bottomY = size.height * 0.9f
        val maxBarHeight = size.height * 0.75f

        for (i in 0 until maxBars) {
            val heightFraction = (i + 1).toFloat() / maxBars.toFloat()
            val h = maxBarHeight * heightFraction
            val x = horizontalInset + i * (barWidth + gap)
            val y = bottomY - h
            drawRect(
                color = if (i < lit) color else HudPalette.DimGreen,
                topLeft = Offset(x, y),
                size = Size(barWidth, h),
            )
        }
    }
}

// ── RmsGlyph ─────────────────────────────────────────────────────────────

/**
 * A mic-amplitude ribbon: a narrow vertical rectangle centered in the slot
 * whose height grows symmetrically (up + down from center) with [rms].
 *
 * Used by LeftBar bottom half, bound to [local.skippy.droid.layers.VoiceEngine.rmsLevel].
 * When rms = 0 the glyph is invisible (mic idle); when rms = 1 the bar
 * spans the full slot height.
 *
 * Shape legend:
 *   (nothing)   rms = 0.0   mic idle
 *   ─           rms = 0.2   faint activity
 *   │           rms = 1.0   loud activity
 *
 * @param rms    Clamped to [0f, 1f]. Typical values from Android's
 *               onRmsChanged() range roughly [0, 10] dB — caller normalizes.
 * @param color  Palette color for the ribbon (typically [HudPalette.Red]).
 */
@Composable
fun RmsGlyph(
    rms: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val safe = if (rms.isNaN()) 0f else rms.coerceIn(0f, 1f)
    Canvas(modifier = modifier) {
        if (safe <= 0f) return@Canvas

        val ribbonWidth = size.width * 0.18f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val halfH = (size.height * 0.9f * safe) / 2f

        drawRect(
            color = color,
            topLeft = Offset(cx - ribbonWidth / 2f, cy - halfH),
            size = Size(ribbonWidth, halfH * 2f),
        )
    }
}

// ── ModeSegments ─────────────────────────────────────────────────────────

/**
 * A stack of [total] horizontal segments, one of which — the [activeIndex]-th,
 * counted from the top — is lit in [color]. The rest are [HudPalette.DimGreen].
 *
 * Used by LeftBar top half, bound to
 * [local.skippy.droid.layers.ContextEngine.Mode] (stationary / walking / driving,
 * top-to-bottom by speed).
 *
 * Shape legend (total = 3):
 *   ▮           activeIndex = 0   (stationary)
 *   ░
 *   ░
 *
 *   ░
 *   ▮           activeIndex = 1   (walking)
 *   ░
 *
 *   ░
 *   ░
 *   ▮           activeIndex = 2   (driving)
 *
 * @param activeIndex  Which segment is lit. Clamped to [0, total-1].
 * @param total        Segment count. Must be >= 1.
 * @param color        Palette color for the lit segment.
 */
@Composable
fun ModeSegments(
    activeIndex: Int,
    total: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val n = total.coerceAtLeast(1)
    val active = activeIndex.coerceIn(0, n - 1)
    Canvas(modifier = modifier) {
        val horizontalInset = size.width * 0.2f
        val segWidth = size.width - horizontalInset * 2f
        val gap = size.height * 0.04f
        val totalGap = gap * (n - 1)
        val segHeight = (size.height - totalGap) / n

        for (i in 0 until n) {
            val y = i * (segHeight + gap)
            drawRect(
                color = if (i == active) color else HudPalette.DimGreen,
                topLeft = Offset(horizontalInset, y),
                size = Size(segWidth, segHeight),
            )
        }
    }
}

// ── HeadingTick ──────────────────────────────────────────────────────────

/**
 * A single vertical tick marking compass heading on a horizontal strip.
 * Not used by the sidebars (heading lives in [HudZone.TopCenter] as text
 * today) but kept in the primitive library for a future compass-strip
 * module that replaces text with a symbology-only heading bar.
 *
 * Tick x-position = (headingDeg / 360) * width, wrapped to [0, 360).
 *
 * @param headingDeg  Heading in degrees; wrapped modulo 360.
 * @param color       Palette color for the tick (typically [HudPalette.Amber]).
 */
@Composable
fun HeadingTick(
    headingDeg: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val wrapped = ((headingDeg % 360f) + 360f) % 360f
    Canvas(modifier = modifier) {
        val x = size.width * (wrapped / 360f)
        val tickWidth = 3f
        drawRect(
            color = color,
            topLeft = Offset(x - tickWidth / 2f, 0f),
            size = Size(tickWidth, size.height),
        )
    }
}
