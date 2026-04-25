package local.skippy.droid.compositor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Observable glasses display state — the single source of truth for what
 * the VITURE display renders.
 *
 * Two independent axes:
 *
 *   [mode]   HUD vs FULLSCREEN — whether the HUD chrome (clock, bars, etc.)
 *            is visible around the service viewport, or stripped away so the
 *            active passthrough service fills the full 1920×1200 canvas.
 *
 *   [shaded] Whether a darkening overlay is painted on top of whatever [mode]
 *            is rendering. Captain says "shades" to engage, "eyes open" to
 *            clear. "I'm listening" also clears shading automatically.
 *
 * Injected into [GlassesPresentation] → [Compositor] at presentation creation
 * time. All mutations happen on the main thread (voice-intent handlers run on
 * the UI thread via CommandDispatcher).
 *
 * Phone rotation does NOT affect this state — SkippyDroid stays alive in
 * memory via moveTaskToBack(true) and the GlassesPresentation keeps rendering
 * the glasses display regardless of which phone app is in the foreground.
 */
class GlassesDisplayEngine {

    enum class Mode {
        /** HUD chrome visible; active service renders inside the 1540×960 Viewport. */
        HUD,
        /** HUD chrome hidden; active service (or AR canvas) fills the full 1920×1200. */
        FULLSCREEN,
    }

    /** Current glasses rendering mode. Default: [Mode.HUD]. */
    var mode: Mode by mutableStateOf(Mode.HUD)

    /**
     * Shading overlay active. When true, [HudPalette.ShadingOverlay] is painted
     * on top of the rendered frame — dims the glasses without changing the
     * underlying mode. Cleared automatically on "I'm listening".
     */
    var shaded: Boolean by mutableStateOf(false)
}
