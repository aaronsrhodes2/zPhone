package local.skippy.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.skippy.chat.compositor.ChatPalette
import local.skippy.chat.model.Message
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Long-press detail sheet — one-screen dev diagnostic for any message.
 *
 * Shown via [ModalBottomSheet]. Background is [ChatPalette.Black];
 * labels in [ChatPalette.DimGreenHi]; values in [ChatPalette.White].
 * Raw JSON is pretty-printed (2-space indent) if parseable, else
 * shown verbatim.
 *
 * This is the ONLY non-chat surface in the app. Everything the
 * Captain might want to know about a message lives here — source,
 * tier, latency, raw body, timestamp — so the main UI stays clean.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevDetailSheet(
    message: Message,
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ChatPalette.Black,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "MESSAGE DETAIL",
                color = ChatPalette.Cyan,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))

            Field("seq",       message.seq.toString())
            Field("role",      message.role.name.lowercase(Locale.ROOT))
            Field("source",    message.source)
            Field("tier",      message.tier ?: "—")
            Field("latency",   message.latencyMs?.let { "$it ms" } ?: "—")
            Field("timestamp", formatTs(message.ts))
            Field("failed",    if (message.failed) "yes" else "no")

            Spacer(Modifier.height(12.dp))
            Text(
                text = "raw",
                color = ChatPalette.DimGreenHi,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Text(
                text = prettyJson(message.raw) ?: "—",
                color = ChatPalette.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            color = ChatPalette.DimGreenHi,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier.height(20.dp),
        )
        Text(
            text = value,
            color = ChatPalette.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
    }
}

private fun formatTs(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date(ms))

private fun prettyJson(raw: String?): String? {
    if (raw.isNullOrEmpty()) return null
    return try {
        JSONObject(raw).toString(2)
    } catch (_: Exception) {
        raw
    }
}
