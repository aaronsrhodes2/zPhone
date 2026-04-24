package local.skippy.droid.compositor

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Named layout zones for the HUD. The Compositor uses a module's [HudZone]
 * (declared on [local.skippy.droid.layers.FeatureModule]) to decide where the
 * module's content anchors on screen.
 *
 * ── Doctrine (April 21 2026 — Session 7 lock) ─────────────────────────────
 *
 *   Corners (4)             — glance-and-dismiss text (clock, battery, speed, coords)
 *   Top edge / Bottom edge  — centered text bands (compass, inbound speech, Captain's words)
 *   Sidebars (2)            — SYMBOLOGY ONLY. No [androidx.compose.material3.Text],
 *                             no pictographic icons, no glyph characters. Only
 *                             [androidx.compose.foundation.Canvas] primitives whose
 *                             *shape* is the data (bars, stacks, segments).
 *   Viewport (1)            — reserved 1540×960 (16:10) center rectangle. Home of
 *                             the AR nav-canvas, and future mount point for
 *                             passthrough-compliant apps (see PASSTHROUGH_STANDARD.md).
 *
 * [Fullscreen] is the escape hatch for AR canvases and modules that self-place
 * multi-zone content (e.g. [local.skippy.droid.features.navigation.NavigationModule]
 * draws a full-screen Canvas AND a bottom-center error banner — the Compositor
 * lets it handle its own positioning).
 *
 * ── Pixel budget on the canonical 1920×1200 canvas ────────────────────────
 *   TopStart     260×80       TopCenter   1200×180     TopEnd      320×140
 *   LeftBar      180×(full)                            RightBar    200×(full)
 *                              Viewport   1540×960
 *   BottomStart  300×120      BottomCenter 1400×90     BottomEnd   200×80
 *
 * The Compositor clamps the root to 1920×1200 centered on-device; on the S23
 * (2340×1080 landscape) this letterboxes ~420 px of horizontal spare as pure
 * black (reads as transparent on the glasses). On the VITURE 1920×1200 native
 * display the clamp matches exactly — no letterbox.
 */
enum class HudZone {
    /** Top-left corner — glance-and-dismiss text (speed today). */
    TopStart,

    /** Top-center band — world-facing text (compass, inbound transcribed speech). */
    TopCenter,

    /** Top-right corner — glance-and-dismiss text (clock, battery stack). */
    TopEnd,

    /** Bottom-left corner — dev-mode text (coordinates). */
    BottomStart,

    /** Bottom-center band — Captain-facing text (chat pill, nav error banner, voice echo). */
    BottomCenter,

    /** Bottom-right corner — reserved for context-mode readout (stationary / walking / driving). */
    BottomEnd,

    /**
     * Left sidebar — SYMBOLOGY ONLY. ~180 px wide, full height between top/bottom bands.
     * Top half: [local.skippy.droid.compositor.ModeSegments] bound to ContextEngine mode.
     * Bottom half: [local.skippy.droid.compositor.RmsGlyph] bound to VoiceEngine.rmsLevel.
     * No [androidx.compose.material3.Text]. No icons. Canvas primitives only.
     */
    LeftBar,

    /**
     * Right sidebar — SYMBOLOGY ONLY. ~200 px wide, full height between top/bottom bands.
     * Top half: [local.skippy.droid.compositor.VerticalFillBar] bound to phone battery %.
     * Bottom half: [local.skippy.droid.compositor.SignalStack] bound to TransportLayer ping tier.
     * No [androidx.compose.material3.Text]. No icons. Canvas primitives only.
     */
    RightBar,

    /**
     * Center passthrough viewport — 1540×960 (16:10) rectangle nested inside the
     * chrome frame. Home of the AR nav canvas today; future home of mounted
     * passthrough apps (DJ Block Planner, etc.) per PASSTHROUGH_STANDARD.md.
     * Only one module may claim this zone at a time.
     */
    Viewport,

    /** Self-placing escape hatch for modules that paint full-canvas (AR overlays). */
    Fullscreen,
}

/**
 * Mapping from [HudZone] to the `contentAlignment` of the positioning [androidx.compose.foundation.layout.Box]
 * that the Compositor wraps the module in.
 */
val HudZone.contentAlignment: Alignment
    get() = when (this) {
        HudZone.TopStart     -> Alignment.TopStart
        HudZone.TopCenter    -> Alignment.TopCenter
        HudZone.TopEnd       -> Alignment.TopEnd
        HudZone.BottomStart  -> Alignment.BottomStart
        HudZone.BottomCenter -> Alignment.BottomCenter
        HudZone.BottomEnd    -> Alignment.BottomEnd
        HudZone.LeftBar      -> Alignment.CenterStart
        HudZone.RightBar     -> Alignment.CenterEnd
        HudZone.Viewport     -> Alignment.Center
        HudZone.Fullscreen   -> Alignment.Center
    }

/**
 * Mapping from [HudZone] to the horizontal alignment of the inner [androidx.compose.foundation.layout.Column]
 * that stacks multiple modules sharing the same zone.
 */
val HudZone.columnAlignment: Alignment.Horizontal
    get() = when (this) {
        HudZone.TopStart, HudZone.BottomStart,
        HudZone.LeftBar                            -> Alignment.Start
        HudZone.TopCenter, HudZone.BottomCenter,
        HudZone.Viewport, HudZone.Fullscreen       -> Alignment.CenterHorizontally
        HudZone.TopEnd, HudZone.BottomEnd,
        HudZone.RightBar                           -> Alignment.End
    }

/**
 * Breathing room from the screen edge for each zone. Same value for the phone
 * (which is the full-time glasses mirror) and the glasses display itself — the
 * Captain's eye should see the same layout at the same relative position.
 */
val HudZone.edgePadding: PaddingValues
    get() = when (this) {
        HudZone.TopStart     -> PaddingValues(start = 16.dp, top = 10.dp)
        HudZone.TopCenter    -> PaddingValues(top = 10.dp)
        HudZone.TopEnd       -> PaddingValues(end = 16.dp, top = 10.dp)
        HudZone.BottomStart  -> PaddingValues(start = 16.dp, bottom = 24.dp)
        HudZone.BottomCenter -> PaddingValues(bottom = 24.dp)
        HudZone.BottomEnd    -> PaddingValues(end = 16.dp, bottom = 24.dp)
        // Sidebars: small inset from the outer edge; inner edge flush against viewport.
        HudZone.LeftBar      -> PaddingValues(start = 8.dp, top = 140.dp, bottom = 90.dp)
        HudZone.RightBar     -> PaddingValues(end = 8.dp,   top = 140.dp, bottom = 90.dp)
        // Viewport centered; padding handled by the Compositor clamp, not here.
        HudZone.Viewport     -> PaddingValues(0.dp)
        HudZone.Fullscreen   -> PaddingValues(0.dp)
    }

/**
 * Canonical pixel budget for each zone, expressed in dp on the reference
 * 1920×1200 canvas. The Compositor uses these to size the positioning
 * boxes so content can't overflow into a neighboring zone.
 *
 * [Width] null = span remaining horizontal space (used by center bands and Viewport).
 * [Height] null = span remaining vertical space (used by sidebars and Viewport).
 */
data class ZoneBudget(val width: Dp?, val height: Dp?)

val HudZone.budget: ZoneBudget
    get() = when (this) {
        HudZone.TopStart     -> ZoneBudget(260.dp, 80.dp)
        HudZone.TopCenter    -> ZoneBudget(null,   180.dp)
        HudZone.TopEnd       -> ZoneBudget(320.dp, 140.dp)
        HudZone.BottomStart  -> ZoneBudget(300.dp, 120.dp)
        HudZone.BottomCenter -> ZoneBudget(null,   90.dp)
        HudZone.BottomEnd    -> ZoneBudget(200.dp, 80.dp)
        HudZone.LeftBar      -> ZoneBudget(180.dp, null)
        HudZone.RightBar     -> ZoneBudget(200.dp, null)
        HudZone.Viewport     -> ZoneBudget(1540.dp, 960.dp)   // 16:10 locked
        HudZone.Fullscreen   -> ZoneBudget(null, null)
    }

/**
 * Zones on which the Compositor is allowed to paint symbology Canvas content
 * and MUST reject [androidx.compose.material3.Text] or pictographic icons.
 * Used by future lint / enforcement passes.
 */
val HudZone.symbologyOnly: Boolean
    get() = this == HudZone.LeftBar || this == HudZone.RightBar
