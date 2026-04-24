package local.skippy.droid.features.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Android [NotificationListenerService] implementation.
 *
 * Needs the `BIND_NOTIFICATION_LISTENER_SERVICE` permission in the manifest
 * (already present) and a `<service>` declaration with the
 * `android.service.notification.NotificationListenerService` intent filter
 * (added in the same AndroidManifest.xml edit that introduces this file).
 *
 * The Captain must also enable the listener once per install via
 * `Settings → Notifications → Device & app notifications → SkippyDroid`.
 * Without that grant the service starts but receives no events.
 *
 * ── What gets surfaced to the HUD ─────────────────────────────────────────
 * Filters:
 *   - Skip ongoing notifications (media controls, downloads, foreground
 *     services) — they'd pin the toast forever and aren't "arrival" events.
 *   - Skip group summaries — the individual child will come through on its
 *     own, so publishing the summary duplicates.
 *   - Skip our own package — we don't want a feedback loop.
 *
 * Everything else gets packaged into a [NotificationBus.Arrival] and
 * published; the [NotificationModule] picks it up and paints a 3.5-second
 * TopCenter toast via [local.skippy.droid.compositor.SizeJustifiedText].
 */
class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "Local.Skippy.NotifListener"
        private const val OWN_PKG = "local.skippy.droid"
    }

    override fun onListenerConnected() {
        Log.d(TAG, "listener connected")
    }

    override fun onListenerDisconnected() {
        Log.d(TAG, "listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        if (!shouldSurface(s)) return

        val extras = s.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text  = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        if (title.isEmpty() && text.isEmpty()) return

        NotificationBus.publish(
            NotificationBus.Arrival(
                pkg        = s.packageName ?: "",
                title      = title,
                text       = text,
                postedAtMs = s.postTime,
            )
        )
    }

    private fun shouldSurface(s: StatusBarNotification): Boolean {
        val n = s.notification ?: return false
        if (s.packageName == OWN_PKG) return false
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return false
        if ((n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return false
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        return true
    }
}
