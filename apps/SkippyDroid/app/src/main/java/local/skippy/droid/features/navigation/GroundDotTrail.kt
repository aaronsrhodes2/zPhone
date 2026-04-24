package local.skippy.droid.features.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Maintains a set of GPS-anchored dot positions painted on the virtual ground.
 *
 * Dots sit at fixed world distances along the current route polyline — they do
 * NOT move each frame. The projection engine ([NavigationEngine.worldProject])
 * makes them appear world-locked by recomputing screen positions from the
 * Captain's live GPS + heading on every recompose.
 *
 * Mirror of `apps/SkippyGlassesMac/Sources/MacNavigationEngine.swift` GroundDotTrail.
 */
class GroundDotTrail {

    data class Dot(val id: Long, val lat: Double, val lng: Double)

    /**
     * World distances at which dots are painted (metres ahead along the route).
     * Exponential spacing — dense near feet so perspective looks like real
     * ground, sparse near the horizon.
     */
    val dotDistancesM: DoubleArray = doubleArrayOf(2.0, 4.0, 7.0, 12.0, 20.0, 33.0, 55.0, 90.0)

    /** Distance within which a dot is considered "eaten" (Captain passed it). */
    val eatRadiusM: Double = 2.0

    /** Observable snapshot list — Compose Canvas recomposes when this changes. */
    val dots: androidx.compose.runtime.snapshots.SnapshotStateList<Dot> = mutableStateListOf()

    /** End-of-path arrow target — the last polyline point, used to rotate the green arrow. */
    var endLat: Double? by mutableStateOf(null)
        private set
    var endLng: Double? by mutableStateOf(null)
        private set

    private var nextId: Long = 0L

    /**
     * Recompute all dot world positions along [polyline], starting from `(fromLat, fromLng)`.
     * Call on route start, step advance, or when the trail is running low.
     */
    fun repaint(polyline: List<Pair<Double, Double>>, fromLat: Double, fromLng: Double) {
        dots.clear()
        if (polyline.isEmpty()) {
            endLat = null
            endLng = null
            return
        }
        val poly = if (polyline.size == 1) listOf(polyline[0], polyline[0]) else polyline

        for (d in dotDistancesM) {
            walkPolyline(poly, fromLat, fromLng, d)?.let { (lat, lng) ->
                dots.add(Dot(id = nextId++, lat = lat, lng = lng))
            }
        }
        // Anchor the green arrow at the polyline endpoint (last step vertex).
        poly.last().let { (lat, lng) ->
            endLat = lat
            endLng = lng
        }
    }

    /** Remove dots within [eatRadiusM] of the Captain's current position. */
    fun eatConsumed(fromLat: Double, fromLng: Double) {
        val keep = dots.filter {
            metersBetween(fromLat, fromLng, it.lat, it.lng) > eatRadiusM
        }
        if (keep.size != dots.size) {
            dots.clear()
            dots.addAll(keep)
        }
    }

    fun clear() {
        dots.clear()
        endLat = null
        endLng = null
    }

    // ── Polyline walking (private geometry) ──────────────────────────────────

    /**
     * Returns the GPS coord at [distanceM] metres ahead along [polyline],
     * starting from the closest point on the polyline to `(fromLat, fromLng)`.
     */
    private fun walkPolyline(
        polyline: List<Pair<Double, Double>>,
        fromLat: Double,
        fromLng: Double,
        distanceM: Double
    ): Pair<Double, Double>? {
        if (polyline.size < 2) return polyline.firstOrNull()

        // Find the segment + t parameter closest to the Captain's current position.
        var bestSeg = 0
        var bestT = 0.0
        var bestDist = Double.POSITIVE_INFINITY

        for (i in 0 until polyline.size - 1) {
            val t  = clampedT(polyline[i], polyline[i + 1], fromLat, fromLng)
            val pt = lerp(polyline[i], polyline[i + 1], t)
            val d  = metersBetween(fromLat, fromLng, pt.first, pt.second)
            if (d < bestDist) {
                bestDist = d
                bestSeg = i
                bestT = t
            }
        }

        // Walk forward distanceM from that closest point.
        var remaining = distanceM
        var seg = bestSeg
        var t   = bestT

        while (remaining > 1e-3) {
            if (seg >= polyline.size - 1) break
            val a = polyline[seg]
            val b = polyline[seg + 1]
            val segLen   = metersBetween(a.first, a.second, b.first, b.second)
            val toEndLen = segLen * (1.0 - t)

            if (remaining <= toEndLen) {
                val newT = t + remaining / segLen
                return lerp(a, b, kotlin.math.min(newT, 1.0))
            }

            remaining -= toEndLen
            seg += 1
            t = 0.0
        }

        return polyline.last()
    }

    private fun clampedT(
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
        pLat: Double,
        pLng: Double
    ): Double {
        val dlat = b.first  - a.first
        val dlng = b.second - a.second
        val len2 = dlat * dlat + dlng * dlng
        if (len2 < 1e-18) return 0.0
        val raw  = ((pLat - a.first) * dlat + (pLng - a.second) * dlng) / len2
        return raw.coerceIn(0.0, 1.0)
    }

    private fun lerp(
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
        t: Double
    ): Pair<Double, Double> =
        (a.first + t * (b.first - a.first)) to (a.second + t * (b.second - a.second))

    companion object {
        fun metersBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dN = (lat2 - lat1) * 111_139.0
            val dE = (lng2 - lng1) * 111_139.0 * cos(Math.toRadians(lat1))
            return sqrt(dN * dN + dE * dE)
        }
    }
}
