package local.skippy.droid.features.navigation

import android.location.Location
import android.text.Html
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import local.skippy.droid.BuildConfig
import local.skippy.droid.layers.DeviceLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Navigation engine — fetches a Google Maps Directions route and tracks
 * progress via GPS, advancing through steps as the Captain moves.
 *
 * Call [navigateTo] (suspend) to start a route. The [state] observable
 * drives [NavigationModule]'s overlay; both are backed by mutableStateOf
 * so Compose recomposes automatically.
 *
 * Step advancement thresholds:
 *   Walking : within 15 m of the step end-point
 *   Driving : within 40 m  (GPS accuracy at speed is lower)
 */
class NavigationEngine(private val device: DeviceLayer) {

    enum class TravelMode { WALKING, DRIVING }

    data class NavStep(
        val instruction: String,    // HTML-stripped, e.g. "Turn left onto Oak St"
        val maneuver: String,       // Google maneuver key, e.g. "turn-left"
        val distanceMeters: Int,
        val durationSeconds: Int,
        val endLat: Double,
        val endLng: Double,
        /** Full polyline for this step (decoded from `step.polyline.points`). */
        val polylinePoints: List<Pair<Double, Double>>
    )

    data class NavState(
        val steps: List<NavStep>,
        val currentStepIndex: Int,
        val totalDistanceMeters: Int,
        val totalDurationSeconds: Int,
        val destination: String,
        val mode: TravelMode
    ) {
        val currentStep: NavStep?
            get() = steps.getOrNull(currentStepIndex)

        /** Sum of distances from current step onward. */
        val remainingDistanceMeters: Int
            get() = steps.drop(currentStepIndex).sumOf { it.distanceMeters }

        /** Sum of durations from current step onward — used for live ETA. */
        val remainingDurationSeconds: Int
            get() = steps.drop(currentStepIndex).sumOf { it.durationSeconds }
    }

    // ── Observables ───────────────────────────────────────────────────────────

    var state: NavState? by mutableStateOf(null)
        private set

    var isNavigating: Boolean by mutableStateOf(false)
        private set

    /** True while the Directions API request is in flight — drives the route-fetch spinner. */
    var isFetchingRoute: Boolean by mutableStateOf(false)
        private set

    /** Set when a route request fails — cleared on next [navigateTo] call. */
    var error: String? by mutableStateOf(null)
        private set

    /** World-anchored dot trail — observed by NavigationModule for rendering. */
    val trail = GroundDotTrail()

    // ── Internals ─────────────────────────────────────────────────────────────

    private val scope = MainScope()
    private var trackingJob: Job? = null
    private val http = OkHttpClient()


    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetch a route and begin navigation. Cancels any existing route first.
     * Must be called from a coroutine (suspend).
     *
     * @param destination Free-text address or place name — passed to Directions API as-is.
     * @param mode        [TravelMode.WALKING] or [TravelMode.DRIVING].
     */
    suspend fun navigateTo(destination: String, mode: TravelMode) {
        cancel()
        error = null
        isFetchingRoute = true

        try {
            val loc = device.location
            if (loc == null) {
                error = "No GPS fix — cannot navigate"
                return
            }

            val origin      = "${loc.latitude},${loc.longitude}"
            val modeParam   = if (mode == TravelMode.WALKING) "walking" else "driving"
            val encodedDest = URLEncoder.encode(destination, "UTF-8")
            val url = "https://maps.googleapis.com/maps/api/directions/json" +
                "?origin=$origin" +
                "&destination=$encodedDest" +
                "&mode=$modeParam" +
                "&key=${BuildConfig.MAPS_API_KEY}"

            val newState = withContext(Dispatchers.IO) {
                try {
                    val body = http.newCall(Request.Builder().url(url).build())
                        .execute()
                        .body?.string() ?: return@withContext null
                    parseDirections(body, destination, mode)
                } catch (_: Exception) {
                    null
                }
            }

            if (newState == null || newState.steps.isEmpty()) {
                error = "No route found to \"$destination\""
                return
            }

            state = newState
            isNavigating = true

            // Paint dots onto the world along the first step's polyline.
            newState.steps.firstOrNull()?.let { firstStep ->
                trail.repaint(
                    polyline = firstStep.polylinePoints,
                    fromLat  = loc.latitude,
                    fromLng  = loc.longitude
                )
            }

            startTracking()
        } finally {
            isFetchingRoute = false
        }
    }

    /** Stop navigation and clear state. */
    fun cancel() {
        trackingJob?.cancel()
        trackingJob = null
        state = null
        isNavigating = false
        trail.clear()
    }

    // ── Tracking ──────────────────────────────────────────────────────────────

    private fun startTracking() {
        trackingJob = scope.launch {
            while (isNavigating) {
                delay(TRACK_INTERVAL_MS)
                advanceIfClose()
            }
        }
    }

    private fun advanceIfClose() {
        val current = state ?: return
        val step    = current.currentStep ?: return
        val loc     = device.location ?: return

        // Eat any dots the Captain has walked past.
        trail.eatConsumed(fromLat = loc.latitude, fromLng = loc.longitude)

        val threshold = if (current.mode == TravelMode.WALKING)
            ADVANCE_WALKING_M else ADVANCE_DRIVING_M

        val dist = FloatArray(1)
        Location.distanceBetween(
            loc.latitude, loc.longitude,
            step.endLat,  step.endLng,
            dist
        )

        if (dist[0] <= threshold) {
            val next = current.currentStepIndex + 1
            if (next >= current.steps.size) {
                // Arrived at destination
                cancel()
            } else {
                state = current.copy(currentStepIndex = next)
                // Repaint dots along the new step's polyline.
                trail.repaint(
                    polyline = current.steps[next].polylinePoints,
                    fromLat  = loc.latitude,
                    fromLng  = loc.longitude
                )
            }
        } else if (trail.dots.size < 3) {
            // Trail is nearly empty (dots eaten) — repaint along the remaining step polyline.
            trail.repaint(
                polyline = step.polylinePoints,
                fromLat  = loc.latitude,
                fromLng  = loc.longitude
            )
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun parseDirections(json: String, destination: String, mode: TravelMode): NavState? {
        return try {
            val root = JSONObject(json)
            if (root.getString("status") != "OK") return null

            val leg = root
                .getJSONArray("routes").getJSONObject(0)
                .getJSONArray("legs").getJSONObject(0)

            val totalDist = leg.getJSONObject("distance").getInt("value")
            val totalDur  = leg.getJSONObject("duration").getInt("value")

            val stepsJson = leg.getJSONArray("steps")
            val steps = (0 until stepsJson.length()).map { i ->
                val s = stepsJson.getJSONObject(i)

                val endLat = s.getJSONObject("end_location").getDouble("lat")
                val endLng = s.getJSONObject("end_location").getDouble("lng")

                // Extract full polyline; fall back to straight line if missing.
                val polyline: List<Pair<Double, Double>> = s.optJSONObject("polyline")
                    ?.optString("points")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { PolylineDecoder.decode(it) }
                    ?: run {
                        val start = s.optJSONObject("start_location")
                        val sLat = start?.optDouble("lat", endLat) ?: endLat
                        val sLng = start?.optDouble("lng", endLng) ?: endLng
                        listOf(sLat to sLng, endLat to endLng)
                    }

                NavStep(
                    instruction    = stripHtml(s.getString("html_instructions")),
                    maneuver       = s.optString("maneuver", "straight"),
                    distanceMeters = s.getJSONObject("distance").getInt("value"),
                    durationSeconds= s.getJSONObject("duration").getInt("value"),
                    endLat         = endLat,
                    endLng         = endLng,
                    polylinePoints = polyline
                )
            }

            NavState(steps, 0, totalDist, totalDur, destination, mode)
        } catch (_: Exception) {
            null
        }
    }

    private fun stripHtml(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().trim()

    // ── Bearing helper — synthetic heading when no compass data is trustworthy ──

    companion object {
        private const val ADVANCE_WALKING_M  = 15f
        private const val ADVANCE_DRIVING_M  = 40f
        private const val TRACK_INTERVAL_MS  = 4_000L

        /**
         * Compass bearing in degrees from one GPS coordinate to another.
         * 0° = north, 90° = east, 180° = south, 270° = west.
         */
        fun bearing(
            fromLat: Double, fromLng: Double,
            toLat:   Double, toLng:   Double
        ): Double {
            val dNorth = (toLat - fromLat) * 111_139.0
            val dEast  = (toLng - fromLng) * 111_139.0 * kotlin.math.cos(Math.toRadians(fromLat))
            val deg = Math.toDegrees(kotlin.math.atan2(dEast, dNorth))
            return if (deg < 0) deg + 360 else deg
        }

        /**
         * Projects a GPS dot onto screen coordinates given current position, heading, and screen size.
         *
         * Flat-Earth ENU approximation + standard perspective projection.
         * Returns `null` if the dot is behind the viewer, too close, or off-screen.
         *
         * @param screenWidthPx   render surface width in pixels
         * @param screenHeightPx  render surface height in pixels
         * @param hFovDeg         horizontal field of view (VITURE Luma Ultra ≈ 45°,
         *                        derived from 52° diagonal at 16:10; placeholder
         *                        until a measured pass lands)
         * @param eyeHeightM      Captain's eye height above the ground (m)
         * @return Triple of (screenX, screenY, scale) where scale = 1/forwardDistance.
         */
        fun worldProject(
            dotLat: Double, dotLng: Double,
            fromLat: Double, fromLng: Double,
            headingDeg: Double,
            screenWidthPx:  Float,
            screenHeightPx: Float,
            hFovDeg:    Double = 45.0,
            eyeHeightM: Double = 1.65
        ): Triple<Float, Float, Double>? {
            // 1. ENU offset in metres
            val dNorth = (dotLat - fromLat) * 111_139.0
            val dEast  = (dotLng - fromLng) * 111_139.0 *
                kotlin.math.cos(Math.toRadians(fromLat))

            // 2. Rotate into camera frame (forward = depth axis, right = lateral)
            val h = Math.toRadians(headingDeg)
            val forward =  dNorth * kotlin.math.cos(h) + dEast * kotlin.math.sin(h)
            val right   = -dNorth * kotlin.math.sin(h) + dEast * kotlin.math.cos(h)

            if (forward <= 0.5) return null   // behind or within 0.5 m

            // 3. Perspective projection
            val focalLen = (screenWidthPx / 2f) /
                kotlin.math.tan(Math.toRadians(hFovDeg / 2.0)).toFloat()
            val screenX  = screenWidthPx  / 2f + focalLen * (right   / forward).toFloat()
            val screenY  = screenHeightPx / 2f + focalLen * (eyeHeightM / forward).toFloat()

            // 10% horizontal margin for edge dots; vertical clip is strict.
            val hMargin = screenWidthPx * 0.10f
            if (screenX < -hMargin || screenX > screenWidthPx + hMargin ||
                screenY < 0f || screenY > screenHeightPx
            ) return null

            val scale = 1.0 / forward
            return Triple(screenX, screenY, scale)
        }
    }
}
