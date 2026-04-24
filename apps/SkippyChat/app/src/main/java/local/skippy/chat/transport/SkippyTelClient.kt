package local.skippy.chat.transport

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
 * SkippyTel client for SkippyChat.
 *
 * Re-implementation of the relevant slice of
 * [local.skippy.droid.layers.TransportLayer] — quarantine doctrine
 * forbids cross-app imports, so we copy the shape instead. The wire
 * contract is IDENTICAL:
 *   - `GET  /health`            — reachability + latency pill
 *   - `POST /intent/unmatched`  — { text, source, context } →
 *                                 { reply, speak?, tier? }
 *
 * Two observable states drive UI:
 *   - [reachability] : UNKNOWN / ONLINE / OFFLINE
 *   - [pingLatencyMs]: last `/health` round-trip, or null when offline
 *
 * SkippyTel lives on port **3003** on the tailnet host `skippy-pc`.
 * Change in lockstep with the SkippyTel-side port binding.
 */
class SkippyTelClient(private val baseUrl: String) {

    enum class Reachability { UNKNOWN, ONLINE, OFFLINE }

    var reachability: Reachability by mutableStateOf(Reachability.UNKNOWN)
        private set

    /** Most-recent `/health` round-trip latency, or null when offline. */
    var pingLatencyMs: Long? by mutableStateOf(null)
        private set

    /** 3s connect / 5s read — for `/health`, fail fast on unreachable. */
    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Slow client for `/intent/unmatched` — Ollama cold-boot can
     * exceed 10s on the first request. Same 3s connect so unreachable
     * hosts still fail fast; only the read side is relaxed.
     */
    private val intentClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var healthJob: Job? = null

    /** Start the 10s `/health` polling loop. Safe to call once. */
    fun start() {
        if (healthJob != null) return
        healthJob = scope.launch {
            while (isActive) {
                val (state, latency) = pingWithLatency()
                reachability = state
                pingLatencyMs = latency
                delay(10_000)
            }
        }
    }

    fun stop() {
        healthJob?.cancel()
        healthJob = null
    }

    private fun pingWithLatency(): Pair<Reachability, Long?> {
        val start = System.nanoTime()
        return try {
            val request = Request.Builder().url("$baseUrl/health").build()
            healthClient.newCall(request).execute().use { response ->
                val elapsedMs = (System.nanoTime() - start) / 1_000_000L
                if (response.isSuccessful) Reachability.ONLINE to elapsedMs
                else Reachability.OFFLINE to null
            }
        } catch (_: IOException) {
            Reachability.OFFLINE to null
        }
    }

    /**
     * Parsed `POST /intent/unmatched` response.
     *
     * Extends SkippyDroid's equivalent with [latencyMs] + [rawJson]
     * so the DevDetailSheet can show the full exchange without a
     * second request. `rawJson` is the literal response body (or
     * null if the body arrived malformed / empty).
     */
    data class IntentReply(
        val reply: String,
        val speak: String?,
        val tier: String?,
        val latencyMs: Long,
        val rawJson: String?,
    )

    /**
     * POST `/intent/unmatched`. Blocking — call from
     * [Dispatchers.IO] or equivalent.
     *
     * Returns null on any failure (unreachable, timeout, non-200,
     * malformed body, empty reply). Callers render no assistant
     * message on null.
     */
    fun postIntentUnmatched(
        text: String,
        source: String = "text",
        context: Map<String, Any> = emptyMap(),
    ): IntentReply? {
        // Intentionally NOT gated on reachability — the health loop
        // refreshes every 10s, and the Captain may send a message
        // before the first tick lands. Let the request itself be the
        // probe; if it fails we return null and the UI banners.
        val payload = JSONObject().apply {
            put("text", text)
            put("source", source)
            if (context.isNotEmpty()) {
                put("context", JSONObject(context as Map<*, *>))
            }
        }
        val body = payload.toString().toRequestBody(jsonMedia)
        val request = Request.Builder()
            .url("$baseUrl/intent/unmatched")
            .post(body)
            .build()

        val started = System.nanoTime()
        return try {
            intentClient.newCall(request).execute().use { response ->
                val elapsed = (System.nanoTime() - started) / 1_000_000L
                if (!response.isSuccessful) return null
                val raw = response.body?.string() ?: return null
                val obj = JSONObject(raw)
                val reply = obj.optString("reply", "")
                if (reply.isEmpty()) return null
                IntentReply(
                    reply = reply,
                    speak = obj.optString("speak").takeIf { it.isNotEmpty() },
                    tier  = obj.optString("tier").takeIf { it.isNotEmpty() },
                    latencyMs = elapsed,
                    rawJson = raw,
                )
            }
        } catch (_: IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
    }
}
