package com.skippy.droid.layers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker

/**
 * Layer 2 — camera passthrough engine.
 *
 * Owns the Camera2 lifecycle and manages two competing consumers:
 * the phone screen (dev preview) and the glasses display (real passthrough).
 * Glasses always take priority. When glasses disconnect, the phone reclaims the camera.
 *
 * All public methods are safe to call from the main thread at any time.
 */
class PassthroughCamera(private val context: Context) {

    private var phoneSurface: Surface? = null
    private var glassesSurface: Surface? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // ── Phone surface ─────────────────────────────────────────────────────────

    /** Called when the phone screen's TextureView becomes available. */
    fun attachPhone(surface: Surface) {
        phoneSurface = surface
        // Only open on phone if glasses haven't already claimed the camera
        if (glassesSurface == null) startOn(surface)
    }

    /** Called when the phone screen's TextureView is destroyed. */
    fun detachPhone() {
        phoneSurface = null
        // Glasses may still be active — don't stop the camera
        if (glassesSurface == null) stopCamera()
    }

    // ── Glasses surface ───────────────────────────────────────────────────────

    /** Called when the GlassesPresentation TextureView becomes available. Glasses take priority. */
    fun attachGlasses(surface: Surface) {
        glassesSurface = surface
        startOn(surface)          // steals camera from phone if open
    }

    /** Called when GlassesPresentation is dismissed. Falls back to phone if available. */
    fun detachGlasses() {
        glassesSurface = null
        val fallback = phoneSurface
        if (fallback != null) startOn(fallback)
        else stopCamera()
    }

    // ── Permission ────────────────────────────────────────────────────────────

    /**
     * Call from [android.app.Activity.onRequestPermissionsResult] when CAMERA is granted.
     * Opens on whichever surface is already registered.
     */
    fun onPermissionGranted() {
        val target = glassesSurface ?: phoneSurface ?: return
        startOn(target)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun startOn(surface: Surface) {
        if (PermissionChecker.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) != PermissionChecker.PERMISSION_GRANTED
        ) return
        stopCamera()
        openCamera(surface)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(surface: Surface) {
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startPreview(camera, surface)
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close(); cameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close(); cameraDevice = null
            }
        }, null)
    }

    private fun startPreview(camera: CameraDevice, surface: Surface) {
        val request = camera
            .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            .apply { addTarget(surface) }
            .build()

        camera.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(surface)),
                ContextCompat.getMainExecutor(context),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        session.setRepeatingRequest(request, null, null)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }
            )
        )
    }

    private fun stopCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
    }
}

// ── Composable ────────────────────────────────────────────────────────────────

/**
 * Layer 2 — camera passthrough composable.
 *
 * Hosts a [TextureView] and routes surface lifecycle events to [PassthroughCamera]
 * via the provided callbacks. Use distinct callbacks for phone vs glasses so the
 * camera manager knows which surface to fall back to on disconnect.
 *
 * @param onSurfaceAvailable called with the new Surface when the texture is ready
 * @param onSurfaceDestroyed called when the texture is released
 */
@Composable
fun CameraPassthrough(
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        st: SurfaceTexture, width: Int, height: Int
                    ) { onSurfaceAvailable(Surface(st)) }

                    override fun onSurfaceTextureSizeChanged(
                        st: SurfaceTexture, width: Int, height: Int
                    ) {}

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        onSurfaceDestroyed()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
        modifier = modifier
    )
    // Surface lifecycle is managed entirely through onSurfaceTextureDestroyed —
    // no DisposableEffect here to avoid closing a just-opened glasses session when
    // the phone's CameraPassthrough re-enters composition.
}
