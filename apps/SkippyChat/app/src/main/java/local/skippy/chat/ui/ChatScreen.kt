package local.skippy.chat.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import local.skippy.chat.audio.SpeechInputEngine
import local.skippy.chat.compositor.ChatPalette
import local.skippy.chat.model.ChatViewModel
import local.skippy.chat.model.InputMode
import local.skippy.chat.model.KeywordScanner
import local.skippy.chat.model.Message
import local.skippy.chat.sms.SmsReceiver
import local.skippy.chat.transport.SkippyTelClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Base64

/**
 * The single-screen SkippyChat surface.
 *
 * Layout (top → bottom):
 *   1. Header row (48dp): "Skippy" title + reachability pill.
 *   2. Offline / error banner (visible only when relevant).
 *   3. Scrollback (LazyColumn, fills remaining height).
 *   4. Input bar:
 *      - Collapsed (default): thin 40dp bar showing mic status + countdown.
 *        Tap anywhere to expand keyboard.
 *      - Expanded: full TextField + planning banner + countdown bar.
 *        IME dismiss or tapping the ▼ handle collapses back.
 *
 * Speech is always on (when permission granted and not muted). Transcripts
 * feed into the draft regardless of keyboard visibility. Volume Down =
 * mute toggle (wired in MainActivity.onKeyDown).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel:    ChatViewModel,
    client:       SkippyTelClient,
    speechEngine: SpeechInputEngine,
) {
    val context    = LocalContext.current
    val messages   by viewModel.messages.collectAsState()
    val sending    by viewModel.sending.collectAsState()
    val inputMode  by viewModel.inputMode.collectAsState()

    var selectedMessage by remember { mutableStateOf<Message?>(null) }
    val sheetState  = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope       = rememberCoroutineScope()

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // ── Draft state ───────────────────────────────────────────────────────
    var draft by remember { mutableStateOf("") }

    // Stable refs so the speech callback never captures stale values.
    val draftRef       = rememberUpdatedState(draft)
    val inputModeRef   = rememberUpdatedState(inputMode)

    // ── Language (Spanish) translation state ─────────────────────────────
    // True while a /translate/text call is in-flight for the latest transcript.
    var isTranslating by remember { mutableStateOf(false) }

    // ── Keyword handler ───────────────────────────────────────────────────
    // Defined as a val so both keyboard onChange and the speech callback
    // go through the same scanner.
    val onDraftChange: (String) -> Unit = { raw ->
        when (val result = KeywordScanner.scan(raw, inputMode)) {
            is KeywordScanner.Result.None               -> draft = raw
            is KeywordScanner.Result.ClearAll           -> { draft = ""; viewModel.setMode(InputMode.VIBE) }
            is KeywordScanner.Result.DropLastParagraph  -> draft = result.cleanedDraft
            is KeywordScanner.Result.SendNow            -> {
                val text = result.cleanedDraft
                draft = ""
                viewModel.setMode(InputMode.VIBE)
                if (text.isNotBlank()) viewModel.send(text)
            }
            is KeywordScanner.Result.SwitchMode         -> {
                draft = result.cleanedDraft
                viewModel.setMode(result.mode)
            }
            is KeywordScanner.Result.EnterSpanish       -> speechEngine.targetLanguage = "es-ES"
            is KeywordScanner.Result.ExitSpanish        -> speechEngine.targetLanguage = "en-US"
            is KeywordScanner.Result.SendSms            -> {
                draft = ""
                viewModel.setMode(InputMode.VIBE)
                viewModel.sendSms(context, result.recipient, result.body)
            }
            is KeywordScanner.Result.BilbyNowPlaying   -> {
                draft = ""
                viewModel.bilbyNowPlaying()
            }
            is KeywordScanner.Result.BilbyNext         -> {
                draft = ""
                viewModel.bilbyNext()
            }
            is KeywordScanner.Result.ServiceIntent     -> {
                draft = ""
                viewModel.activateService(result.serviceId)
                val manifest = viewModel.getManifest(result.serviceId)
                when {
                    // 1. Companion Android app — launch it
                    manifest?.companionApp != null -> {
                        context.packageManager.getLaunchIntentForPackage(manifest.companionApp)
                            ?.apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                            ?.let { context.startActivity(it) }
                    }
                    // 2. External service with a direct Tailscale URL — open in browser
                    // (covers Bilby web UI, future Mac-side services, etc.)
                    manifest?.baseUrl != null -> {
                        val uri = android.net.Uri.parse(manifest.baseUrl)
                        val browserIntent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW, uri
                        ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                        try { context.startActivity(browserIntent) }
                        catch (_: android.content.ActivityNotFoundException) { /* no browser */ }
                    }
                    // 3. Inline / overlay service — SkippyTel handles it; bubble is enough
                    else -> Unit
                }
            }
        }
    }
    val onDraftChangeRef = rememberUpdatedState(onDraftChange)

    // Stable refs used inside the DisposableEffect below.
    val clientRef          = rememberUpdatedState(client)
    val isTranslatingRef   = rememberUpdatedState(isTranslating)

    // ── Speech → draft wiring ─────────────────────────────────────────────
    // When the recognizer is in Spanish mode (targetLanguage starts with "es"),
    // each transcript is routed through SkippyTel /translate/text before it
    // lands in the draft. Translation is async; isTranslating flips true/false
    // around the network call so the status bar can show "🌐 translating…".
    DisposableEffect(speechEngine) {
        speechEngine.onTranscript = { transcript ->
            if (speechEngine.targetLanguage.startsWith("es")) {
                // Spanish path — translate on IO, append English result on main.
                isTranslating = true
                scope.launch {
                    val reply = withContext(Dispatchers.IO) {
                        clientRef.value.translateText(transcript, hintLang = "es")
                    }
                    isTranslating = false
                    val english = reply?.englishText?.takeIf { it.isNotBlank() } ?: transcript
                    val cur = draftRef.value
                    val sep = when {
                        inputModeRef.value == InputMode.PLANNING -> "\n\n"
                        cur.isNotBlank()                         -> " "
                        else                                     -> ""
                    }
                    onDraftChangeRef.value(cur + sep + english)
                }
            } else {
                // English (default) path — no translation needed.
                val current = draftRef.value
                val sep = when {
                    inputModeRef.value == InputMode.PLANNING -> "\n\n"
                    current.isNotBlank()                     -> " "
                    else                                     -> ""
                }
                onDraftChangeRef.value(current + sep + transcript)
            }
        }
        onDispose { speechEngine.onTranscript = null }
    }

    // ── Image save ───────────────────────────────────────────────────────
    // Long-press on a generated image → save to MediaStore "SkippyGallery".
    val onImageSave: (String) -> Unit = { b64 ->
        scope.launch(Dispatchers.IO) {
            val saved = saveImageToGallery(context, b64)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    if (saved) "Saved to SkippyGallery" else "Image save failed",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // ── Incoming SMS feed ─────────────────────────────────────────────────
    // SmsReceiver posts to a SharedFlow; collect here and hand to ViewModel.
    LaunchedEffect(Unit) {
        SmsReceiver.incoming.collect { sms ->
            viewModel.receiveSms(sms.sender, sms.body)
        }
    }

    // ── Keyboard visibility ───────────────────────────────────────────────
    var keyboardVisible by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    // If the user dismisses the keyboard with the back button, sync state.
    LaunchedEffect(imeVisible) {
        if (!imeVisible) keyboardVisible = false
    }

    // ── Auto-send countdown ───────────────────────────────────────────────
    val autoSendProgress = remember { Animatable(0f) }
    LaunchedEffect(draft, inputMode, sending) {
        if (inputMode == InputMode.VIBE && draft.isNotBlank() && !sending) {
            autoSendProgress.snapTo(0f)
            autoSendProgress.animateTo(
                targetValue    = 1f,
                animationSpec  = tween(durationMillis = 5_000, easing = LinearEasing),
            )
            // Double-guard — draft must still be non-blank at fire time.
            // (Speech or keyboard may have cleared it mid-countdown.)
            val text = draft.trim()
            if (text.isNotBlank()) {
                draft = ""
                viewModel.send(text)
            }
        } else {
            autoSendProgress.snapTo(0f)
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatPalette.Black)
            .statusBarsPadding(),
    ) {
        Header(client)
        BannerRow(client)

        LazyColumn(
            modifier       = Modifier.weight(1f).fillMaxWidth(),
            state          = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(messages, key = { it.seq }) { msg ->
                MessageBubble(
                    message     = msg,
                    onLongPress = { selectedMessage = it },
                    onImageSave = onImageSave,
                )
            }
        }

        InputBar(
            draft           = draft,
            onDraftChange   = onDraftChange,
            sending         = sending,
            inputMode       = inputMode,
            progress        = autoSendProgress.value,
            keyboardVisible = keyboardVisible,
            onExpand        = { keyboardVisible = true },
            onCollapse      = { keyboardVisible = false },
            speechEngine    = speechEngine,
            isTranslating   = isTranslating,
        )
    }

    selectedMessage?.let { msg ->
        DevDetailSheet(
            message    = msg,
            sheetState = sheetState,
            onDismiss  = {
                scope.launch { sheetState.hide() }
                selectedMessage = null
            },
        )
    }
}

// ── Header ────────────────────────────────────────────────────────────────

@Composable
private fun Header(client: SkippyTelClient) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment    = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text       = "Skippy",
            color      = ChatPalette.White,
            fontFamily = FontFamily.Default,
            fontSize   = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        ReachabilityPill(client)
    }
}

@Composable
private fun ReachabilityPill(client: SkippyTelClient) {
    val reachability = client.reachability
    val latency      = client.pingLatencyMs
    val (dotColor, label) = when (reachability) {
        SkippyTelClient.Reachability.ONLINE  -> ChatPalette.Cyan  to (latency?.let { "${it}ms" } ?: "connected")
        SkippyTelClient.Reachability.UNKNOWN -> ChatPalette.Amber to "connecting…"
        SkippyTelClient.Reachability.OFFLINE -> ChatPalette.Red   to "offline"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(6.dp))
        Text(text = label, color = dotColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

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
        Text(text = label, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

// ── Gallery save ─────────────────────────────────────────────────────────

/**
 * Write a base64-encoded PNG (optionally prefixed with a data-URL header)
 * to MediaStore under Pictures/SkippyGallery.
 *
 * No permissions needed on API 29+ (Android 10+) for writing to a
 * non-primary-external location with [MediaStore]. Returns true on success.
 */
private fun saveImageToGallery(context: android.content.Context, base64: String): Boolean {
    return try {
        val stripped = if (base64.contains(",")) base64.substringAfter(",") else base64
        val bytes = Base64.decode(stripped, Base64.DEFAULT)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "skippy_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SkippyGallery")
            }
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
        ) ?: return false
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        true
    } catch (_: Exception) {
        false
    }
}

// ── Input bar ─────────────────────────────────────────────────────────────

/**
 * Two visual states:
 *
 * COLLAPSED (default, keyboard hidden):
 * ┌────────────────────────────────────────────────────┐
 * │ ● [mic]   "what I hear…"  (weight 1)   ▲ type     │  40dp
 * │ ████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░           │   2dp countdown
 * └────────────────────────────────────────────────────┘
 *
 * EXPANDED (keyboard visible):
 * ┌────────────────────────────────────────────────────┐
 * │ ▼ collapse                     ● [mic]             │  24dp handle
 * │ ● PLANNING — "ship it" to send                     │  planning banner
 * │ [OutlinedTextField                               ] │
 * │ ████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  │  2dp countdown
 * └────────────────────────────────────────────────────┘
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(
    draft:           String,
    onDraftChange:   (String) -> Unit,
    sending:         Boolean,
    inputMode:       InputMode,
    progress:        Float,
    keyboardVisible: Boolean,
    onExpand:        () -> Unit,
    onCollapse:      () -> Unit,
    speechEngine:    SpeechInputEngine,
    isTranslating:   Boolean = false,
) {
    val focusRequester   = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Only hide the keyboard on collapse — never auto-show it.
    // Keyboard appears only when the user explicitly taps inside the text field.
    LaunchedEffect(keyboardVisible) {
        if (!keyboardVisible) keyboardController?.hide()
    }

    // Mic status colors / label
    val isMuted      = speechEngine.isMuted
    val isListening  = speechEngine.isListening
    val partial      = speechEngine.partialTranscript
    val isSpanish    = speechEngine.targetLanguage.startsWith("es")
    val micColor     = when {
        isMuted      -> ChatPalette.DimGreen
        isSpanish    -> ChatPalette.Amber   // amber = Spanish mode active
        isListening  -> ChatPalette.Red
        else         -> ChatPalette.DimGreenHi
    }
    val statusText = when {
        isMuted                -> "muted"
        isTranslating          -> "🌐 translating…"
        partial.isNotBlank()   -> if (isSpanish) "🌐 $partial" else partial
        isListening && isSpanish -> "escuchando…"
        isListening            -> "listening…"
        else                   -> "speak to Skippy…"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChatPalette.Black)
            .border(1.dp, ChatPalette.DimGreenHi)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        if (!keyboardVisible) {
            // ── Collapsed bar ─────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clickable { onExpand() }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Mic dot
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(micColor),
                )
                Spacer(Modifier.width(8.dp))
                // Partial transcript or status
                Text(
                    text       = statusText,
                    color      = if (partial.isNotBlank()) ChatPalette.Amber else ChatPalette.DimGreenHi,
                    fontFamily = FontFamily.Default,
                    fontSize   = 14.sp,
                    maxLines   = 1,
                    modifier   = Modifier.weight(1f),
                    overflow   = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                // Right-side label: language flag > planning > expand hint
                val rightLabel = when {
                    isSpanish && inputMode == InputMode.PLANNING -> "● planning  🌐 ES"
                    isSpanish                                    -> "🌐 ES"
                    inputMode == InputMode.PLANNING              -> "● planning"
                    else                                         -> "▲ type"
                }
                Text(
                    text       = rightLabel,
                    color      = if (inputMode == InputMode.PLANNING || isSpanish) ChatPalette.Amber
                                 else ChatPalette.DimGreenHi,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 11.sp,
                )
            }
        } else {
            // ── Collapse handle ───────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clickable { onCollapse() }
                    .padding(horizontal = 12.dp),
                verticalAlignment    = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text       = "▼ close keyboard",
                    color      = ChatPalette.DimGreenHi,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 11.sp,
                )
                // Mic status in expanded mode too
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(micColor))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text       = if (isMuted) "muted" else "",
                        color      = ChatPalette.DimGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 10.sp,
                    )
                }
            }

            // ── Planning mode banner ──────────────────────────────────────
            if (inputMode == InputMode.PLANNING) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(ChatPalette.Amber))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text       = "PLANNING — say \"ship it\" to send",
                        color      = ChatPalette.Amber,
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 11.sp,
                    )
                }
            }

            // ── Text field ────────────────────────────────────────────────
            OutlinedTextField(
                value         = draft,
                onValueChange = onDraftChange,
                modifier      = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .focusRequester(focusRequester),
                placeholder = {
                    Text(
                        text       = "type to Skippy…",
                        color      = ChatPalette.DimGreenHi,
                        fontFamily = FontFamily.Default,
                    )
                },
                singleLine  = false,
                enabled     = !sending,
                textStyle   = TextStyle(
                    color      = ChatPalette.White,
                    fontFamily = FontFamily.Default,
                    fontSize   = 16.sp,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization     = KeyboardCapitalization.Sentences,
                    autoCorrectEnabled = true,
                    keyboardType       = KeyboardType.Text,
                    imeAction          = ImeAction.None,
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor   = ChatPalette.Black,
                    unfocusedContainerColor = ChatPalette.Black,
                    disabledContainerColor  = ChatPalette.Black,
                    focusedTextColor        = ChatPalette.White,
                    unfocusedTextColor      = ChatPalette.White,
                    focusedIndicatorColor   = ChatPalette.Green,
                    unfocusedIndicatorColor = ChatPalette.DimGreen,
                    cursorColor             = ChatPalette.Green,
                ),
            )
        }

        // ── Countdown bar — shown in both states when draft is live ───────
        if (inputMode == InputMode.VIBE && draft.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(ChatPalette.DimGreen),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(2.dp)
                        .background(ChatPalette.Green),
                )
            }
        }
    }
}
