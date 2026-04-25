package local.skippy.droid.features.widget

import android.content.Intent
import android.widget.RemoteViewsService

/**
 * Glue service that Android calls to get the RemoteViewsFactory for the
 * ServiceGridWidget's GridView.  Must be exported and bound via
 * BIND_REMOTEVIEWS permission in the manifest.
 */
class ServiceGridWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        ServiceGridRemoteViewsFactory(applicationContext, intent)
}
