package com.skippy.droid.compositor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.skippy.droid.layers.ContextEngine
import com.skippy.droid.layers.FeatureModule

/**
 * Layer 6 — Compositor.
 *
 * Renders all feature modules that are:
 *   1. [FeatureModule.enabled] == true (Captain's manual toggle)
 *   2. Active in the current [ContextEngine.Mode] — or declare no mode preference
 *
 * [isGlasses] switches between full [FeatureModule.Overlay] (phone screen — text, panels,
 * everything) and [FeatureModule.GlassesOverlay] (glasses display — AR-only cues, no text).
 */
@Composable
fun Compositor(modules: List<FeatureModule>, context: ContextEngine, isGlasses: Boolean = false) {
    val mode = context.currentMode   // mutableStateOf — drives recomposition on mode change

    Box(modifier = Modifier.fillMaxSize()) {
        modules
            .filter { it.enabled }
            .filter { it.activeIn.isEmpty() || mode in it.activeIn }
            .sortedBy { it.zOrder }
            .forEach { if (isGlasses) it.GlassesOverlay() else it.Overlay() }
    }
}
