package local.skippy.chat.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Enforces the zPhone audio doctrine:
 *
 *   1. **External output connected** (Viture glasses via USB-C, Bluetooth A2DP,
 *      wired headset/headphones, HDMI) → STREAM_MUSIC locked to 100%.
 *      Voice commands "volume up" / "volume down" adjust STREAM_MUSIC by
 *      [VOLUME_STEP] increments on the 0–max Android scale.
 *
 *   2. **No external output** → STREAM_MUSIC = 0.
 *      Phone speakers are reserved for STREAM_ALARM and STREAM_RING only.
 *      The Captain does not pollute open space with AI chat audio.
 *
 * STREAM_RING and STREAM_ALARM are never touched — alerts can always reach
 * the speaker if needed.
 *
 * Requires MODIFY_AUDIO_SETTINGS (already declared for STT beep suppression).
 * Must be started on the main thread (AudioDeviceCallback uses a Handler).
 */
class AudioRouter(private val context: Context) {

    companion object {
        private const val TAG = "Local.Skippy.AudioRouter"

        /**
         * Number of Android volume steps per voice command.
         * Android media stream is typically 0–15 (15 steps max).
         * 2 steps ≈ 13% per command — noticeable without being jarring.
         */
        const val VOLUME_STEP = 2

        /**
         * Output device types treated as "external" (not the built-in speaker).
         * Viture XR glasses via USB-C show up as TYPE_USB_HEADSET on Android.
         */
        private val EXTERNAL_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_HDMI,
        )
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler   = Handler(Looper.getMainLooper())

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            Log.d(TAG, "devices added: ${addedDevices.map { deviceTypeName(it.type) }}")
            applyPolicy()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            Log.d(TAG, "devices removed: ${removedDevices.map { deviceTypeName(it.type) }}")
            applyPolicy()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    fun start() {
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        applyPolicy()
        Log.d(TAG, "started")
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        Log.d(TAG, "stopped")
    }

    // ── Volume commands ───────────────────────────────────────────────────

    /** Raise media volume by [VOLUME_STEP] steps. No-op if no external output. */
    fun volumeUp() {
        if (!hasExternalOutput()) {
            Log.d(TAG, "volumeUp: no external output — ignoring")
            return
        }
        repeat(VOLUME_STEP) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0,
            )
        }
        logVolume("up")
    }

    /** Lower media volume by [VOLUME_STEP] steps. No-op if no external output. */
    fun volumeDown() {
        if (!hasExternalOutput()) {
            Log.d(TAG, "volumeDown: no external output — ignoring")
            return
        }
        repeat(VOLUME_STEP) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0,
            )
        }
        logVolume("down")
    }

    // ── Policy ────────────────────────────────────────────────────────────

    private fun applyPolicy() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (hasExternalOutput()) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            Log.d(TAG, "external output → STREAM_MUSIC = $max (max)")
        } else {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            Log.d(TAG, "no external output → STREAM_MUSIC = 0 (speaker locked)")
        }
    }

    fun hasExternalOutput(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type in EXTERNAL_OUTPUT_TYPES }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun logVolume(direction: String) {
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        Log.d(TAG, "volume $direction → $cur/$max")
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER    -> "SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADSET      -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES   -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_USB_HEADSET        -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE         -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_ACCESSORY      -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP     -> "BT_A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET        -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER        -> "BLE_SPEAKER"
        AudioDeviceInfo.TYPE_HDMI               -> "HDMI"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE   -> "EARPIECE"
        else                                    -> "TYPE($type)"
    }
}
