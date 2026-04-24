package local.skippy.droid.layers

import androidx.compose.runtime.Composable
import local.skippy.droid.compositor.HudZone

/**
 * Contract every HUD feature module must implement.
 * Declare your requirements, produce an overlay, and stay toggleable.
 */
interface FeatureModule {
    val id: String
    val requiresGps: Boolean get() = false
    val requiresCamera: Boolean get() = false
    val requiresNetwork: Boolean get() = false
    var enabled: Boolean

    /**
     * Ordering within the compositor:
     *   - Across zones (paint order): higher zOrder paints later, so a nav Canvas
     *     with zOrder=20 draws on top of chrome modules with zOrder 5–10.
     *   - Within a shared chrome zone (vertical stacking): lower zOrder goes
     *     topmost in the zone's Column. Example: Clock (zOrder=5) sits above
     *     Battery (zOrder=10) in [HudZone.TopEnd].
     */
    val zOrder: Int get() = 0

    /**
     * Which named layout zone this module's [Overlay] / [GlassesOverlay]
     * content anchors to. Default is [HudZone.Fullscreen] for AR canvases
     * and self-placing modules; chrome modules should declare an explicit
     * corner or edge (see the doctrine comment in [HudZone]).
     *
     * The Compositor wraps the module's content in a positioning Box with
     * this zone's alignment and edge padding — modules in a named zone
     * should emit *pure content* (no fillMaxSize, no Box with contentAlignment).
     */
    val zone: HudZone get() = HudZone.Fullscreen

    /**
     * Context modes in which this module is active.
     * Empty set (default) = active in ALL modes.
     * Set specific modes for modules that only make sense while walking or driving.
     */
    val activeIn: Set<ContextEngine.Mode> get() = emptySet()

    /** Called once when the module is enabled. */
    fun onEnable() {}

    /** Called once when the module is disabled. */
    fun onDisable() {}

    /** Full overlay drawn on the phone screen — text, panels, arrows, everything. */
    @Composable
    fun Overlay()

    /**
     * Stripped overlay drawn on the glasses display.
     * Default: identical to [Overlay]. Override to show only what makes sense as an
     * AR cue (e.g. direction dots only — no text panels the Captain already sees on the phone).
     */
    @Composable
    fun GlassesOverlay() { Overlay() }
}
