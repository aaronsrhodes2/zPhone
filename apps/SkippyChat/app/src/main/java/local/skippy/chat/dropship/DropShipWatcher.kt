package local.skippy.chat.dropship

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Foreground service that watches MediaStore for new photos and auto-uploads
 * them to DropShip (SkippyTel /dropship/upload).
 *
 * Any image added to the device — camera shot, screenshot, download — is
 * pushed to Google Drive Images/ within seconds, making it instantly
 * retrievable via "Skippy, show me that picture we just took."
 *
 * Watermarking: stores the `date_added` unix-second of the last successfully
 * uploaded image in SharedPreferences. On first run, watermark is set to NOW
 * so we don't bulk-upload the entire camera roll.
 *
 * Start via [DropShipWatcher.start]. Restarts automatically on boot via
 * [DropShipBootReceiver].
 */
class DropShipWatcher : Service() {

    companion object {
        private const val TAG          = "DropShipWatcher"
        private const val CHANNEL_ID   = "dropship_watcher"
        private const val NOTIF_ID     = 7331
        private const val PREFS_NAME   = "skippy_prefs"
        private const val KEY_LAST_TS  = "dropship_last_image_ts"

        fun start(context: Context) {
            val intent = Intent(context, DropShipWatcher::class.java)
            context.startForegroundService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ContentObserver? = null

    private val prefs get() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    private val skippyTelUrl get() =
        prefs.getString("skippytel_url", "http://10.0.2.2:3003") ?: "http://10.0.2.2:3003"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Watching for new photos…"))

        // On first-ever run: set watermark to NOW so we don't re-upload
        // the entire camera roll.
        if (!prefs.contains(KEY_LAST_TS)) {
            prefs.edit()
                .putLong(KEY_LAST_TS, System.currentTimeMillis() / 1000L)
                .apply()
            Log.i(TAG, "First run — watermark set to now, existing photos skipped")
        }

        val handler = Handler(Looper.getMainLooper())
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "MediaStore changed — checking for new images")
                scope.launch { uploadNewImages() }
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            /* notifyForDescendants= */ true,
            observer!!,
        )
        Log.i(TAG, "DropShipWatcher started — watching MediaStore")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY   // restart automatically if killed

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        scope.cancel()
        Log.i(TAG, "DropShipWatcher stopped")
        super.onDestroy()
    }

    // ── Upload logic ──────────────────────────────────────────────────────

    /** Upload one image URI to DropShip; advance watermark on success. */
    private fun uploadImage(imageUri: Uri, name: String, mime: String, dateAdded: Long) {
        try {
            val bytes = contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
            if (bytes == null) {
                Log.w(TAG, "Couldn't open $name — skipping")
                return
            }
            uploadToDropShip(name, mime, bytes)
            prefs.edit().putLong(KEY_LAST_TS, dateAdded).apply()
            Log.i(TAG, "DropShip: uploaded $name (${bytes.size / 1024} KB)")
            updateNotification("Uploaded $name")
        } catch (e: Exception) {
            Log.e(TAG, "DropShip upload failed for $name: $e")
            // Don't advance watermark — retry on next observer callback
        }
    }

    /**
     * Query MediaStore for images added since the last watermark and upload
     * each to DropShip. Updates the watermark after each successful upload
     * so a mid-batch crash doesn't re-upload already-synced files.
     */
    private suspend fun uploadNewImages() {
        val lastTs = prefs.getLong(KEY_LAST_TS, 0L)

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val selection     = "${MediaStore.Images.Media.DATE_ADDED} > ?"
        val selectionArgs = arrayOf(lastTs.toString())
        val sortOrder     = "${MediaStore.Images.Media.DATE_ADDED} ASC"

        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, sortOrder,
        ) ?: return

        cursor.use { c ->
            val idCol   = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (c.moveToNext()) {
                val id       = c.getLong(idCol)
                val name     = c.getString(nameCol) ?: "photo_$id.jpg"
                val mime     = c.getString(mimeCol) ?: "image/jpeg"
                val dateAdded = c.getLong(dateCol)
                val imageUri  = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                )

                uploadImage(imageUri, name, mime, dateAdded)
            }
        }
    }

    private fun uploadToDropShip(name: String, mime: String, bytes: ByteArray) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image", name,
                bytes.toRequestBody(mime.toMediaTypeOrNull()),
            )
            .build()
        val request = Request.Builder()
            .url("$skippyTelUrl/dropship/upload")
            .post(body)
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DropShip")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "DropShip Sync",
                    NotificationManager.IMPORTANCE_MIN,   // no sound/pop
                ).apply { description = "Auto-uploads new photos to DropShip" }
            )
        }
    }
}
