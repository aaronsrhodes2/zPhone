package local.skippy.droid.compositor.passthrough

import android.net.Uri

/**
 * Manifest-level validation of a parsed [Scene].
 *
 * Structural validation (unknown node type, off-palette, missing required
 * props) already happened at parse time in [SceneJson]. This layer enforces
 * the *contract* rules from PROTOCOL §2 & the Standard §2:
 *
 *   - Text.sizePx >= manifest.min_text_px (Standard §2 rule 5)
 *   - Focus-target count <= manifest.max_focus_targets (Standard §2 rule 7)
 *   - FrameStream.url origin matches declared stream_origin OR loopback
 *     (PROTOCOL §16.7 — prevents third-party origin exfiltration)
 *
 * Violations are collected, not thrown. The host decides whether any
 * violation blocks mount (severity [Severity.Fatal]) or just logs
 * ([Severity.Warn]). In v1 we're forgiving — most violations warn, only
 * origin violations are fatal.
 */
object SceneValidator {

    enum class Severity { Warn, Fatal }

    data class Violation(
        val severity: Severity,
        val errorType: String,
        val message: String,
        val nodeId: String? = null,
    )

    data class Contract(
        val minTextPx: Int,
        val maxFocusTargets: Int,
        /** Origin the view declared at register-time. Null = loopback only allowed. */
        val streamOrigin: String?,
    )

    fun validate(scene: Scene, contract: Contract): List<Violation> {
        val acc = mutableListOf<Violation>()
        walk(scene.root, contract, acc, focusBudget = FocusBudget(0))
        return acc
    }

    // ── Walking ──────────────────────────────────────────────────────────────

    /** Mutable counter the walk shares to accumulate focus-target usage. */
    private class FocusBudget(var count: Int)

    private fun walk(
        node: SceneNode,
        c: Contract,
        acc: MutableList<Violation>,
        focusBudget: FocusBudget,
    ) {
        when (node) {
            is SceneNode.Text -> {
                if (node.sizePx < c.minTextPx && !node.sizeJustify) {
                    acc += Violation(
                        Severity.Warn, "min_text_px_violation",
                        "Text '${node.id}' size ${node.sizePx}px < min ${c.minTextPx}px",
                        node.id,
                    )
                }
            }
            is SceneNode.FrameStream -> {
                if (!originAllowed(node.url, c.streamOrigin)) {
                    acc += Violation(
                        Severity.Fatal, "frame_stream_origin_forbidden",
                        "FrameStream '${node.id}' url origin not allowed (must match stream_origin or be loopback): ${node.url}",
                        node.id,
                    )
                }
            }
            is SceneNode.FocusTarget -> {
                focusBudget.count += 1
                if (focusBudget.count > c.maxFocusTargets) {
                    acc += Violation(
                        Severity.Warn, "focus_budget_exceeded",
                        "focus target '${node.focusId}' exceeds max_focus_targets=${c.maxFocusTargets}",
                        node.id,
                    )
                }
                walk(node.child, c, acc, focusBudget)
                return
            }
            is SceneNode.Button -> {
                focusBudget.count += 1
                if (focusBudget.count > c.maxFocusTargets) {
                    acc += Violation(
                        Severity.Warn, "focus_budget_exceeded",
                        "button focus '${node.focusId}' exceeds max_focus_targets=${c.maxFocusTargets}",
                        node.id,
                    )
                }
            }
            else -> { /* other leaf types: nothing contract-level to check */ }
        }
        for (kid in SceneJson.childrenOf(node)) walk(kid, c, acc, focusBudget)
    }

    // ── Origin check ─────────────────────────────────────────────────────────

    /**
     * An MJPEG URL is allowed if:
     *   - it's a loopback URL (127.0.0.1 or localhost), OR
     *   - its scheme+host+port exactly match the view's [streamOrigin].
     *
     * Fall-through on any parse failure: reject (defensive).
     */
    private fun originAllowed(url: String, streamOrigin: String?): Boolean {
        val u = try { Uri.parse(url) } catch (_: Exception) { return false }
        if (u.host == null) return false
        if (u.host == "127.0.0.1" || u.host == "localhost" || u.host == "::1") return true
        val declared = streamOrigin?.let { try { Uri.parse(it) } catch (_: Exception) { null } }
            ?: return false
        return u.scheme == declared.scheme &&
               u.host   == declared.host   &&
               u.port   == declared.port
    }
}
