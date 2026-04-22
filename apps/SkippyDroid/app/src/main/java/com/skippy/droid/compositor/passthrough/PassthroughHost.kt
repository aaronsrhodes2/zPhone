package com.skippy.droid.compositor.passthrough

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.skippy.droid.compositor.HudPalette
import com.skippy.droid.compositor.HudZone
import com.skippy.droid.layers.CommandDispatcher
import com.skippy.droid.layers.FeatureModule
import com.skippy.droid.layers.GlassesLayer
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * FeatureModule that owns the Passthrough Host pipeline — PROTOCOL §1.
 *
 * One instance lives in the Compositor, claims [HudZone.Viewport], and
 * composes the currently-mounted view's [Scene] there. When no view is
 * active, the viewport renders as pure black (not transparent — the
 * viewport is a framed rectangle; black is the palette default).
 *
 * Responsibilities:
 *   - Lifecycle (start/stop) of [PassthroughServer].
 *   - Hold the [SceneRenderer] + [FrameStreamConsumer] instances used to
 *     paint scene trees inside the viewport.
 *   - Expose `activate(viewId)` / `deactivate()` for the voice registry
 *     ("open DJ block planner") and future dev UI.
 *
 * Context integration: this module is active in **all** context modes
 * (no [activeIn] restriction). Individual mounted views may decide
 * internally to adapt.
 *
 * zOrder 10: above basic HUD modules (clock/battery at 5) so the viewport
 * rectangle clears them; below a future full-screen alert layer.
 */
class PassthroughHost(
    private val appContext: Context,
    private val glasses: GlassesLayer,
    private val dispatcher: CommandDispatcher,
) : FeatureModule {

    override val id = "passthrough_host"
    override var enabled: Boolean by mutableStateOf(true)
    override val zone: HudZone = HudZone.Viewport
    override val zOrder: Int = 10
    override val requiresNetwork: Boolean = true

    // ── Owned pieces ─────────────────────────────────────────────────────────

    val registry = AppRegistry()
    val posePublisher = PosePublisher(glasses)

    /** Shared OkHttp client for every active MJPEG consumer. Long read timeout. */
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val frameConsumer = FrameStreamConsumer(httpClient)
    private val sceneRenderer = SceneRenderer { url, fit, bg, mod ->
        frameConsumer.Render(url, fit, bg, mod)
    }

    val server = PassthroughServer(
        registry      = registry,
        posePublisher = posePublisher,
        dispatcher    = dispatcher,
    )

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onEnable() {
        server.startServer()
    }

    override fun onDisable() {
        server.stopServer()
    }

    /** Wire by voice or dev UI. Tears down any prior mount (§8 multi-view). */
    fun activate(viewId: String) = server.activate(viewId)
    fun deactivate()             = server.deactivateActive()

    // ── Render ───────────────────────────────────────────────────────────────

    @Composable
    override fun Overlay() {
        val scene by server.sceneFlow.collectAsState(initial = null)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HudPalette.Black),
            contentAlignment = Alignment.Center,
        ) {
            scene?.let { sceneRenderer.Render(it, Modifier.fillMaxSize()) }
        }
    }
}
