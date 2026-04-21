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
 */
class TransportLayer(private val pcBaseUrl: String) {

    enum class State { UNKNOWN, ONLINE, OFFLINE }

    var pcState: State by mutableStateOf(State.UNKNOWN)
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
                pcState = ping()
                delay(10_000)
            }
        }
    }

    fun stop() {
        healthJob?.cancel()
        healthJob = null
    }

    private fun ping(): State {
        return try {
            val request = Request.Builder().url("$pcBaseUrl/health").build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) State.ONLINE else State.OFFLINE
            }
        } catch (_: IOException) {
            State.OFFLINE
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
