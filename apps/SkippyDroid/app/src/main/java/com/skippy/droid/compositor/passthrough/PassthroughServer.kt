package com.skippy.droid.compositor.passthrough

import android.util.Log
import com.skippy.droid.layers.CommandDispatcher
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress

/**
 * Embedded HTTP server implementing PROTOCOL v1.
 *
 * Ownership: lifecycled by [PassthroughHost]. One instance for the whole
 * app, registered on port 47823 (arbitrary, Skippy-local convention).
 *
 * Endpoints (PROTOCOL §1):
 *   GET  /passthrough/stream?view=<id>     — SSE control channel, host → view
 *   GET  /passthrough/pose?view=<id>       — SSE pose channel,    host → view
 *   POST /passthrough/register             — view announces itself
 *   POST /passthrough/patch                — scene:patch deltas
 *   POST /passthrough/intent_result        — response to intent event
 *   POST /passthrough/speak                — TTS request
 *   POST /passthrough/log                  — view log line
 *   POST /passthrough/error                — view:error
 *   POST /passthrough/request_unmount
 *   POST /passthrough/request_full_scene
 *
 * Binding (PROTOCOL §14): bound to 0.0.0.0 for interface flexibility, but
 * [isTrustedRemote] rejects anything that isn't loopback or the Tailscale
 * CGNAT range (100.64.0.0/10). Tailscale = auth; phone's WiFi LAN is not.
 *
 * Scene state (PROTOCOL §11): the server keeps the last parsed [Scene] per
 * mounted view. Patches apply atomically via [ScenePatchApplier]; the
 * Compositor's Viewport pass reads the current scene from the
 * [sceneFlow] shared flow.
 */
class PassthroughServer(
    private val registry: AppRegistry,
    private val posePublisher: PosePublisher,
    private val dispatcher: CommandDispatcher,
    private val port: Int = DEFAULT_PORT,
) : NanoHTTPD("0.0.0.0", port) {

    companion object {
        const val DEFAULT_PORT = 47823
        private const val TAG   = "PassthroughServer"

        /** Hard-coded Tailscale CGNAT range per Tailscale docs: 100.64.0.0/10. */
        private val TAILSCALE_RANGE_MIN = bytesToInt(100, 64, 0, 0).toLong() and 0xFFFFFFFFL
        private val TAILSCALE_RANGE_MAX = bytesToInt(100, 127, 255, 255).toLong() and 0xFFFFFFFFL

        private fun bytesToInt(a: Int, b: Int, c: Int, d: Int): Int =
            (a shl 24) or (b shl 16) or (c shl 8) or d
    }

    // ── Public state ─────────────────────────────────────────────────────────

    /** Parsed current scene for the active mount, or null if no mount. */
    private val _sceneFlow = MutableSharedFlow<Scene?>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val sceneFlow: SharedFlow<Scene?> = _sceneFlow.asSharedFlow()

    /** Last-known scene per view-id (PROTOCOL §11 state persistence). */
    private val sceneCache = java.util.concurrent.ConcurrentHashMap<String, Scene>()

    // ── Internals ────────────────────────────────────────────────────────────

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Per-view control-channel event bus. Each active SSE subscriber of
     * `/passthrough/stream?view=<id>` reads from this. [MutableSharedFlow] with
     * `replay=0` so late subscribers don't get duplicates of old intents.
     */
    private val controlFlows = java.util.concurrent.ConcurrentHashMap<String, MutableSharedFlow<JSONObject>>()

    /** Monotonic seq per direction per channel (PROTOCOL §10). */
    private var controlSeq: Long = 0

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun startServer() {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "Passthrough server listening on :$port")
        } catch (e: IOException) {
            Log.e(TAG, "Server start failed", e)
        }
    }

    fun stopServer() {
        stop()
        serverScope.cancel()
    }

    // ── Request routing ──────────────────────────────────────────────────────

    override fun serve(session: IHTTPSession): Response {
        if (!isTrustedRemote(session.remoteIpAddress)) {
            return json(Response.Status.FORBIDDEN, err("forbidden_origin",
                "client ${session.remoteIpAddress} not loopback or Tailscale"))
        }
        val method = session.method
        val uri    = session.uri
        return try {
            when {
                method == Method.GET  && uri == "/passthrough/stream" -> serveControlStream(session)
                method == Method.GET  && uri == "/passthrough/pose"   -> servePoseStream(session)
                method == Method.POST && uri == "/passthrough/register" -> handleRegister(session)
                method == Method.POST && uri == "/passthrough/patch" -> handlePatch(session)
                method == Method.POST && uri == "/passthrough/intent_result" -> handleIntentResult(session)
                method == Method.POST && uri == "/passthrough/speak" -> handleSpeak(session)
                method == Method.POST && uri == "/passthrough/log"   -> handleLog(session)
                method == Method.POST && uri == "/passthrough/error" -> handleViewError(session)
                method == Method.POST && uri == "/passthrough/request_unmount" -> handleRequestUnmount(session)
                method == Method.POST && uri == "/passthrough/request_full_scene" -> handleRequestFullScene(session)
                else -> json(Response.Status.NOT_FOUND, err("not_found", "$method $uri"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve: $method $uri", e)
            json(Response.Status.INTERNAL_ERROR, err("internal", e.message ?: "unknown"))
        }
    }

    // ── Trust check (§14) ────────────────────────────────────────────────────

    private fun isTrustedRemote(ip: String?): Boolean {
        if (ip == null) return false
        return try {
            val addr = InetAddress.getByName(ip)
            if (addr.isLoopbackAddress) return true
            val raw = addr.address
            if (raw.size != 4) return false    // v4 only for Tailscale CGNAT check
            val ipLong = ((raw[0].toInt() and 0xFF).toLong() shl 24) or
                         ((raw[1].toInt() and 0xFF).toLong() shl 16) or
                         ((raw[2].toInt() and 0xFF).toLong() shl  8) or
                         ((raw[3].toInt() and 0xFF).toLong())
            ipLong in TAILSCALE_RANGE_MIN..TAILSCALE_RANGE_MAX
        } catch (_: Exception) { false }
    }

    // ── SSE: control channel ─────────────────────────────────────────────────

    private fun serveControlStream(session: IHTTPSession): Response {
        val viewId = session.parameters["view"]?.firstOrNull()
            ?: return json(Response.Status.BAD_REQUEST, err("missing_param", "view"))
        val flow = controlFlows.getOrPut(viewId) {
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 32,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }
        val (input, pipe) = newPipe()
        val job = serverScope.launch {
            // Initial hello: scene:full (from cache) then mount_ack.
            registry.get(viewId)?.let { view ->
                sceneCache[viewId]?.let { cached ->
                    writeEvent(pipe, "scene:full", sceneFullPayload(cached))
                }
                writeEvent(pipe, "mount_ack", mountAckPayload(view))
            }
            flow.collect { event ->
                writeEvent(pipe, event.optString("type", "event"), event)
            }
        }
        cleanupOnClose(pipe, job)
        return sseResponse(input)
    }

    // ── SSE: pose channel ────────────────────────────────────────────────────

    private fun servePoseStream(session: IHTTPSession): Response {
        val (input, pipe) = newPipe()
        val job = serverScope.launch {
            posePublisher.subscribe().collect { event ->
                writeEvent(pipe, "pose", event)
            }
        }
        cleanupOnClose(pipe, job)
        return sseResponse(input)
    }

    // ── POST: register ───────────────────────────────────────────────────────

    private fun handleRegister(session: IHTTPSession): Response {
        val body = readJson(session) ?: return bad("invalid_json")
        val id   = body.optString("id").ifBlank { return bad("missing_field", "id") }
        val name = body.optString("name").ifBlank { return bad("missing_field", "name") }
        val spec = body.optString("spec_version").ifBlank { return bad("missing_field", "spec_version") }
        if (spec != "1") return bad("unsupported_spec_version", "got $spec, expected 1")

        val streamUrl    = body.optString("stream_url").ifBlank { return bad("missing_field", "stream_url") }
        val streamOrigin = body.optString("stream_origin", "").ifBlank { null }
        val manifestUrl  = body.optString("manifest_url", "")
        val aspect       = body.optString("aspect_ratio", "16:10")
        val minTextPx    = body.optInt("min_text_px", 22)
        val maxFocus     = body.optInt("max_focus_targets", 7)
        val palette      = body.optJSONArray("palette")?.let { arr ->
            (0 until arr.length()).mapNotNull { PaletteEnum.fromWire(arr.optString(it)) }
        } ?: PaletteEnum.entries.toList()
        val capabilities = body.optJSONArray("capabilities")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.toSet()
        } ?: emptySet()
        val voice = body.optJSONArray("voice_commands")?.let { arr ->
            (0 until arr.length()).mapNotNull { idx ->
                val v = arr.optJSONObject(idx) ?: return@mapNotNull null
                val intent  = v.optString("intent").ifBlank { return@mapNotNull null }
                val phrases = v.optJSONArray("phrase")?.let { p ->
                    (0 until p.length()).map { p.optString(it) }
                } ?: emptyList()
                AppRegistry.RegisteredView.VoiceCommand(intent, phrases)
            }
        } ?: emptyList()

        val view = AppRegistry.RegisteredView(
            id               = id,
            name             = name,
            specVersion      = spec,
            manifestUrl      = manifestUrl,
            streamUrl        = streamUrl,
            streamOrigin     = streamOrigin,
            aspectRatio      = aspect,
            paletteWhitelist = palette,
            minTextPx        = minTextPx,
            maxFocusTargets  = maxFocus,
            voiceCommands    = voice,
            capabilities     = capabilities,
        )
        registry.register(view)
        return json(Response.Status.OK, JSONObject().apply {
            put("ok", true)
            put("id", id)
            put("spec_version", spec)
        })
    }

    // ── POST: patch ──────────────────────────────────────────────────────────

    private fun handlePatch(session: IHTTPSession): Response {
        val body = readJson(session) ?: return bad("invalid_json")
        val viewId = body.optString("view").ifBlank { return bad("missing_field", "view") }
        val ops = body.optJSONArray("ops") ?: return bad("missing_field", "ops")

        val parsed = try { parseOps(ops) }
        catch (e: IllegalArgumentException) { return bad("patch_path_invalid", e.message ?: "bad ops") }

        val current = sceneCache[viewId] ?: Scene(
            root = SceneJson.parseNode(
                JSONObject().put("type", "Box").put("id", "root")
            ),
            seq  = 0,
        )
        val next = try {
            ScenePatchApplier.apply(current, parsed)
        } catch (e: ScenePatchApplier.PatchException) {
            // §13.2: push host:error back to the view over the control channel.
            pushControlEvent(viewId, JSONObject().apply {
                put("type", "host:error")
                put("error_type", e.errorType)
                put("message", e.message)
            })
            return bad(e.errorType, e.message ?: "patch failed")
        } catch (e: SceneJson.SceneParseException) {
            pushControlEvent(viewId, JSONObject().apply {
                put("type", "host:error")
                put("error_type", e.errorType)
                put("message", e.message)
            })
            return bad(e.errorType, e.message ?: "parse failed")
        }
        sceneCache[viewId] = next
        if (registry.isActive(viewId)) {
            _sceneFlow.tryEmit(next)
        }
        return json(Response.Status.OK, JSONObject().apply {
            put("ok", true); put("seq", next.seq)
        })
    }

    private fun parseOps(arr: JSONArray): List<PatchOp> {
        val out = mutableListOf<PatchOp>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("op[$i] not object")
            val op   = o.optString("op").ifBlank { throw IllegalArgumentException("op[$i].op missing") }
            val path = o.optString("path").ifBlank { throw IllegalArgumentException("op[$i].path missing") }
            out += when (op) {
                "set"    -> PatchOp.Set(path, o.opt("value"))
                "insert" -> PatchOp.Insert(path, o.opt("value"))
                "remove" -> PatchOp.Remove(path)
                else     -> throw IllegalArgumentException("unknown op '$op' at [$i]")
            }
        }
        return out
    }

    // ── POST: intent_result ──────────────────────────────────────────────────

    private fun handleIntentResult(session: IHTTPSession): Response {
        val body = readJson(session) ?: return bad("invalid_json")
        // Forwarded verbatim; CommandDispatcher handlers can subscribe if they care.
        Log.d(TAG, "intent_result: $body")
        return okAck()
    }

    // ── POST: speak ──────────────────────────────────────────────────────────

    private fun handleSpeak(session: IHTTPSession): Response {
        val body = readJson(session) ?: return bad("invalid_json")
        val text = body.optString("text")
        val priority = body.optString("priority", "normal")
        // TODO: wire to Skippy TTS pipeline once it exists. For now log.
        Log.i(TAG, "speak[$priority]: $text")
        return okAck()
    }

    private fun handleLog(session: IHTTPSession): Response {
        val body = readJson(session) ?: return bad("invalid_json")
        Log.i(TAG, "view:log: $body")
        return okAck()
    }

    private fun handleViewError(session: IHTTPSession): Response {
        val body = readJson(session) ?: return bad("invalid_json")
        Log.w(TAG, "view:error: $body")
        return okAck()
    }

    private fun handleRequestUnmount(session: IHTTPSession): Response {
        val body = readJson(session) ?: return bad("invalid_json")
        val viewId = body.optString("view").ifBlank { return bad("missing_field", "view") }
        if (registry.isActive(viewId)) registry.deactivate()
        _sceneFlow.tryEmit(null)
        return okAck()
    }

    private fun handleRequestFullScene(session: IHTTPSession): Response {
        val body = readJson(session) ?: return bad("invalid_json")
        val viewId = body.optString("view").ifBlank { return bad("missing_field", "view") }
        // Drop cached scene so the next scene:patch over the control channel
        // forces the view to resend scene:full via its own producer.
        sceneCache.remove(viewId)
        pushControlEvent(viewId, JSONObject().apply {
            put("type", "request_full_scene")
        })
        return okAck()
    }

    // ── Control-channel push ─────────────────────────────────────────────────

    /** Fire a message onto the view's control SSE. No-op if no subscriber. */
    fun pushControlEvent(viewId: String, event: JSONObject) {
        val withSeq = event.apply {
            if (!has("seq")) put("seq", ++controlSeq)
        }
        controlFlows[viewId]?.tryEmit(withSeq)
    }

    /** Activate a view. Fires before_unmount → 500ms grace → swap. */
    fun activate(viewId: String) {
        registry.active?.let { prior ->
            pushControlEvent(prior.view.id, JSONObject().apply {
                put("type", "before_unmount")
                put("grace_ms", 500)
            })
            // We don't wait here — grace is the *view's* responsibility. Host
            // moves on after 500ms if the view hasn't acked; PassthroughHost's
            // coroutine is expected to enforce the timer. Kept simple in v1.
        }
        val mount = registry.activate(viewId) ?: return
        sceneCache[viewId]?.let { _sceneFlow.tryEmit(it) }
        pushControlEvent(viewId, mountAckPayload(mount.view))
        mount.view.voiceCommands.forEach { vc ->
            dispatcher.register(
                CommandDispatcher.Intent(
                    id          = "${mount.view.id}.${vc.intent}",
                    phrases     = vc.phrases,
                    description = "${mount.view.name}: ${vc.intent}",
                    handler     = { args ->
                        pushControlEvent(mount.view.id, JSONObject().apply {
                            put("type", "intent")
                            put("intent", vc.intent)
                            if (args.isNotEmpty()) put("args", args)
                        })
                    },
                )
            )
        }
    }

    fun deactivateActive() {
        val prior = registry.active ?: return
        pushControlEvent(prior.view.id, JSONObject().apply {
            put("type", "before_unmount")
            put("grace_ms", 500)
        })
        prior.view.voiceCommands.forEach { vc ->
            dispatcher.unregister("${prior.view.id}.${vc.intent}")
        }
        registry.deactivate()
        _sceneFlow.tryEmit(null)
    }

    // ── SSE plumbing ─────────────────────────────────────────────────────────

    private fun newPipe(): Pair<InputStream, PipedOutputStream> {
        val input  = PipedInputStream(64 * 1024)
        val output = PipedOutputStream(input)
        return input to output
    }

    private fun sseResponse(input: InputStream): Response {
        val r = newChunkedResponse(Response.Status.OK, "text/event-stream", input)
        r.addHeader("Cache-Control", "no-cache")
        r.addHeader("Connection", "keep-alive")
        r.addHeader("X-Accel-Buffering", "no")
        return r
    }

    private fun writeEvent(pipe: PipedOutputStream, type: String, payload: JSONObject) {
        val bytes = "event: $type\ndata: $payload\n\n".toByteArray(Charsets.UTF_8)
        try {
            synchronized(pipe) {
                pipe.write(bytes)
                pipe.flush()
            }
        } catch (e: IOException) {
            // Client closed — the collector will learn on next write and the
            // cleanup job cancels. Swallow here.
        }
    }

    private fun cleanupOnClose(pipe: PipedOutputStream, job: Job) {
        // When the collector coroutine exits (flow completes or we cancel),
        // close the pipe so NanoHTTPD's Response reader gets EOF.
        job.invokeOnCompletion {
            try { pipe.close() } catch (_: IOException) {}
        }
    }

    // ── JSON utils ───────────────────────────────────────────────────────────

    private fun readJson(session: IHTTPSession): JSONObject? {
        return try {
            val files = hashMapOf<String, String>()
            session.parseBody(files)
            val raw = files["postData"] ?: session.queryParameterString ?: return null
            JSONObject(raw)
        } catch (_: Exception) { null }
    }

    private fun json(status: Response.Status, payload: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", payload.toString())

    private fun err(type: String, msg: String) = JSONObject().apply {
        put("ok", false); put("error_type", type); put("message", msg)
    }

    private fun bad(type: String, msg: String = type) =
        json(Response.Status.BAD_REQUEST, err(type, msg))

    private fun okAck() = json(Response.Status.OK, JSONObject().put("ok", true))

    // ── Handshake payloads ───────────────────────────────────────────────────

    private fun mountAckPayload(view: AppRegistry.RegisteredView) = JSONObject().apply {
        put("type", "mount_ack")
        put("seq", ++controlSeq)
        put("viewport_px", JSONArray().apply { put(1540); put(960) })
        put("spec_version", view.specVersion)
        put("palette_enum", JSONArray().apply {
            view.paletteWhitelist.forEach { put(it.wireName) }
        })
        put("context_mode", "static")   // placeholder; wire to ContextEngine later
    }

    private fun sceneFullPayload(scene: Scene) = JSONObject().apply {
        put("type", "scene:full")
        put("seq", scene.seq)
        put("root", SceneJson.toJson(scene.root))
    }
}
