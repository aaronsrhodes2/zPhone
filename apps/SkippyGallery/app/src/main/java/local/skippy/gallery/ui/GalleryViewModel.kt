package local.skippy.gallery.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One image entry from the DropShip /dropship/list API.
 *
 * @param fileId     Drive file ID — used to build proxy URLs.
 * @param name       Original filename.
 * @param thumbUrl   Drive thumbnailLink (direct CDN URL, no auth needed). May be null.
 * @param date       ISO 8601 createdTime from Drive.
 */
data class DriveImage(
    val fileId:   String,
    val name:     String,
    val thumbUrl: String?,
    val date:     String,
)

class GalleryViewModel : ViewModel() {

    companion object {
        // Emulator → host: 10.0.2.2. Real S23 via Tailscale: skippy-pc.
        // Keep in sync with SkippyTelClient.kt in SkippyChat.
        const val BASE_URL = "http://10.0.2.2:3003"
        private const val TAG = "GalleryViewModel"
    }

    private val _images  = MutableStateFlow<List<DriveImage>>(emptyList())
    val images: StateFlow<List<DriveImage>> = _images.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error   = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Fetch the latest image list from SkippyTel /dropship/list. */
    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value   = null
            val result = withContext(Dispatchers.IO) { fetchImages() }
            when {
                result == null -> _error.value = "SkippyTel unreachable — is the server running?"
                else           -> _images.value = result
            }
            _loading.value = false
        }
    }

    /** Full-resolution URL for an image (proxied through SkippyTel). */
    fun imageUrl(fileId: String) = "$BASE_URL/dropship/image/$fileId"

    /**
     * Thumbnail URL — use Drive's CDN link directly if available (fast, no
     * round-trip through SkippyTel), otherwise fall back to the proxy.
     */
    fun thumbUrl(image: DriveImage): String =
        image.thumbUrl?.replace("=s220", "=s512") ?: "$BASE_URL/dropship/thumb/${image.fileId}"

    // ── private ────────────────────────────────────────────────────────────

    private fun fetchImages(): List<DriveImage>? {
        return try {
            val url  = URL("$BASE_URL/dropship/list?size=200")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout    = 15_000
            conn.requestMethod  = "GET"

            if (conn.responseCode != 200) {
                Log.w(TAG, "dropship/list returned ${conn.responseCode}")
                return emptyList()
            }

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val arr  = json.getJSONArray("images")

            List(arr.length()) { i ->
                val item = arr.getJSONObject(i)
                DriveImage(
                    fileId   = item.getString("id"),
                    name     = item.optString("name", ""),
                    thumbUrl = item.optString("thumb_url").takeIf { it.isNotBlank() },
                    date     = item.optString("date", ""),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchImages failed: $e")
            null   // null = server unreachable; emptyList = no images
        }
    }
}
