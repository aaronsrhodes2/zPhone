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

    /** Called once when the module is enabled. */
    fun onEnable() {}

    /** Called once when the module is disabled. */
    fun onDisable() {}

    /** The Compose content drawn on the overlay layer. Nothing drawn when not enabled. */
    @Composable
    fun Overlay()
}
