package local.skippy.droid.features.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import local.skippy.droid.R
import local.skippy.droid.features.services.ServiceManifest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Feeds service icons + labels into the ServiceGridWidget's GridView.
 *
 * Each item represents one SkippyTel service with a visible UI
 * (displayMode != "background", available = true).
 *
 * Icons are fetched from SkippyTel's GET /services/<id>/icon endpoint.
 * Falls back to a programmatic colored-initial bitmap if the fetch fails.
 *
 * Click data is packed into a fill-in Intent so the AppWidgetProvider's
 * PendingIntent template can dispatch the right launch action per item.
 */
class ServiceGridRemoteViewsFactory(
    private val context: Context,
    intent: Intent,
) : RemoteViewsService.RemoteViewsFactory {

    companion object {
        private const val TAG = "ServiceGridFactory"

        // Intent extras — written by factory, read by ServiceGridWidget on click
        const val EXTRA_SERVICE_ID        = "skippy_service_id"
        const val EXTRA_COMPANION_APP     = "skippy_companion_app"
        const val EXTRA_BASE_URL          = "skippy_base_url"
        const val EXTRA_DISPLAY_MODE      = "skippy_display_mode"
    }

    private val skippyTelUrl: String =
        context.getSharedPreferences("skippy_prefs", Context.MODE_PRIVATE)
            .getString("skippytel_url", "http://10.0.2.2:3003") ?: "http://10.0.2.2:3003"

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Snapshot of services with UIs, refreshed in onDataSetChanged()
    private var items: List<ServiceManifest> = emptyList()
    // Icon cache: service id → bitmap
    private val iconCache = mutableMapOf<String, Bitmap>()

    // ── RemoteViewsFactory lifecycle ───────────────────────────────────────

    override fun onCreate() { /* no-op */ }

    override fun onDataSetChanged() {
        items = fetchVisibleServices()
        // Pre-fetch icons for all items so getViewAt() is fast
        items.forEach { svc ->
            if (!iconCache.containsKey(svc.id)) {
                iconCache[svc.id] = fetchIcon(svc.id) ?: fallbackIcon(svc)
            }
        }
        Log.i(TAG, "Updated: ${items.size} visible services")
    }

    override fun onDestroy() {
        iconCache.values.forEach { it.recycle() }
        iconCache.clear()
    }

    override fun getCount(): Int = items.size
    override fun hasStableIds(): Boolean = true
    override fun getItemId(position: Int): Long = items.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
    override fun getViewTypeCount(): Int = 1
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val svc = items.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_service_item)

        val views = RemoteViews(context.packageName, R.layout.widget_service_item)
        views.setTextViewText(R.id.service_name, svc.name)

        val icon = iconCache[svc.id] ?: fallbackIcon(svc)
        views.setImageViewBitmap(R.id.service_icon, icon)

        // Fill-in intent carries the data for this specific item.
        // The AppWidgetProvider sets the PendingIntent template on the GridView;
        // when the user taps, Android merges template + fill-in and fires.
        val fillIn = Intent().apply {
            putExtra(EXTRA_SERVICE_ID,    svc.id)
            putExtra(EXTRA_COMPANION_APP, svc.companionApp ?: "")
            putExtra(EXTRA_BASE_URL,      svc.baseUrl      ?: "")
            putExtra(EXTRA_DISPLAY_MODE,  svc.displayMode)
        }
        views.setOnClickFillInIntent(R.id.service_icon, fillIn)
        views.setOnClickFillInIntent(R.id.service_name,  fillIn)

        return views
    }

    // ── Data fetching ──────────────────────────────────────────────────────

    /** Fetch the service manifest and return services with visible UIs. */
    private fun fetchVisibleServices(): List<ServiceManifest> {
        return try {
            val req = Request.Builder().url("$skippyTelUrl/services").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val root = JSONObject(resp.body?.string() ?: return emptyList())
                val arr  = root.optJSONArray("services") ?: return emptyList()
                (0 until arr.length())
                    .map { ServiceManifest.fromJson(arr.getJSONObject(it)) }
                    .filter { it.available && it.displayMode != "background" }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchVisibleServices failed: $e")
            emptyList()
        }
    }

    /** Fetch the service's AI-generated icon from SkippyTel. Null on failure. */
    private fun fetchIcon(serviceId: String): Bitmap? {
        return try {
            val req = Request.Builder().url("$skippyTelUrl/services/$serviceId/icon").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchIcon($serviceId) failed: $e")
            null
        }
    }

    // ── Fallback icon (no SD / offline) ───────────────────────────────────

    /** Programmatic dark-circle icon with neon initial + accent color per service. */
    private fun fallbackIcon(svc: ServiceManifest): Bitmap {
        val size = 128
        val bmp  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val accent = accentColor(svc.id)

        // Dark fill
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1A2E") }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, bgPaint)

        // Neon ring
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent; style = Paint.Style.STROKE; strokeWidth = 4f
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4, ringPaint)

        // Initial letter
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent; textSize = 52f; typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val yPos = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(svc.id.first().uppercaseChar().toString(), size / 2f, yPos, textPaint)

        return bmp
    }

    private fun accentColor(serviceId: String): Int {
        return when (serviceId) {
            "intent"    -> Color.parseColor("#00FFFF")
            "bilby"     -> Color.parseColor("#AA44FF")
            "dropship"  -> Color.parseColor("#4488FF")
            "image"     -> Color.parseColor("#00FF88")
            "translate" -> Color.parseColor("#FFFF44")
            "ups"       -> Color.parseColor("#FF8800")
            "heybuddy"  -> Color.parseColor("#00FFDD")
            "jellyfin"  -> Color.parseColor("#FFAA00")
            "starmap"   -> Color.parseColor("#2244FF")
            else        -> Color.parseColor("#888888")
        }
    }
}
