package com.skippy.droid.compositor

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.skippy.droid.layers.FeatureModule

/**
 * Layer 6 — glasses-side display.
 *
 * Android routes a secondary [Display] to the VITURE glasses automatically when
 * USB-C DisplayPort Alt Mode is active. This Presentation inflates the full Compositor
 * on that secondary display so the Captain sees HUD overlays inside the glasses.
 *
 * Background is black (camera passthrough stand-in). When Layer 2 (Camera2 passthrough)
 * is wired, this class will host the SurfaceView below the Compositor overlays.
 *
 * Lifecycle: created/dismissed by [MainActivity]'s DisplayManager.DisplayListener.
 */
class GlassesPresentation(
    private val activity: ComponentActivity,
    display: Display,
    private val modules: List<FeatureModule>
) : Presentation(activity, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the glasses display lit; run fullscreen
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ComposeView inside a Presentation (Dialog subclass) needs the Activity's
        // lifecycle and saved-state registry injected via ViewTree so that Compose's
        // internal machinery (LaunchedEffect, remember, etc.) can attach correctly.
        val composeView = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )
            setContent {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),   // replaced by camera feed in Layer 2
                    contentAlignment = Alignment.TopEnd
                ) {
                    Compositor(modules)
                }
            }
        }
        setContentView(composeView)
    }
}
