package local.skippy.chat.admin

import android.app.admin.DeviceAdminReceiver
import android.util.Log

/**
 * Minimal DeviceAdminReceiver granting the force-lock policy.
 *
 * Used by [local.skippy.chat.sensors.PocketDetector] to call
 * `DevicePolicyManager.lockNow()` when the proximity sensor confirms
 * the phone has been pocketed.
 *
 * One-time activation (run once after first install):
 *
 *   adb shell dpm set-active-admin local.skippy.chat/.admin.SkippyDeviceAdmin
 *
 * If not activated, pocket-lock is silently skipped — everything else
 * works normally.
 */
class SkippyDeviceAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: android.content.Context, intent: android.content.Intent) {
        Log.i(TAG, "device admin enabled — pocket-lock active")
    }
    override fun onDisabled(context: android.content.Context, intent: android.content.Intent) {
        Log.i(TAG, "device admin disabled — pocket-lock inactive")
    }
    private companion object { const val TAG = "Local.Skippy.DevAdmin" }
}
