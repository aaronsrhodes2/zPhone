package com.skippy.droid.layers

import androidx.compose.runtime.Composable

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

    /** Z-order in the compositor — higher floats on top. */
    val zOrder: Int get() = 0

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
