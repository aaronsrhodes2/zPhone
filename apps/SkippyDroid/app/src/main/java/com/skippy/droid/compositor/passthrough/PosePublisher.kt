package com.skippy.droid.compositor.passthrough

import com.skippy.droid.layers.GlassesLayer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * Real-time pose publisher — PROTOCOL §16.3.
 *
 * A mounted view with a `FrameStream{ pose: true }` node subscribes to this
 * to render world-pinned frames. Separate SSE channel from the control
 * channel so pose updates never block behind scene patches.
 *
 * Cadence doctrine (§16):
 *   - 60 Hz when glasses IMU is reporting (`GlassesLayer.isConnected`).
 *   - 10 Hz degraded when we only have phone magnetometer / synthetic pose.
 *   - 0 Hz when glasses were connected and then disconnected — we emit a
 *     single `pose_lost` control event (delivered over the *control* SSE
 *     by [PassthroughServer], not this channel) and stop ticking.
 *
 * Producers on the other side of the MJPEG lane use the pose to render each
 * JPEG against a known head pose, then stamp `X-Pose-Seq` in the part
 * headers so Skippy can late-warp or drop stale frames.
 *
 * ── Pose coordinates ─────────────────────────────────────────────────────
 *
 * `pos_m`  : 3-vector in metres, ENU (east, north, up) relative to a session
 *            origin. v1 pins origin at mount time — we're not doing world
 *            anchoring yet, just the nearby Captain-head frame.
 * `quat`   : unit quaternion (w, x, y, z). Identity when we have no IMU.
 *            When we have yaw only (current GlassesLayer state), we build
 *            a yaw-only quaternion about the up axis.
 *
 * When the VITURE SDK is wired and delivers full 6DoF, we replace
 * [snapshotFromGlasses] with a direct SDK passthrough.
 *
 * ── Why callbackFlow ─────────────────────────────────────────────────────
 *
 * [subscribe] returns a cold [Flow] that launches a ticker per collector.
 * Each SSE subscriber gets its own cadence loop so one slow consumer can't
 * throttle another. The launched coroutine cancels automatically when the
 * collector leaves (SSE client disconnect → callbackFlow awaitClose → done).
 */
class PosePublisher(
    private val glasses: GlassesLayer,
    /** Viewport the view was mounted into. Echoed in every pose event. */
    private val viewportPx: Pair<Int, Int> = 1540 to 960,
    /**
     * Horizontal FOV of the display in degrees. Placeholder 45° matches the
     * VITURE Luma Ultra's ~45° horizontal derived from 52° diagonal at 16:10.
     * Overridden once a measured value lands.
     */
    private val fovHDeg: Double = 45.0,
) {

    /** One frame of pose state — what we serialize per tick. */
    data class Pose(
        val seq: Long,
        val tMs: Long,
        val posM: DoubleArray,         // [x, y, z]
        val quat: DoubleArray,         // [w, x, y, z]
        val fovHDeg: Double,
        val viewportPx: Pair<Int, Int>,
        val tracking: Tracking,
    ) {
        enum class Tracking { Live, Degraded, Lost }
    }

    /** Monotonic seq, shared across subscribers (§10: seq per direction per channel). */
    @Volatile private var seq: Long = 0

    /** True once we've ever seen the glasses connected this session — used to decide
     *  whether a disconnection is `pose_lost` (was live, now gone) vs. degraded
     *  (never had glasses). */
    @Volatile private var haveEverConnected: Boolean = false

    /**
     * Cold flow of pose events. Each collector runs its own ticker.
     *
     * The flow emits only "live" events — transitions to `pose_lost` are
     * signalled separately on the control channel because they are a
     * one-shot control notification, not a cadence point.
     */
    fun subscribe(): Flow<JSONObject> = callbackFlow {
        val job = launch {
            while (isActive) {
                val pose = snapshot()
                trySend(encode(pose))
                val periodMs = when (pose.tracking) {
                    Pose.Tracking.Live     -> 1000L / 60   // 60 Hz
                    Pose.Tracking.Degraded -> 100L         // 10 Hz
                    Pose.Tracking.Lost     -> 1000L        // 1 Hz idle
                }
                delay(periodMs)
            }
        }
        awaitClose { job.cancel() }
    }

    /** One-shot snapshot — used by diagnostics & tests without subscribing. */
    fun snapshot(): Pose {
        val now = System.currentTimeMillis()
        val s   = ++seq

        return if (glasses.isConnected && glasses.headingDegrees != null) {
            haveEverConnected = true
            yawOnlyPose(
                seq      = s,
                tMs      = now,
                yawDeg   = glasses.headingDegrees!!,
                tracking = Pose.Tracking.Live,
            )
        } else if (haveEverConnected) {
            // Was live, now gone. Control channel delivers pose_lost; we still
            // stamp the last-known identity so consumers don't see undefined.
            identityPose(seq = s, tMs = now, tracking = Pose.Tracking.Lost)
        } else {
            // No glasses this session — synthetic identity so emulator testing
            // against the frame lane works without hardware.
            identityPose(seq = s, tMs = now, tracking = Pose.Tracking.Degraded)
        }
    }

    // ── Pose construction ────────────────────────────────────────────────────

    private fun yawOnlyPose(
        seq: Long,
        tMs: Long,
        yawDeg: Double,
        tracking: Pose.Tracking,
    ): Pose {
        // Yaw about +Z (up). Quaternion: (cos(θ/2), 0, 0, sin(θ/2)).
        val halfRad = Math.toRadians(yawDeg) / 2.0
        val quat = doubleArrayOf(cos(halfRad), 0.0, 0.0, sin(halfRad))
        return Pose(
            seq        = seq,
            tMs        = tMs,
            posM       = doubleArrayOf(0.0, 0.0, 0.0),
            quat       = quat,
            fovHDeg    = fovHDeg,
            viewportPx = viewportPx,
            tracking   = tracking,
        )
    }

    private fun identityPose(
        seq: Long,
        tMs: Long,
        tracking: Pose.Tracking,
    ) = Pose(
        seq        = seq,
        tMs        = tMs,
        posM       = doubleArrayOf(0.0, 0.0, 0.0),
        quat       = doubleArrayOf(1.0, 0.0, 0.0, 0.0),
        fovHDeg    = fovHDeg,
        viewportPx = viewportPx,
        tracking   = tracking,
    )

    // ── Serialization ────────────────────────────────────────────────────────

    /** JSON shape per PROTOCOL §16.3. `tracking` field is a Skippy-local hint. */
    fun encode(pose: Pose): JSONObject = JSONObject().apply {
        put("type", "pose")
        put("seq", pose.seq)
        put("t_ms", pose.tMs)
        put("pos_m", JSONArray(pose.posM.toTypedArray()))
        put("quat",  JSONArray(pose.quat.toTypedArray()))
        put("fov_h_deg", pose.fovHDeg)
        put("viewport_px", JSONArray().apply {
            put(pose.viewportPx.first)
            put(pose.viewportPx.second)
        })
        put("tracking", pose.tracking.name.lowercase())
    }

    /**
     * Control-channel companion event — emitted by [PassthroughServer] onto
     * the *control* SSE stream once, when the glasses transition from
     * connected → disconnected. Exposed here so the server and publisher
     * stay in sync on the JSON shape.
     */
    fun encodePoseLost(): JSONObject = JSONObject().apply {
        put("type", "pose_lost")
        put("t_ms", System.currentTimeMillis())
    }
}
