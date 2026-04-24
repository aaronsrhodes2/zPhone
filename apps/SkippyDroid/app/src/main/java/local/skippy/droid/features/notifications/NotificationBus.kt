package local.skippy.droid.features.notifications

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Shared singleton that carries incoming notifications from the
 * [NotificationListener] service into the Compose world that
 * [NotificationModule] reads.
 *
 * ── Why a singleton rather than a per-activity bus ────────────────────────
 * [android.service.notification.NotificationListenerService] is instantiated
 * by the system, not by [local.skippy.droid.MainActivity]. We have no
 * constructor injection path — the service discovers the bus via static
 * reference.
 *
 * ── What's in the bus ─────────────────────────────────────────────────────
 * A short-lived "current notification" that the module fades out after
 * [TOAST_DURATION_MS]. We don't maintain a history queue — the HUD shows
 * the most recent arrival for a few seconds and then yields the slot.
 * Phase 2 may add a backlog for a notification-panel view.
 *
 * ── Thread safety ─────────────────────────────────────────────────────────
 * [latest] is backed by [mutableStateOf]; writes from the listener service
 * thread are safe (Compose snapshot reads/writes are lock-protected). The
 * module reads it from a composable which will recompose automatically.
 */
object NotificationBus {

    /**
     * A single incoming notification, distilled for HUD consumption.
     *
     * @property pkg        Source package (e.g. `"com.google.android.apps.messaging"`).
     *                      Not surfaced in Phase 1 UI but useful for per-app routing later.
     * @property title      Notification title (contact name, app name). Empty if absent.
     * @property text       Notification body. Empty if absent.
     * @property postedAtMs Wall-clock of arrival — used to expire the toast.
     */
    data class Arrival(
        val pkg: String,
        val title: String,
        val text: String,
        val postedAtMs: Long,
    ) {
        /** One-line form for the TopCenter toast: "Title — body". */
        val oneLine: String
            get() = when {
                title.isNotEmpty() && text.isNotEmpty() -> "$title — $text"
                title.isNotEmpty()                      -> title
                text.isNotEmpty()                       -> text
                else                                     -> pkg
            }
    }

    /** Duration a toast stays visible before [NotificationModule] fades it. */
    const val TOAST_DURATION_MS = 3_500L

    /** Most recent arrival. `null` when nothing's in flight. */
    var latest: Arrival? by mutableStateOf(null)
        private set

    /**
     * Publish a new arrival. Called from [NotificationListener.onNotificationPosted].
     * Replaces any in-flight toast immediately.
     */
    fun publish(a: Arrival) {
        latest = a
    }

    /** Clear the current toast (used by the module when its timer expires). */
    fun clear() {
        latest = null
    }
}
