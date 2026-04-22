package com.skippy.droid.compositor.passthrough

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Consumer for the frame lane — MJPEG over `multipart/x-mixed-replace`.
 *
 * ── Protocol (PROTOCOL §16.2) ──────────────────────────────────────────────
 *   - Single long-lived GET to the view's declared MJPEG URL.
 *   - Each part: `Content-Type: image/jpeg` + optional `X-Frame-Seq` +
 *     `X-Pose-Seq` + `X-Pose-Tms` + `\r\n` + JPEG bytes.
 *   - Boundary is `--<boundary>` between parts; `<boundary>` comes from
 *     the response `Content-Type: multipart/x-mixed-replace; boundary=…`.
 *
 * ── Behavior ───────────────────────────────────────────────────────────────
 *   - Latest-wins: we only keep the most recent decoded frame. Old frames
 *     are discarded; the decoder never queues.
 *   - Stale-frame overlay: if no new frame arrives within 500 ms an amber
 *     pixel indicator is painted (host-drawn, not view-visible in the tree).
 *     PROTOCOL §16.5.
 *   - Reconnect: exponential backoff 500 ms / 1 s / 2 s / 4 s / 8 s (cap 8 s).
 *     PROTOCOL §16.6.
 *
 * The composable [Render] mounts a consumer scoped to its own CoroutineScope,
 * cancelling the connection on dispose — safe to unmount / remount.
 */
class FrameStreamConsumer(
    private val httpClient: OkHttpClient = DEFAULT_CLIENT,
) {
    companion object {
        private const val TAG = "FrameStream"
        private const val STALE_MS = 500L

        private val DEFAULT_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .readTimeout(0,  TimeUnit.SECONDS)     // long-lived stream — no read timeout
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Implements [SceneRenderer.FrameSource]. Compose this inside a
     * [SceneRenderer] via `SceneRenderer(frameSource = consumer::Render)`.
     */
    @Composable
    fun Render(
        url: String,
        fit: FrameFit,
        background: Color,
        modifier: Modifier,
    ) {
        var frame by remember(url) { mutableStateOf<ImageBitmap?>(null) }
        var lastFrameTs by remember(url) { mutableStateOf(0L) }
        var error by remember(url) { mutableStateOf<String?>(null) }

        DisposableEffect(url) {
            val scope = CoroutineScope(Dispatchers.IO)
            val job = scope.launch {
                connectWithBackoff(url) { bmp ->
                    frame = bmp
                    lastFrameTs = System.currentTimeMillis()
                    error = null
                }
            }
            onDispose {
                job.cancel()
                scope.cancel()
            }
        }

        Box(
            modifier = modifier.background(background),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val bmp = frame
                if (bmp != null) {
                    drawFitted(bmp, fit)
                    // Stale-frame indicator: small amber dot in top-right if the
                    // last frame is > 500ms old. Doesn't block rendering.
                    val age = System.currentTimeMillis() - lastFrameTs
                    if (age > STALE_MS) {
                        drawCircle(
                            color = Color(0xFFFFCC00),
                            radius = 6f,
                            center = Offset(size.width - 12f, 12f),
                        )
                    }
                }
            }
        }
    }

    /** Scale/position the decoded bitmap according to [fit]. */
    private fun DrawScope.drawFitted(bmp: ImageBitmap, fit: FrameFit) {
        val sw = bmp.width.toFloat()
        val sh = bmp.height.toFloat()
        val dw = size.width
        val dh = size.height
        if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return

        val (dstW, dstH, dstX, dstY) = when (fit) {
            FrameFit.Fill -> FitBox(dw, dh, 0f, 0f)
            FrameFit.Cover -> {
                val scale = maxOf(dw / sw, dh / sh)
                val w = sw * scale; val h = sh * scale
                FitBox(w, h, (dw - w) / 2f, (dh - h) / 2f)
            }
            FrameFit.Contain -> {
                val scale = minOf(dw / sw, dh / sh)
                val w = sw * scale; val h = sh * scale
                FitBox(w, h, (dw - w) / 2f, (dh - h) / 2f)
            }
        }
        drawImageRectScaled(bmp, dstX, dstY, dstW, dstH)
    }

    private data class FitBox(val w: Float, val h: Float, val x: Float, val y: Float)

    /** Compose's draw scope doesn't expose a `drawImage(ImageBitmap, Rect, Rect)` in
     *  the stable API cleanly; we emulate by sizing the image via a native
     *  transform. */
    private fun DrawScope.drawImageRectScaled(
        bmp: ImageBitmap, x: Float, y: Float, w: Float, h: Float,
    ) {
        val intSrcSize  = androidx.compose.ui.unit.IntSize(bmp.width, bmp.height)
        val intDstSize  = androidx.compose.ui.unit.IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
        val intDstOffs  = androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt())
        drawImage(
            image = bmp,
            srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
            srcSize   = intSrcSize,
            dstOffset = intDstOffs,
            dstSize   = intDstSize,
        )
    }

    // ── Connection + parsing ─────────────────────────────────────────────────

    private suspend fun connectWithBackoff(url: String, onFrame: (ImageBitmap) -> Unit) {
        val backoffs = longArrayOf(500, 1000, 2000, 4000, 8000, 8000)
        var attempt = 0
        while (CoroutineScope(Dispatchers.IO).isActive) {
            try {
                streamOnce(url, onFrame)
                attempt = 0   // clean disconnect → reset backoff
            } catch (e: Throwable) {
                Log.w(TAG, "stream error: ${e.message}; backing off")
            }
            val delayMs = backoffs[attempt.coerceAtMost(backoffs.lastIndex)]
            attempt += 1
            kotlinx.coroutines.delay(delayMs)
        }
    }

    /** One connection lifetime. Returns when the stream ends normally. */
    private suspend fun streamOnce(url: String, onFrame: (ImageBitmap) -> Unit) =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "HTTP ${resp.code} from $url")
                    return@use
                }
                val contentType = resp.header("Content-Type") ?: ""
                val boundary = parseBoundary(contentType)
                    ?: run {
                        Log.w(TAG, "no boundary in Content-Type: '$contentType'")
                        return@use
                    }

                val body = resp.body ?: return@use
                val stream = BufferedInputStream(body.byteStream())

                parseMjpeg(stream, boundary) { jpegBytes ->
                    val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    if (bmp != null) {
                        onFrame(bmp.asImageBitmap())
                    } else {
                        Log.v(TAG, "decode returned null (malformed JPEG part)")
                    }
                }
            }
        }

    /** Extract the `boundary=` value from a multipart Content-Type header. */
    private fun parseBoundary(contentType: String): String? {
        val re = Regex("""boundary=["']?([^"';\s]+)""")
        return re.find(contentType)?.groupValues?.getOrNull(1)
    }

    /**
     * Parse an MJPEG stream. Each part:
     *   [--boundary CRLF]
     *   Content-Type: image/jpeg CRLF
     *   [Content-Length: N CRLF]
     *   [X-*:  CRLF]*
     *   CRLF
     *   <N bytes of JPEG> CRLF
     * Repeats until stream end.
     */
    private suspend fun parseMjpeg(
        stream: InputStream,
        boundary: String,
        onPart: suspend (ByteArray) -> Unit,
    ) {
        val boundaryMark = "--$boundary".toByteArray(Charsets.US_ASCII)
        val crlf = "\r\n".toByteArray(Charsets.US_ASCII)

        while (CoroutineScope(Dispatchers.IO).isActive) {
            // Find next boundary marker line.
            if (!skipUntil(stream, boundaryMark)) return
            // Skip the trailing CRLF of the boundary line (or "--" for terminator).
            val peek = readLine(stream) ?: return
            if (peek == "--") return   // terminator

            // Read part headers until empty line.
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(stream) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).lowercase().trim()] =
                        line.substring(colon + 1).trim()
                }
            }

            val len = headers["content-length"]?.toIntOrNull()
            val jpeg = if (len != null && len > 0) {
                readExactly(stream, len) ?: return
            } else {
                readUntilBoundary(stream, crlf + boundaryMark) ?: return
            }
            onPart(jpeg)
            // After the part data a CRLF follows before the next boundary — skipUntil handles it.
        }
    }

    // ── Stream helpers ───────────────────────────────────────────────────────

    /** Skip bytes until [marker] has been fully read from [stream]. Returns false on EOF. */
    private fun skipUntil(stream: InputStream, marker: ByteArray): Boolean {
        var matched = 0
        while (true) {
            val b = stream.read()
            if (b < 0) return false
            val ch = b.toByte()
            matched = if (ch == marker[matched]) matched + 1 else if (ch == marker[0]) 1 else 0
            if (matched == marker.size) return true
        }
    }

    /** Read a CRLF-terminated ASCII line (excluding the CRLF). Returns null on EOF. */
    private fun readLine(stream: InputStream): String? {
        val sb = StringBuilder(64)
        var prev = -1
        while (true) {
            val b = stream.read()
            if (b < 0) return if (sb.isEmpty() && prev < 0) null else sb.toString()
            if (prev == '\r'.code && b == '\n'.code) {
                sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
        }
    }

    /** Read exactly [n] bytes from [stream], or null on EOF. */
    private fun readExactly(stream: InputStream, n: Int): ByteArray? {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = stream.read(out, read, n - read)
            if (r < 0) return null
            read += r
        }
        return out
    }

    /**
     * Read bytes until the byte sequence [marker] is encountered, returning
     * everything *before* the marker. Marker bytes are consumed. Returns null
     * on EOF without match.
     *
     * Used when the producer omits `Content-Length` — we scan until the
     * boundary marker is found.
     */
    private fun readUntilBoundary(stream: InputStream, marker: ByteArray): ByteArray? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val b = stream.read()
            if (b < 0) return null
            val ch = b.toByte()
            if (ch == marker[matched]) {
                matched += 1
                if (matched == marker.size) {
                    // Trim the marker bytes we wrote into `buf` before the partial-match.
                    return buf.toByteArray()
                }
            } else {
                // Match broke; dump accumulated partial-match bytes into buf, then this one.
                if (matched > 0) {
                    for (i in 0 until matched) buf.write(marker[i].toInt())
                    matched = 0
                }
                buf.write(b)
            }
        }
    }
}
