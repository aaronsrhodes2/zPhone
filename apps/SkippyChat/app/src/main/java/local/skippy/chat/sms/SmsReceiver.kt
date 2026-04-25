package local.skippy.chat.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * BroadcastReceiver for incoming SMS.
 *
 * Declared in AndroidManifest.xml with the SMS_RECEIVED intent-filter.
 * Requires RECEIVE_SMS permission.
 *
 * Multi-part messages (long SMS split across segments) are reassembled
 * by grouping parts that share the same originating address.
 *
 * Incoming messages are emitted on [incoming] — a SharedFlow with a
 * 32-slot replay buffer so messages are never lost during recomposition.
 * ChatScreen collects this flow in a LaunchedEffect and calls
 * viewModel.receiveSms(sender, body) on each emission.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Local.Skippy.SmsRx"

        data class IncomingSms(
            val sender: String,
            val body:   String,
        )

        private val _incoming = MutableSharedFlow<IncomingSms>(extraBufferCapacity = 32)

        /** Collect this in ChatScreen to feed incoming SMS into the feed. */
        val incoming: SharedFlow<IncomingSms> = _incoming.asSharedFlow()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group message parts by originating address (multi-part SMS reassembly).
        val bySender = linkedMapOf<String, StringBuilder>()
        for (msg in messages) {
            val sender = msg.originatingAddress ?: "unknown"
            bySender.getOrPut(sender) { StringBuilder() }.append(msg.messageBody)
        }

        for ((sender, body) in bySender) {
            Log.d(TAG, "SMS received from $sender (${body.length} chars)")
            _incoming.tryEmit(IncomingSms(sender = sender, body = body.toString()))
        }
    }
}
