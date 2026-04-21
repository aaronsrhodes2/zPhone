package com.skippy.droid.layers

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Layer 1 extension — VITURE glasses IMU.
 *
 * The VITURE Luma Ultra has an on-glasses 6DoF chip that reports head pose
 * (yaw / pitch / roll) independently of the phone.  Yaw is the compass-equivalent:
 * as the Captain turns their head, [headingDegrees] tracks the gaze direction.
 *
 * This is CRITICAL for direction-dot AR accuracy.  The phone's magnetometer tells
 * you where the phone is pointing (pocket, hand, bag) — not where the eyes are.
 * The glasses tell you where the eyes are.  We prefer glasses heading always.
 *
 * ── Integration TODO (fill in when VITURE SDK PDF is in hand) ────────────────
 *
 * 1. Add the VITURE SDK AAR to libs/ and declare it in build.gradle.kts:
 *       implementation(files("libs/viture-xr-sdk.aar"))
 *
 * 2. In start(), initialize the SDK and register the IMU listener:
 *       VitureSDK.init(context)   // or whatever the init call is
 *       VitureSDK.setImuListener { imuData ->
 *           // Euler angles variant:
 *           headingDegrees = imuData.yaw.toDouble()   // [0, 360) or [-180, 180]
 *           isConnected    = true
 *           // Quaternion variant — extract yaw:
 *           // val yaw = Math.toDegrees(atan2(
 *           //     2*(q.w*q.z + q.x*q.y), 1 - 2*(q.y*q.y + q.z*q.z)))
 *           // headingDegrees = (yaw + 360) % 360
 *       }
 *
 * 3. In stop(), clear the listener:
 *       VitureSDK.setImuListener(null)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * [DeviceLayer.headingDegrees] delegates to this when [isConnected] is true,
 * falling back to the phone magnetometer otherwise.
 */
class GlassesLayer {

    /** True once the VITURE SDK has delivered its first IMU packet. */
    var isConnected: Boolean by mutableStateOf(false)
        private set

    /**
     * Glasses yaw in degrees [0, 360).
     * Null when the glasses IMU is not available (SDK not integrated yet, or not connected).
     */
    var headingDegrees: Double? by mutableStateOf(null)
        private set

    fun start() {
        // ── TODO: VITURE SDK init + IMU listener registration ────────────────
        // See class-level KDoc above for the integration checklist.
        // Until the SDK is wired, this is a no-op and headingDegrees stays null.
    }

    fun stop() {
        // ── TODO: VitureSDK.setImuListener(null) ────────────────────────────
        headingDegrees = null
        isConnected    = false
    }
}
