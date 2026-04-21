package com.skippy.droid.features.navigation

import android.location.Location
import android.text.Html
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.skippy.droid.BuildConfig
import com.skippy.droid.layers.DeviceLayer
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
        val endLng: Double
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

    /** Set when a route request fails — cleared on next [navigateTo] call. */
    var error: String? by mutableStateOf(null)
        private set

    // ── Internals ─────────────────────────────────────────────────────────────

    private val scope = MainScope()
    private var trackingJob: Job? = null
    private val http = OkHttpClient()

    companion object {
        private const val ADVANCE_WALKING_M  = 15f
        private const val ADVANCE_DRIVING_M  = 40f
        private const val TRACK_INTERVAL_MS  = 4_000L
    }

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
        startTracking()
    }

    /** Stop navigation and clear state. */
    fun cancel() {
        trackingJob?.cancel()
        trackingJob = null
        state = null
        isNavigating = false
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
            }
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
                NavStep(
                    instruction    = stripHtml(s.getString("html_instructions")),
                    maneuver       = s.optString("maneuver", "straight"),
                    distanceMeters = s.getJSONObject("distance").getInt("value"),
                    durationSeconds= s.getJSONObject("duration").getInt("value"),
                    endLat         = s.getJSONObject("end_location").getDouble("lat"),
                    endLng         = s.getJSONObject("end_location").getDouble("lng")
                )
            }

            NavState(steps, 0, totalDist, totalDur, destination, mode)
        } catch (_: Exception) {
            null
        }
    }

    private fun stripHtml(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().trim()
}
