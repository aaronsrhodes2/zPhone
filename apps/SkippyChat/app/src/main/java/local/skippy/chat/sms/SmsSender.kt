package local.skippy.chat.sms

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.util.Log

/**
 * Contact resolution + SMS dispatch for SkippyChat.
 *
 * Contact resolution uses ContactsContract.CommonDataKinds.Phone with a
 * two-pass fuzzy match:
 *   1. Exact display-name match (case-insensitive)
 *   2. Contains match in either direction (spoken "Randall" matches "Randall Bronte")
 * Results are sorted A→Z so the first alphabetical match wins on ambiguity.
 *
 * Requires READ_CONTACTS + SEND_SMS permissions (requested in MainActivity).
 */
object SmsSender {

    private const val TAG = "Local.Skippy.SMS"

    data class ContactResult(
        val displayName: String,
        val number:      String,
    )

    sealed class SendResult {
        data class Sent(val contact: ContactResult)    : SendResult()
        data class ContactNotFound(val query: String)  : SendResult()
        data class Failed(val error: String)           : SendResult()
    }

    /**
     * Resolve a spoken [name] to a ContactResult.
     * Returns null if no contacts match.
     * Blocking — call from Dispatchers.IO.
     */
    fun resolveContact(context: Context, name: String): ContactResult? {
        val lower = name.lowercase().trim()
        val uri   = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj  = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val sort = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        val cursor: Cursor? = context.contentResolver.query(uri, proj, null, null, sort)

        cursor?.use { c ->
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx  = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            // Pass 1 — exact match
            while (c.moveToNext()) {
                val display = c.getString(nameIdx) ?: continue
                val number  = c.getString(numIdx)  ?: continue
                if (display.lowercase() == lower) {
                    return ContactResult(display, number)
                }
            }

            // Pass 2 — contains in either direction ("Randall" ↔ "Randall Bronte")
            c.moveToFirst()
            while (c.moveToNext()) {
                val display = c.getString(nameIdx) ?: continue
                val number  = c.getString(numIdx)  ?: continue
                val dl = display.lowercase()
                if (dl.contains(lower) || lower.contains(dl)) {
                    return ContactResult(display, number)
                }
            }
        }
        return null
    }

    /**
     * Resolve contact and send an SMS.
     * Blocking — call from Dispatchers.IO.
     */
    fun send(context: Context, recipientName: String, body: String): SendResult {
        val contact = resolveContact(context, recipientName)
            ?: return SendResult.ContactNotFound(recipientName)

        return try {
            val smsManager: SmsManager =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
            // Split if the body exceeds one SMS segment.
            val parts = smsManager.divideMessage(body)
            if (parts.size == 1) {
                smsManager.sendTextMessage(contact.number, null, body, null, null)
            } else {
                smsManager.sendMultipartTextMessage(contact.number, null, parts, null, null)
            }
            Log.d(TAG, "SMS → ${contact.displayName} (${contact.number})  ${body.length} chars")
            SendResult.Sent(contact)
        } catch (e: Exception) {
            Log.e(TAG, "SMS send failed: ${e.message}", e)
            SendResult.Failed(e.message ?: "unknown error")
        }
    }
}
