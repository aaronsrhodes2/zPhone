package local.skippy.droid.layers

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
 *                        (used by [local.skippy.droid.compositor.SignalStack]
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
     * PC is unreachable. Drives the [local.skippy.droid.compositor.SignalStack]
     * symbology glyph and the tier-5 fallback routing.
     */
    var pingLatencyMs: Long? by mutableStateOf(null)
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Second OkHttp client for slow inference paths (e.g. `/intent/unmatched`
     * which routes to Ollama — warm 2s, cold-boot can exceed 10s on the
     * first request after service start). Same 3s connect timeout so
     * unreachable hosts still fail fast; only the read side is relaxed.
     */
    private val slowClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

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

    /** Fire a GET and return the response body string, or null on any failure. */
    fun get(path: String): String? {
        return try {
            val request = Request.Builder().url("$pcBaseUrl$path").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (_: IOException) {
            null
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

    /**
     * Parsed response from `POST /intent/unmatched` per SKIPPYTEL_BRIEF.md.
     * `reply` is the text the phone renders (teleprompter, captions, etc.),
     * `speak` is the TTS-intended form (may equal `reply` if no separate
     * voice form exists), `tier` is the routing tag SkippyTel assigns
     * ("local" = Ollama, "cloud" = Anthropic).
     */
    data class IntentReply(
        val reply: String,
        val speak: String?,
        val tier: String?,
    )

    /**
     * Escalate an unmatched or "explain" voice command to SkippyTel's
     * `/intent/unmatched` endpoint. Uses [slowClient] — LLM routing can
     * run 2s warm, 10s+ cold. Returns null on any failure (offline,
     * timeout, non-200, malformed body); callers render no response.
     *
     * Blocking — must be called from a coroutine on [Dispatchers.IO]
     * or equivalent. The phone-side intent handlers in MainActivity
     * wrap this in `lifecycleScope.launch(Dispatchers.IO) { ... }`.
     */
    fun postIntentUnmatched(
        text: String,
        source: String = "voice",
        context: Map<String, Any> = emptyMap(),
    ): IntentReply? {
        if (pcState != State.ONLINE) return null

        val payload = JSONObject().apply {
            put("text", text)
            put("source", source)
            if (context.isNotEmpty()) {
                put("context", JSONObject(context as Map<*, *>))
            }
        }
        val body = payload.toString().toRequestBody(jsonMedia)
        val request = Request.Builder()
            .url("$pcBaseUrl/intent/unmatched")
            .post(body)
            .build()

        return try {
            slowClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val raw = response.body?.string() ?: return null
                val obj = JSONObject(raw)
                val reply = obj.optString("reply", "")
                if (reply.isEmpty()) return null
                IntentReply(
                    reply = reply,
                    speak = obj.optString("speak").takeIf { it.isNotEmpty() },
                    tier  = obj.optString("tier").takeIf { it.isNotEmpty() },
                )
            }
        } catch (_: IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
    }
}
