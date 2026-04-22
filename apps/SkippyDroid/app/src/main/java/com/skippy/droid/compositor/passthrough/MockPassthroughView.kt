package com.skippy.droid.compositor.passthrough

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Debug-only passthrough view — the **proto-Star-Map**.
 *
 * Exercises the full frame lane (§16) + pose SSE + scene-tree registration
 * loop *without* requiring an external producer. Runs entirely in-process
 * on the emulator or phone. Wire it from `MainActivity` behind
 * `BuildConfig.DEBUG`.
 *
 * What it does:
 *   1. Stands up a tiny HTTP server on [producerPort] (default 47824).
 *   2. POSTs `/passthrough/register` to [PassthroughServer] announcing itself.
 *   3. When Skippy opens the pose SSE channel, the producer reads each pose
 *      event, renders a JPEG of synthetic "star" dots pinned to world
 *      yaw-space (so turning the head sweeps the sky), and pushes each
 *      rendered JPEG into its MJPEG stream.
 *   4. POSTs one `scene:patch` to set up the viewport with a single
 *      FrameStream node covering the whole viewport.
 *
 * This is the narrowest possible exercise of the frame lane.  A real Star
 * Map producer will live in `apps/SkippyStarMap/` as a standalone project
 * and compute actual ephemeris — this mock just proves the pipe works.
 *
 * Not shipped in release builds.
 */
class MockPassthroughView(
    private val hostPort: Int = PassthroughServer.DEFAULT_PORT,
    private val producerPort: Int = 47824,
    private val viewId: String = "mock.starmap",
) {
    companion object { private const val TAG = "MockPassthroughView" }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var producerServer: ProducerServer? = null
    private var renderJob: Job? = null

    /** Holds the most recent JPEG rendered by [renderLoop]. Read by MJPEG GET. */
    @Volatile private var latestJpeg: ByteArray = blankJpeg()
    @Volatile private var latestSeq: Long = 0

    fun start() {
        producerServer = ProducerServer(producerPort).also { it.startServer() }
        scope.launch { registerWithHost() }
        renderJob = scope.launch { renderLoop() }
    }

    fun stop() {
        renderJob?.cancel()
        producerServer?.stop()
        producerServer = null
        scope.cancel()
    }

    // ── Registration ─────────────────────────────────────────────────────────

    private suspend fun registerWithHost() {
        val client = OkHttpClient()
        val body = JSONObject().apply {
            put("id", viewId)
            put("name", "Mock Star Map")
            put("spec_version", "1")
            put("manifest_url", "http://127.0.0.1:$producerPort/manifest.json")
            put("stream_url", "http://127.0.0.1:$producerPort/control")
            put("stream_origin", "http://127.0.0.1:$producerPort")
            put("aspect_ratio", "16:10")
            put("min_text_px", 22)
            put("max_focus_targets", 7)
            put("palette", JSONArray().apply {
                put("black"); put("white"); put("cyan"); put("amber")
            })
        }.toString()
        val req = Request.Builder()
            .url("http://127.0.0.1:$hostPort/passthrough/register")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(req).execute().use { r ->
                Log.i(TAG, "register -> ${r.code} ${r.body?.string()}")
            }
        } catch (e: IOException) {
            Log.w(TAG, "register failed: ${e.message}")
            return
        }
        // Push an initial scene:patch to insert a FrameStream covering the viewport.
        pushInitialScene(client)
    }

    private fun pushInitialScene(client: OkHttpClient) {
        val frameStreamUrl = "http://127.0.0.1:$producerPort/stream.mjpeg?view=$viewId"
        val rootNode = JSONObject().apply {
            put("type", "Box")
            put("id", "root")
            put("children", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "FrameStream")
                    put("id", "ar_sky")
                    put("props", JSONObject().apply {
                        put("url", frameStreamUrl)
                        put("fit", "cover")
                        put("pose", true)
                    })
                })
            })
        }
        val body = JSONObject().apply {
            put("view", viewId)
            put("ops", JSONArray().apply {
                put(JSONObject().apply {
                    put("op", "set")
                    put("path", "/root")
                    put("value", rootNode)
                })
            })
        }.toString()
        val req = Request.Builder()
            .url("http://127.0.0.1:$hostPort/passthrough/patch")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(req).execute().use { r ->
                Log.i(TAG, "patch -> ${r.code}")
            }
        } catch (e: IOException) {
            Log.w(TAG, "patch failed: ${e.message}")
        }
    }

    // ── Render loop ──────────────────────────────────────────────────────────

    /**
     * Pulls pose from Skippy's pose SSE and produces one JPEG per pose tick.
     * Simplified: just renders at 30 fps off wall-clock and fakes yaw drift
     * so the emulator shows motion without needing an IMU. Real version
     * will subscribe to `/passthrough/pose` and use each pose event's quat.
     */
    private suspend fun renderLoop() {
        var seq = 0L
        val start = System.currentTimeMillis()
        while (scope.isActive) {
            val tSec = (System.currentTimeMillis() - start) / 1000.0
            val syntheticYawDeg = (tSec * 15.0) % 360.0   // 15°/sec spin
            latestJpeg = renderStarsJpeg(syntheticYawDeg)
            latestSeq = ++seq
            delay(33L)   // ~30 fps
        }
    }

    /**
     * Render a 1540×960 scene of cyan/amber dots pinned to yaw. Dots at
     * fixed azimuth positions; turning the head slides them across the frame.
     */
    private fun renderStarsJpeg(yawDeg: Double): ByteArray {
        val w = 1540; val h = 960
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)

        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 204, 255)   // cyan
        }
        val planetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 204, 0)   // amber
        }

        // Fake sky: 40 dots at deterministic azimuths / altitudes.
        val hFov = 45.0
        val vFov = hFov * (h.toDouble() / w)
        for (i in 0 until 40) {
            val azDeg = (i * 360.0 / 40.0)
            val altDeg = ((i * 17) % 40) - 15.0   // -15° to +25°
            val relAz = ((azDeg - yawDeg + 540.0) % 360.0) - 180.0  // -180..180
            if (kotlin.math.abs(relAz) > hFov / 2) continue
            if (kotlin.math.abs(altDeg) > vFov / 2) continue
            val x = w / 2.0 + (relAz / (hFov / 2)) * (w / 2.0)
            val y = h / 2.0 - (altDeg / (vFov / 2)) * (h / 2.0)
            val r = if (i % 7 == 0) 8f else 4f
            c.drawCircle(x.toFloat(), y.toFloat(), r,
                if (i % 11 == 0) planetPaint else starPaint)
        }

        val buf = ByteArrayOutputStream(64 * 1024)
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, buf)
        bmp.recycle()
        return buf.toByteArray()
    }

    private fun blankJpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.BLACK)
        val buf = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 50, buf)
        bmp.recycle()
        return buf.toByteArray()
    }

    // ── Producer HTTP server ─────────────────────────────────────────────────

    /**
     * Stripped NanoHTTPD serving the MJPEG stream + a no-op control SSE.
     * Skippy (the host) is the client of *both* — it GETs the MJPEG directly
     * for the frame lane, and GETs the control SSE only if it needs to.
     */
    private inner class ProducerServer(port: Int) : NanoHTTPD("127.0.0.1", port) {

        fun startServer() {
            try { start(SOCKET_READ_TIMEOUT, false) }
            catch (e: IOException) { Log.e(TAG, "producer start failed", e) }
        }

        override fun serve(session: IHTTPSession): Response = when (session.uri) {
            "/stream.mjpeg" -> serveMjpeg()
            "/control"      -> serveControlStub()
            "/manifest.json"-> newFixedLengthResponse(Response.Status.OK,
                "application/json", "{\"name\":\"Mock Star Map\"}")
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }

        private fun serveMjpeg(): Response {
            val boundary = "frame"
            val input = PipedInputStream(128 * 1024)
            val out   = PipedOutputStream(input)
            scope.launch {
                try {
                    var lastEmittedSeq = -1L
                    while (isActive) {
                        val seq = latestSeq
                        if (seq != lastEmittedSeq) {
                            val jpeg = latestJpeg
                            val header = "--$boundary\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: ${jpeg.size}\r\n" +
                                "X-Frame-Seq: $seq\r\n\r\n"
                            synchronized(out) {
                                out.write(header.toByteArray(Charsets.UTF_8))
                                out.write(jpeg)
                                out.write("\r\n".toByteArray())
                                out.flush()
                            }
                            lastEmittedSeq = seq
                        }
                        delay(16L)
                    }
                } catch (_: IOException) {
                    /* client closed */
                } finally {
                    try { out.close() } catch (_: IOException) {}
                }
            }
            val r = newChunkedResponse(Response.Status.OK,
                "multipart/x-mixed-replace; boundary=$boundary", input)
            r.addHeader("Cache-Control", "no-cache")
            r.addHeader("Connection", "keep-alive")
            return r
        }

        private fun serveControlStub(): Response {
            val r = newChunkedResponse(Response.Status.OK, "text/event-stream",
                ByteArrayOutputStream().apply {
                    write(": mock producer control stream\n\n".toByteArray())
                }.toByteArray().inputStream())
            r.addHeader("Cache-Control", "no-cache")
            return r
        }
    }

    // TODO(session-7b-followup): subscribe to /passthrough/pose and rotate
    // the sky per incoming quat instead of synthetic yaw drift. Adds a
    // yawFromQuat helper (w,x,y,z → deg) when that wiring lands.
}
