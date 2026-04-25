package local.skippy.droid.compositor

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import local.skippy.droid.layers.CameraPassthrough
import local.skippy.droid.layers.ContextEngine
import local.skippy.droid.layers.FeatureModule
import local.skippy.droid.layers.PassthroughCamera

/**
 * Layer 2 + 6 — glasses-side display.
 *
 * Android routes a secondary [Display] to the VITURE glasses automatically when
 * USB-C DisplayPort Alt Mode is active.  This Presentation fills that display with:
 *
 *   1. Camera passthrough (rear camera → glasses = "see-through")
 *   2. Compositor HUD overlays on top
 *
 * [passthrough] is the shared [PassthroughCamera] owned by MainActivity.  When
 * this Presentation's TextureView becomes available it calls [PassthroughCamera.attachGlasses],
 * which closes the phone session and re-opens the camera targeting the glasses display.
 * On dismiss, [PassthroughCamera.detachGlasses] falls back to the phone surface.
 *
 * Lifecycle: created/dismissed by [local.skippy.droid.MainActivity]'s DisplayManager listener.
 */
class GlassesPresentation(
    private val activity: ComponentActivity,
    display: Display,
    private val modules: List<FeatureModule>,
    private val passthrough: PassthroughCamera,
    private val contextEngine: ContextEngine,
    private val displayEngine: GlassesDisplayEngine,
) : Presentation(activity, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the glasses display lit; run edge-to-edge
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ComposeView inside a Presentation (Dialog subclass) needs the Activity's
        // lifecycle and saved-state registry injected via ViewTree.
        val composeView = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )
            setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Layer 2: camera passthrough — rear camera fills the glasses display
                    CameraPassthrough(
                        onSurfaceAvailable = { surface -> passthrough.attachGlasses(surface) },
                        onSurfaceDestroyed  = { passthrough.detachGlasses() },
                        modifier = Modifier.fillMaxSize()
                    )
                    // Layer 6: HUD overlays — Compositor places each module into its
                    // declared HudZone. Do NOT wrap in an outer Box(contentAlignment = …)
                    // here; that would collapse every module into a single corner.
                    // displayEngine drives HUD/FULLSCREEN mode and the shading overlay.
                    Compositor(
                        modules       = modules,
                        context       = contextEngine,
                        isGlasses     = true,
                        displayEngine = displayEngine,
                    )
                }
            }
        }
        setContentView(composeView)
    }
}
