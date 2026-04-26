package local.skippy.chat.dropship

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts DropShipWatcher automatically after device boot.
 * Declared in AndroidManifest with RECEIVE_BOOT_COMPLETED permission.
 */
class DropShipBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            DropShipWatcher.start(context)
        }
    }
}
