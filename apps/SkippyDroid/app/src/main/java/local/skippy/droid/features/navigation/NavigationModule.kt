package local.skippy.droid.features.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import local.skippy.droid.compositor.HudPalette
import local.skippy.droid.compositor.HudZone
import local.skippy.droid.compositor.SizeJustifiedText
import local.skippy.droid.layers.DeviceLayer
import local.skippy.droid.layers.FeatureModule
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Navigation overlay.
 *
 *   Overlay()         — phone surface. Error banner ONLY. The phone is the
 *                       glasses mirror (plus future chat prompt); all nav UI
 *                       comes from [GlassesOverlay] below.
 *
 *   GlassesOverlay()  — glasses surface. World-anchored dot trail painted on
 *                       the virtual ground via GPS → screen projection, plus
 *                       a green triangle arrow at the far end of the trail
 *                       rotating to point at the step endpoint.
 *
 * No text panels. Dots + arrow is the entire navigation UI.
 * Mirror of `apps/SkippyGlassesMac/Sources/GlassesHUDView.swift`.
 */
class NavigationModule(
    private val nav: NavigationEngine,
    private val device: DeviceLayer
) : FeatureModule {

    override val id = "local.skippy.navigation"
    override val requiresGps = true
    override val requiresNetwork = true
    override var enabled by mutableStateOf(true)
    override val zOrder = 20    // above all other modules while navigating
    // Self-placing: this module paints a full-screen Canvas AND a bottom-center
    // error banner; the Compositor leaves positioning to us.
    override val zone = HudZone.Fullscreen

    // ── Phone-side overlay ─────────────────────────────────────────────────────

    @Composable
    override fun Overlay() {
        // Phone is the glasses mirror — render the same content there.
        GlassesOverlay()
    }

    // ── Glasses-side overlay ───────────────────────────────────────────────────

    @Composable
    override fun GlassesOverlay() {
        // Error banner is independent of isNavigating state — if a route
        // request just failed, we surface the message regardless.
        ErrorBanner()

        if (!nav.isNavigating) return
        val state = nav.state ?: return
        val step  = state.currentStep ?: return
        val loc   = device.location ?: return

        // Synthetic heading: bearing from current GPS to step endpoint.
        // Used when no trustworthy compass is available (Mac / emulator / pre-VITURE).
        // When the glasses IMU is live we'd prefer that — but bearing-to-endpoint is
        // visually stable and points the trail where the Captain needs to go.
        val effectiveHeading = if (device.glasses?.isConnected == true &&
                                   device.headingDegrees != 0.0) {
            device.headingDegrees
        } else {
            NavigationEngine.bearing(
                fromLat = loc.latitude, fromLng = loc.longitude,
                toLat   = step.endLat,   toLng   = step.endLng
            )
        }

        val dotsSnapshot = nav.trail.dots.toList()   // snapshot for stable iteration
        val endLat = nav.trail.endLat
        val endLng = nav.trail.endLng

        val dotColor   = HudPalette.Cyan               // "identifier" — these are the waypoints
        val arrowColor = HudPalette.Green              // "self/accent" — the active destination marker

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Draw trail dots
            for (dot in dotsSnapshot) {
                val proj = NavigationEngine.worldProject(
                    dotLat = dot.lat, dotLng = dot.lng,
                    fromLat = loc.latitude, fromLng = loc.longitude,
                    headingDeg = effectiveHeading,
                    screenWidthPx = w, screenHeightPx = h
                ) ?: continue

                val (x, y, scale) = proj
                val radius = (scale * 55.0).coerceIn(6.0, 30.0).toFloat()
                val alpha  = (scale *  8.0).coerceIn(0.2, 0.95).toFloat()

                drawCircle(
                    color  = dotColor.copy(alpha = alpha),
                    radius = radius,
                    center = Offset(x, y)
                )
            }

            // Draw green end-of-path arrow — triangle pointing "forward" at the endpoint.
            // The endpoint is the last polyline vertex for the current step.
            if (endLat != null && endLng != null) {
                val endProj = NavigationEngine.worldProject(
                    dotLat = endLat, dotLng = endLng,
                    fromLat = loc.latitude, fromLng = loc.longitude,
                    headingDeg = effectiveHeading,
                    screenWidthPx = w, screenHeightPx = h
                )
                endProj?.let { (x, y, _) ->
                    // Rotate the triangle to point along the direction from the last
                    // rendered dot toward the endpoint (falls back to pointing "up"
                    // when no dots exist yet).
                    val rotationDeg = computeArrowRotation(
                        dotsSnapshot, endLat, endLng,
                        loc.latitude, loc.longitude, effectiveHeading, w, h
                    )

                    val size = 22f
                    rotate(degrees = rotationDeg, pivot = Offset(x, y)) {
                        val path = Path().apply {
                            moveTo(x,                 y - size)
                            lineTo(x - size * 0.7f,   y + size * 0.6f)
                            lineTo(x + size * 0.7f,   y + size * 0.6f)
                            close()
                        }
                        drawPath(path = path, color = arrowColor)
                    }
                }
            }
        }
    }

    // ── Error banner (bottom-center, size-justified to a fixed slot) ──────────

    /**
     * Red pill at the bottom-center that appears whenever `nav.error` is set.
     * Uses [SizeJustifiedText] so a short message ("No GPS fix") renders big
     * and a long one ("No route found to …") shrinks to fit the same
     * rectangle. No ellipsis, no scrollbars, no wrapping past the slot.
     */
    @Composable
    private fun ErrorBanner() {
        val err = nav.error ?: return
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                modifier = Modifier
                    .padding(bottom = 24.dp, start = 20.dp, end = 20.dp)
                    .fillMaxWidth(0.7f)                    // up to 70% of screen width
                    .height(72.dp)                         // fixed banner height
                    .background(
                        HudPalette.Black.copy(alpha = 0.80f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SizeJustifiedText(
                    text = "⚠ $err",
                    color = HudPalette.Red,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Compute rotation (degrees) for the green end arrow so it points along the
     * tangent direction at the endpoint. Uses screen-space delta from the last
     * projected dot to the endpoint — naturally falls into the trail's visual flow.
     * Returns 0f when no reference dot is available (arrow points up on screen).
     */
    private fun computeArrowRotation(
        dots: List<GroundDotTrail.Dot>,
        endLat: Double, endLng: Double,
        fromLat: Double, fromLng: Double,
        headingDeg: Double,
        w: Float, h: Float
    ): Float {
        val endProj = NavigationEngine.worldProject(
            dotLat = endLat, dotLng = endLng,
            fromLat = fromLat, fromLng = fromLng,
            headingDeg = headingDeg,
            screenWidthPx = w, screenHeightPx = h
        ) ?: return 0f

        // Use the last on-screen dot as the "from" point.
        val lastDot = dots.asReversed().firstNotNullOfOrNull { d ->
            NavigationEngine.worldProject(
                dotLat = d.lat, dotLng = d.lng,
                fromLat = fromLat, fromLng = fromLng,
                headingDeg = headingDeg,
                screenWidthPx = w, screenHeightPx = h
            )
        } ?: return 0f

        val dx = endProj.first  - lastDot.first
        val dy = endProj.second - lastDot.second
        // atan2(dx, -dy) so 0° = up, clockwise positive (Compose rotation convention)
        return Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
    }
}
