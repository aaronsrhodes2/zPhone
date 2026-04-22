package com.skippy.droid.layers

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Layer 0: connectivity to Skippy Tel Network PC over Tailscale.
 * Maintains a health-check loop; degrades gracefully when offline.
 *
 * ── Observables ────────────────────────────────────────────────────────────
 * Two Compose-observable state fields:
 *   - [pcState]        : UNKNOWN / ONLINE / OFFLINE — coarse reachability.
 *   - [pingLatencyMs]  : round-trip ms to `/health`, or `null` when offline
 *                        (used by [com.skippy.droid.compositor.SignalStack]
 *                        in the right sidebar for the signal glyph, and by
 *                        the future CommandDispatcher's tier-5 fallback
 *                        decision — both read the same value).
 */
class TransportLayer(private val pcBaseUrl: String) {

    enum class State { UNKNOWN, ONLINE, OFFLINE }

    var pcState: State by mutableStateOf(State.UNKNOWN)
        private set

    /**
     * Round-trip latency to PC `/health` in milliseconds, or `null` when the
     * PC is unreachable. Drives the [com.skippy.droid.compositor.SignalStack]
     * symbology glyph and the tier-5 fallback routing.
     */
    var pingLatencyMs: Long? by mutableStateOf(null)
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var healthJob: Job? = null

    fun start() {
        healthJob = scope.launch {
            while (isActive) {
                val (state, latency) = pingWithLatency()
                pcState = state
                pingLatencyMs = latency
                delay(10_000)
            }
        }
    }

    fun stop() {
        healthJob?.cancel()
        healthJob = null
    }

    /**
     * Convert the latest [pingLatencyMs] to a signal-stack tier in
     * `[0, 4]`, matching the Session 7 doctrine:
     *   `< 50 ms`   → 4 bars
     *   `< 150 ms`  → 3 bars
     *   `< 400 ms`  → 2 bars
     *   reachable   → 1 bar
     *   unreachable → 0 bars
     */
    fun signalBars(): Int {
        val lat = pingLatencyMs ?: return 0
        return when {
            lat < 50 -> 4
            lat < 150 -> 3
            lat < 400 -> 2
            else -> 1
        }
    }

    private fun pingWithLatency(): Pair<State, Long?> {
        val start = System.nanoTime()
        return try {
            val request = Request.Builder().url("$pcBaseUrl/health").build()
            client.newCall(request).execute().use { response ->
                val elapsedMs = (System.nanoTime() - start) / 1_000_000L
                if (response.isSuccessful) State.ONLINE to elapsedMs
                else State.OFFLINE to null
            }
        } catch (_: IOException) {
            State.OFFLINE to null
        }
    }

    /** Fire a POST and return the response body string, or null on failure. */
    fun post(path: String, body: okhttp3.RequestBody): String? {
        if (pcState != State.ONLINE) return null
        return try {
            val request = Request.Builder().url("$pcBaseUrl$path").post(body).build()
            client.newCall(request).execute().use { it.body?.string() }
        } catch (_: IOException) {
            null
        }
    }
}
