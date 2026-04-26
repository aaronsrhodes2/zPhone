package local.skippy.chat.telemetry

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Deprecation-tracking telemetry for every tappable UI element.
 *
 * DOCTRINE: Every touch-input control (button, toggle, slider, …) must call
 * [UiTelemetry.click] in its onClick handler. The server accumulates click
 * counts per app+element. During audits, elements with zero (or very low)
 * counts are removed — replaced by voice commands or automated logic.
 *
 * Usage:
 *   1. Call [init] once from MainActivity:
 *        UiTelemetry.init("http://100.122.71.14:3003", "skippy_chat")
 *
 *   2. In every onClick:
 *        UiTelemetry.click("send_button")
 *
 * Audit endpoint: GET http://skippy-pc:3003/telemetry/ui_summary
 */
object UiTelemetry {

    private const val TAG = "UiTelemetry"

    private var baseUrl: String = ""
    private var appId:   String = ""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    fun init(baseUrl: String, appId: String) {
        if (this.baseUrl.isNotEmpty()) return
        this.baseUrl = baseUrl.trimEnd('/')
        this.appId   = appId
        Log.d(TAG, "UiTelemetry ready — app=$appId")
    }

    fun click(element: String) {
        if (baseUrl.isEmpty()) return
        scope.launch {
            try {
                val body = JSONObject().apply {
                    put("app",     appId)
                    put("element", element)
                    put("ts",      System.currentTimeMillis() / 1000.0)
                }.toString().toRequestBody("application/json".toMediaType())

                http.newCall(
                    Request.Builder()
                        .url("$baseUrl/telemetry/ui_event")
                        .post(body)
                        .build()
                ).execute().close()
            } catch (_: Exception) { /* never surface */ }
        }
    }
}
