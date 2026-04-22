package com.skippy.droid.compositor.passthrough

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of passthrough views — PROTOCOL §2 (discovery) + §8 (multi-view).
 *
 * A view POSTs `/passthrough/register` once per boot; that payload lands in
 * [register] and becomes a [RegisteredView] here. The Captain later activates
 * one by voice ("open DJ block planner") or via dev-mode UI; that triggers
 * [activate].
 *
 * §8 policy: **one active view at a time.** [activate] tears down any prior
 * mount (fires `before_unmount` with a 500ms grace window before hard-mount
 * of the new view).
 *
 * This class is pure state. Transport hooks (`before_unmount` dispatch, SSE
 * session setup, pose subscription) live in [PassthroughServer] and
 * [PassthroughHost]. The registry only knows what's mounted.
 */
class AppRegistry {

    // ── Data ─────────────────────────────────────────────────────────────────

    /**
     * Parsed view manifest + registration metadata. Mirrors PROTOCOL §3 +
     * STANDARD §2. Only the fields the host actually enforces or routes on
     * are surfaced; unknown fields are forwarded verbatim via [extras].
     */
    data class RegisteredView(
        val id: String,
        val name: String,
        val specVersion: String,
        val manifestUrl: String,
        /** URL the host opens for SSE control events. Usually same-origin as register call. */
        val streamUrl: String,
        /** Origin (scheme://host:port) used for FrameStream URL validation (§16.7). */
        val streamOrigin: String?,
        val aspectRatio: String,              // "16:10"
        val paletteWhitelist: List<PaletteEnum>,
        val minTextPx: Int,
        val maxFocusTargets: Int,
        val voiceCommands: List<VoiceCommand>,
        val capabilities: Set<String>,        // e.g. {"speak", "focus_targets"}
        val registeredAtMs: Long = System.currentTimeMillis(),
        val extras: Map<String, Any?> = emptyMap(),
    ) {
        data class VoiceCommand(
            val intent: String,
            val phrases: List<String>,
        )
    }

    /** The currently mounted view (at most one per §8). */
    data class ActiveMount(
        val view: RegisteredView,
        /** Monotonic session key — survives activate/deactivate pairs. */
        val sessionId: String = UUID.randomUUID().toString(),
        val mountedAtMs: Long = System.currentTimeMillis(),
    )

    // ── Observable state ─────────────────────────────────────────────────────

    /** All known views. Thread-safe for the /register endpoint hitting it. */
    private val store = ConcurrentHashMap<String, RegisteredView>()

    /** Compose-observable. Compositor's Viewport renderer reads this directly. */
    var active: ActiveMount? by mutableStateOf(null)
        private set

    // ── Registration ─────────────────────────────────────────────────────────

    /**
     * Upsert a view. The most recent registration wins — views may re-register
     * to refresh manifest contents without restarting the host.
     *
     * Returns the stored [RegisteredView] so the server can 200-respond with
     * the normalized record.
     */
    fun register(view: RegisteredView): RegisteredView {
        store[view.id] = view
        return view
    }

    fun unregister(id: String): RegisteredView? {
        if (active?.view?.id == id) active = null
        return store.remove(id)
    }

    fun get(id: String): RegisteredView? = store[id]
    fun list(): List<RegisteredView> = store.values.sortedBy { it.name }

    // ── Activation ───────────────────────────────────────────────────────────

    /**
     * Activate [id]. If another view is currently mounted, the caller
     * (PassthroughServer) is responsible for firing `before_unmount` on the
     * prior session *before* calling this — we only swap state.
     *
     * Returns the new [ActiveMount] or `null` if no such view is registered.
     */
    fun activate(id: String): ActiveMount? {
        val view = store[id] ?: return null
        val mount = ActiveMount(view = view)
        active = mount
        return mount
    }

    fun deactivate(): ActiveMount? {
        val prior = active
        active = null
        return prior
    }

    /** True if [id] is the currently mounted view. */
    fun isActive(id: String): Boolean = active?.view?.id == id
}
