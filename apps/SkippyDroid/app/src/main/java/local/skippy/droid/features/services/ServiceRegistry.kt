package local.skippy.droid.features.services

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Polls SkippyTel's GET /services every [pollIntervalMs] milliseconds.
 *
 * Exposes a [services] StateFlow that the ServicesPanelModule and any
 * voice trigger integration can observe. The first poll fires immediately
 * on [start]; subsequent polls run on the interval.
 *
 * Voice triggers: call [voiceTriggers] to get a flat map of
 *   trigger phrase → service id
 * for dynamic registration into SkippyChat's KeywordScanner equivalent.
 */
class ServiceRegistry(
    private val baseUrl:        String,
    private val pollIntervalMs: Long = 60_000L,
) {

    companion object {
        private const val TAG = "ServiceRegistry"
    }

    private val _services = MutableStateFlow<List<ServiceManifest>>(emptyList())
    val services: StateFlow<List<ServiceManifest>> = _services.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // ── Lifecycle ──────────────────────────────────────────────────────────

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                refresh()
                delay(pollIntervalMs)
            }
        }
        Log.i(TAG, "Started polling $baseUrl/services every ${pollIntervalMs / 1000}s")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Manual refresh — call when you need immediate data (e.g. after app resume). */
    fun refresh() {
        scope.launch { _services.value = fetch() ?: _services.value }
    }

    // ── Query helpers ──────────────────────────────────────────────────────

    /**
     * Flat map of voice trigger phrase → service id.
     * Excludes catch-all ("*") entries — those are handled by the AI tier.
     * Returns entries sorted with most-specific (longer phrase) first to
     * avoid short triggers shadowing longer ones in the scanner.
     */
    fun voiceTriggers(): Map<String, String> {
        return _services.value
            .filter { it.available }
            .flatMap { svc -> svc.voiceTriggers.filter { it != "*" }.map { it to svc.id } }
            .sortedByDescending { (phrase, _) -> phrase.length }
            .toMap()
    }

    /** Services that are available and have a companion Android app. */
    fun companionApps(): List<ServiceManifest> =
        _services.value.filter { it.available && it.companionApp != null }

    // ── Network ────────────────────────────────────────────────────────────

    private fun fetch(): List<ServiceManifest>? {
        return try {
            val req = Request.Builder().url("$baseUrl/services").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "GET /services returned ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val root = JSONObject(body)
                val arr  = root.optJSONArray("services") ?: return emptyList()
                List(arr.length()) { i -> ServiceManifest.fromJson(arr.getJSONObject(i)) }
                    .also { Log.i(TAG, "Loaded ${it.size} services from SkippyTel") }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ServiceRegistry fetch failed: $e")
            null
        }
    }
}
