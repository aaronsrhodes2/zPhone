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
import local.skippy.chat.model.ServiceManifest
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
 * SkippyTel lives on port **3003**.
 * Emulator baseUrl: `http://10.0.2.2:3003` (AVD loopback to host).
 * Real device baseUrl: `http://skippy-pc:3003` (Tailscale MagicDNS).
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
     * Parsed `GET /bilby/status` response.
     *
     * [nowPlaying] is the track currently active (deck that is playing, or
     * most recently loaded if nothing is actively playing). Null when Bilby
     * has no source available (Mac offline + Drive not configured).
     */
    data class BilbyStatus(
        val source: String,           // "traktor" | "drive" | "none"
        val traktorAlive: Boolean,
        val playingDeck: String?,     // "a" | "b" | null
        val nowPlaying: TrackInfo?,   // currently playing track
        val deckA: TrackInfo?,
        val deckB: TrackInfo?,
    )

    data class TrackInfo(
        val title: String,
        val artist: String,
    )

    /**
     * GET `/bilby/status`. Blocking — call from [Dispatchers.IO].
     *
     * Returns null on any failure (SkippyTel unreachable, no source active, etc.).
     */
    fun getBilbyStatus(): BilbyStatus? {
        val request = Request.Builder().url("$baseUrl/bilby/status").build()
        return try {
            healthClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val raw = response.body?.string() ?: return null
                val obj = org.json.JSONObject(raw)

                fun parseTrack(key: String): TrackInfo? {
                    val sub = obj.optJSONObject(key) ?: return null
                    val title  = sub.optString("title").takeIf { it.isNotEmpty() } ?: return null
                    val artist = sub.optString("artist", "")
                    return TrackInfo(title, artist)
                }

                val loaded  = obj.optJSONObject("loaded")
                val deckA   = loaded?.let { parseTrackFromObj(it.optJSONObject("a")) }
                val deckB   = loaded?.let { parseTrackFromObj(it.optJSONObject("b")) }
                val nowJson = obj.optJSONObject("now_playing")
                val now     = nowJson?.let { parseTrackFromObj(it) }

                BilbyStatus(
                    source       = obj.optString("source", "none"),
                    traktorAlive = obj.optBoolean("traktor_alive", false),
                    playingDeck  = obj.optString("playing_deck").takeIf { it.isNotEmpty() },
                    nowPlaying   = now,
                    deckA        = deckA,
                    deckB        = deckB,
                )
            }
        } catch (_: Exception) { null }
    }

    private fun parseTrackFromObj(obj: org.json.JSONObject?): TrackInfo? {
        obj ?: return null
        val title = obj.optString("title").takeIf { it.isNotEmpty() } ?: return null
        return TrackInfo(title = title, artist = obj.optString("artist", ""))
    }

    /**
     * GET `/services`. Blocking — call from [Dispatchers.IO].
     *
     * Returns the live SkippyTel service manifest so the app can:
     *   - Register dynamic voice triggers in [KeywordScanner]
     *   - Show available services in the UI
     *
     * Returns null on any failure (unreachable, non-200, malformed body).
     */
    fun getServices(): List<ServiceManifest>? {
        val request = Request.Builder().url("$baseUrl/services").build()
        return try {
            healthClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val raw  = response.body?.string() ?: return null
                val root = JSONObject(raw)
                val arr  = root.optJSONArray("services") ?: return emptyList()
                List(arr.length()) { i -> ServiceManifest.fromJson(arr.getJSONObject(i)) }
            }
        } catch (_: Exception) { null }
    }

    /**
     * POST `/bilby/next`. Blocking — call from [Dispatchers.IO].
     * Tells Bilby to advance to the next track. Returns true on success.
     */
    fun postBilbyNext(): Boolean {
        val body = "".toRequestBody(jsonMedia)
        val request = Request.Builder()
            .url("$baseUrl/bilby/next")
            .post(body)
            .build()
        return try {
            healthClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    /**
     * Parsed `GET /music/bilby-status` response.
     * Used by SkippyChat's header to show a tiny ♪/⏸ glyph when DJ Bilby is active.
     */
    data class MusicStatus(
        val status: String,     // "playing" | "paused" | "none"
        val title:  String?,
        val artist: String?,
    )

    /**
     * GET `/music/bilby-status`. Blocking — call from [Dispatchers.IO].
     * Returns null on any failure; returns `MusicStatus(status="none")` when idle.
     */
    fun getMusicStatus(): MusicStatus? {
        val request = Request.Builder().url("$baseUrl/music/bilby-status").build()
        return try {
            healthClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val raw = response.body?.string() ?: return null
                val obj = JSONObject(raw)
                MusicStatus(
                    status = obj.optString("status", "none"),
                    title  = obj.optString("title").takeIf { it.isNotEmpty() },
                    artist = obj.optString("artist").takeIf { it.isNotEmpty() },
                )
            }
        } catch (_: Exception) { null }
    }

    /**
     * Parsed `POST /translate/text` response.
     *
     * [detectedLang] is an ISO-639-1 code ("en", "es", "fr", …).
     * [englishText] is the English translation (same as input when already English).
     */
    data class TranslateTextReply(
        val detectedLang: String,
        val englishText: String,
    )

    /**
     * POST `/translate/text`. Blocking — call from [Dispatchers.IO].
     *
     * Uses Gemini on SkippyTel to detect the language of [text] and translate
     * to English. Returns null on any failure (unreachable, non-200, malformed).
     *
     * Fast path on SkippyTel: pure-ASCII input is returned immediately without
     * a Gemini call, so English speech never touches the LLM.
     */
    fun translateText(text: String, hintLang: String? = null): TranslateTextReply? {
        val payload = JSONObject().apply {
            put("text", text)
            if (hintLang != null) put("hint_lang", hintLang)
        }
        val body = payload.toString().toRequestBody(jsonMedia)
        val request = Request.Builder()
            .url("$baseUrl/translate/text")
            .post(body)
            .build()
        return try {
            intentClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val raw = response.body?.string() ?: return null
                val obj = JSONObject(raw)
                TranslateTextReply(
                    detectedLang = obj.optString("detected_lang", "unknown"),
                    englishText  = obj.optString("english_text", text),
                )
            }
        } catch (_: IOException) { null }
        catch (_: org.json.JSONException) { null }
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
        val imageBase64: String? = null,  // base64 PNG data URL from SD sentinel
        val imagePrompt: String? = null,  // SD prompt that produced the image
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
                    reply       = reply,
                    speak       = obj.optString("speak").takeIf { it.isNotEmpty() },
                    tier        = obj.optString("tier").takeIf { it.isNotEmpty() },
                    latencyMs   = elapsed,
                    rawJson     = raw,
                    imageBase64 = obj.optString("image_b64").takeIf { it.isNotEmpty() },
                    imagePrompt = obj.optString("image_prompt").takeIf { it.isNotEmpty() },
                )
            }
        } catch (_: IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
    }
}
