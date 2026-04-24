package local.skippy.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.skippy.chat.compositor.ChatPalette
import local.skippy.chat.model.ChatViewModel
import local.skippy.chat.model.Message
import local.skippy.chat.transport.SkippyTelClient
import kotlinx.coroutines.launch

/**
 * The single-screen SkippyChat surface.
 *
 * Layout (top → bottom):
 *   1. Header row (48dp): "Skippy" title + reachability pill.
 *   2. Offline / error banner (visible only when relevant).
 *   3. Scrollback (LazyColumn, fills remaining height).
 *   4. Input bar: OutlinedTextField + send button.
 *
 * Auto-scrolls to bottom on new message. Long-press any bubble →
 * [DevDetailSheet]. No other UI exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    client: SkippyTelClient,
) {
    val messages by viewModel.messages.collectAsStateSafe()
    val sending by viewModel.sending.collectAsStateSafe()

    var selectedMessage by remember { mutableStateOf<Message?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val listState = rememberLazyListState()
    // Auto-scroll to tail on new message.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    var draft by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatPalette.Black)
            .statusBarsPadding(),
    ) {
        Header(client)
        BannerRow(client)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(messages, key = { it.seq }) { msg ->
                MessageBubble(
                    message = msg,
                    onLongPress = { selectedMessage = it },
                )
            }
        }

        InputBar(
            draft = draft,
            onDraftChange = { draft = it },
            sending = sending,
            onSend = {
                val text = draft
                draft = ""
                viewModel.send(text)
            },
        )
    }

    selectedMessage?.let { msg ->
        DevDetailSheet(
            message = msg,
            sheetState = sheetState,
            onDismiss = {
                scope.launch { sheetState.hide() }
                selectedMessage = null
            },
        )
    }
}

@Composable
private fun Header(client: SkippyTelClient) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Skippy",
            color = ChatPalette.White,
            fontFamily = FontFamily.Default,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        ReachabilityPill(client)
    }
}

@Composable
private fun ReachabilityPill(client: SkippyTelClient) {
    val reachability = client.reachability
    val latency = client.pingLatencyMs
    val (dotColor, label) = when (reachability) {
        SkippyTelClient.Reachability.ONLINE ->
            ChatPalette.Cyan to (latency?.let { "${it}ms" } ?: "connected")
        SkippyTelClient.Reachability.UNKNOWN ->
            ChatPalette.Amber to "connecting…"
        SkippyTelClient.Reachability.OFFLINE ->
            ChatPalette.Red to "offline"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = dotColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

/**
 * Amber banner when SkippyTel is UNKNOWN, red when OFFLINE.
 * No banner when ONLINE.
 */
@Composable
private fun BannerRow(client: SkippyTelClient) {
    val (color, label) = when (client.reachability) {
        SkippyTelClient.Reachability.ONLINE  -> return
        SkippyTelClient.Reachability.UNKNOWN -> ChatPalette.Amber to "reaching SkippyTel…"
        SkippyTelClient.Reachability.OFFLINE -> ChatPalette.Red   to "SkippyTel unreachable — messages will fail"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChatPalette.Black)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChatPalette.Black)
            .border(1.dp, ChatPalette.DimGreenHi)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .imePadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    text = "type to Skippy…",
                    color = ChatPalette.DimGreenHi,
                    fontFamily = FontFamily.Default,
                )
            },
            singleLine = false,
            enabled = !sending,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = ChatPalette.White,
                fontFamily = FontFamily.Default,
                fontSize = 16.sp,
            ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = ChatPalette.Black,
                unfocusedContainerColor = ChatPalette.Black,
                disabledContainerColor = ChatPalette.Black,
                focusedTextColor = ChatPalette.White,
                unfocusedTextColor = ChatPalette.White,
                focusedIndicatorColor = ChatPalette.Green,
                unfocusedIndicatorColor = ChatPalette.DimGreen,
                cursorColor = ChatPalette.Green,
            ),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onSend,
            enabled = !sending && draft.isNotBlank(),
        ) {
            Icon(
                imageVector = SendArrow,
                contentDescription = "send",
                tint = if (draft.isNotBlank() && !sending) ChatPalette.Green
                       else ChatPalette.DimGreenHi,
            )
        }
    }
}

/**
 * Minimal send-arrow vector so we don't pull in material-icons-extended.
 * A right-pointing triangle + tail, 24dp.
 */
private val SendArrow: ImageVector = ImageVector.Builder(
    name = "SendArrow",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = androidx.compose.ui.graphics.SolidColor(Color.White),
    ) {
        moveTo(3f, 4f)
        lineTo(21f, 12f)
        lineTo(3f, 20f)
        lineTo(6f, 12f)
        close()
    }
}.build()

/**
 * Thin wrapper so the screen code reads `viewModel.messages.collectAsStateSafe()`
 * without hard-coding `initial = value`. `collectAsState` lives in
 * compose-runtime, no extra artifact.
 */
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateSafe() =
    collectAsState()
