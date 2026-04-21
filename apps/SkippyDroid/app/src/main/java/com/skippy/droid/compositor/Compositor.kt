package com.skippy.droid.compositor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.skippy.droid.layers.FeatureModule

/** Layer 6: draws all enabled feature overlays in z-order on top of the camera passthrough. */
@Composable
fun Compositor(modules: List<FeatureModule>) {
    Box(modifier = Modifier.fillMaxSize()) {
        modules
            .filter { it.enabled }
            .sortedBy { it.zOrder }
            .forEach { it.Overlay() }
    }
}
