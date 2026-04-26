package local.skippy.chat.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.lerp as lerpColor
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import local.skippy.chat.audio.AudioRouter
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
    audioRouter:  AudioRouter,
) {
    val context      = LocalContext.current
    val messages     by viewModel.messages.collectAsState()
    val sending      by viewModel.sending.collectAsState()
    val inputMode    by viewModel.inputMode.collectAsState()
    val musicStatus  by viewModel.musicStatus.collectAsState()

    var selectedMessage by remember { mutableStateOf<Message?>(null) }
    val sheetState  = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope       = rememberCoroutineScope()

    val listState = rememberLazyListState()
    // Scroll to bottom when a new message lands (+1 for the always-visible cursor item).
    LaunchedEffect(messages.size) {
        val total = messages.size + 1
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    // ── Draft state ───────────────────────────────────────────────────────
    var draft          by remember { mutableStateOf("") }
    // True when the draft contains content that arrived via STT (not keyboard).
    // Auto-send only fires when this is true AND speechEngine.isSilent is true.
    // Cleared the moment the user types anything manually.
    var sttDraftPending by remember { mutableStateOf(false) }

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
            is KeywordScanner.Result.VolumeUp          -> {
                draft = ""
                audioRouter.volumeUp()
            }
            is KeywordScanner.Result.VolumeDown        -> {
                draft = ""
                audioRouter.volumeDown()
            }
            is KeywordScanner.Result.ServiceIntent     -> {
                draft = ""
                viewModel.activateService(result.serviceId)
                val manifest = viewModel.getManifest(result.serviceId)
                when {
                    // 1. Companion Android app — launch with context-aware extras
                    manifest?.companionApp != null -> {
                        val launchIntent = buildCompanionIntent(
                            context      = context,
                            companionApp = manifest.companionApp,
                        )
                        try { context.startActivity(launchIntent) }
                        catch (_: android.content.ActivityNotFoundException) { /* app not installed */ }
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
            // Mark that draft now contains STT content — enables auto-send countdown.
            sttDraftPending = true
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

    // ── Paragraph key (Caps Lock → newline) ──────────────────────────────
    // MainActivity intercepts KEYCODE_CAPS_LOCK and emits to this flow.
    // Here we append '\n' to the draft so the user can insert paragraph
    // breaks during keyboard input without Shift+Enter.
    LaunchedEffect(Unit) {
        viewModel.paragraphKey.collect {
            val sep = if (draftRef.value.isNotBlank()) "\n" else ""
            onDraftChangeRef.value(draftRef.value + sep)
            // A paragraph break is manual keyboard input — disarm STT timer.
        }
    }

    // Keyboard state is now managed entirely inside InputBar.
    // ChatScreen only needs the scope for coroutines already declared above.

    // ── Auto-send countdown ───────────────────────────────────────────────
    //
    // Timer arms ONLY when:
    //   • inputMode is VIBE (not PLANNING — "ship it" is the explicit send there)
    //   • draft has content from STT (sttDraftPending=true) — keyboard-only
    //     drafts are never auto-sent
    //   • speechEngine.isSilent=true — the recognizer detected end-of-speech,
    //     meaning the user has stopped talking (not mid-utterance)
    //   • not already sending
    //
    // Duration: 8 seconds (ISOIEC 24751 / common voice-UX guideline for
    // composed-thought dictation — long enough to pause between clauses without
    // feeling rushed).
    val autoSendProgress = remember { Animatable(0f) }
    LaunchedEffect(draft, inputMode, sending, speechEngine.isSilent, sttDraftPending) {
        if (inputMode == InputMode.VIBE && draft.isNotBlank() && !sending
                && sttDraftPending && speechEngine.isSilent) {
            autoSendProgress.snapTo(0f)
            autoSendProgress.animateTo(
                targetValue   = 1f,
                animationSpec = tween(durationMillis = 8_000, easing = LinearEasing),
            )
            // Double-guard — draft must still be non-blank and STT-sourced at fire time.
            val text = draft.trim()
            if (text.isNotBlank()) {
                draft = ""
                sttDraftPending = false
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
        Header(client, musicStatus)
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
            // Pending bubble — always in the feed as a cursor landmark.
            // Empty draft: just the blinking cursor ▍ right-aligned (terminal prompt feel).
            // Non-empty draft: dim bordered bubble grows around the composing text.
            // Border brightness pulses with mic RMS; newly spoken words flash bright then fade.
            item(key = "pending") {
                PendingBubble(text = draft, rmsLevel = speechEngine.rmsLevel)
            }
        }

        InputBar(
            draft            = draft,
            onDraftChange    = onDraftChange,
            onKeyboardInput  = { sttDraftPending = false },
            onSend           = { text ->
                draft = ""
                sttDraftPending = false
                viewModel.send(text)
            },
            sending          = sending,
            inputMode        = inputMode,
            progress         = autoSendProgress.value,
            speechEngine     = speechEngine,
            isTranslating    = isTranslating,
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
private fun Header(client: SkippyTelClient, musicStatus: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment    = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Title + optional music glyph
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text       = "Skippy",
                color      = ChatPalette.White,
                fontFamily = FontFamily.Default,
                fontSize   = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            when (musicStatus) {
                "playing" -> Text(
                    text     = " ♪",
                    color    = ChatPalette.DimGreenHi,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                "paused"  -> Text(
                    text     = " ⏸",
                    color    = ChatPalette.DimGreen,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
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

// ── Companion app launch ──────────────────────────────────────────────────

/**
 * Build a launch Intent for a companion Android app.
 *
 * Uses the standard LAUNCHER intent. Services that used to have custom
 * intent protocols (SkippyMusic) have been removed — music is now a
 * read-only Bilby card in SkippyDroid's viewport feed, not a standalone app.
 */
private fun buildCompanionIntent(
    context: android.content.Context,
    companionApp: String,
): android.content.Intent {
    val flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
    return context.packageManager.getLaunchIntentForPackage(companionApp)
        ?: android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            setPackage(companionApp)
            addFlags(flags)
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

// ── Pending bubble ────────────────────────────────────────────────────────

/**
 * Always-visible composable at the bottom of the feed.
 *
 * Empty draft → just a blinking-cursor glyph (▍) right-aligned. Acts as a
 * visual landmark showing the app is focused and ready for keyboard input.
 *
 * Non-empty draft → a dim green bordered bubble showing the composing text
 * with a trailing cursor, with two live animations:
 *
 *  1. **Border flash** — border color lerps from DimGreen → Green in real-time
 *     with mic RMS amplitude. When you speak the border brightens; silence → dims.
 *
 *  2. **New-word fade** — each time new text lands (STT append), the whole text
 *     briefly flashes to White then fades back to DimGreenHi over ~700ms. Makes
 *     the bubble feel alive and confirms the recognizer heard you.
 */
@Composable
private fun PendingBubble(text: String, rmsLevel: Float = 0f) {
    // Track previous text length to detect new content arrivals.
    var prevLength by remember { mutableStateOf(text.length) }
    // Brightness of new-text flash: 1f = White, 0f = DimGreenHi.
    val flashBrightness = remember { Animatable(0f) }

    LaunchedEffect(text) {
        if (text.length > prevLength) {
            // New characters arrived — flash bright then fade.
            flashBrightness.snapTo(1f)
            flashBrightness.animateTo(
                targetValue   = 0f,
                animationSpec = tween(durationMillis = 700, easing = LinearEasing),
            )
        }
        prevLength = text.length
    }

    // Border color: dim at silence, bright green at peak voice amplitude.
    val borderColor = lerpColor(ChatPalette.DimGreen, ChatPalette.Green, rmsLevel.coerceIn(0f, 1f))
    // Text color: flashes to White on new content, rests at DimGreenHi.
    val textColor   = lerpColor(ChatPalette.DimGreenHi, ChatPalette.White, flashBrightness.value)

    Row(
        modifier            = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        if (text.isBlank()) {
            // Cursor-only state — minimal indicator that focus is here.
            // Border also flashes on the cursor glyph when voice is detected.
            Text(
                text       = "▍",
                color      = lerpColor(ChatPalette.DimGreen, ChatPalette.Green, rmsLevel.coerceIn(0f, 1f)),
                fontSize   = 14.sp,
                fontFamily = FontFamily.Monospace,
                modifier   = Modifier.padding(end = 4.dp, bottom = 4.dp),
            )
        } else {
            Text(
                text       = "$text▍",
                color      = textColor,
                fontSize   = 15.sp,
                fontFamily = FontFamily.Default,
                modifier   = Modifier
                    .widthIn(max = 280.dp)
                    .background(ChatPalette.Black)
                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

// ── Input bar ─────────────────────────────────────────────────────────────

/**
 * Minimal bottom strip — no visible text box.
 *
 * ┌──────────────────────────────────────────────────────┐
 * │ ▼ close keyboard          ← only when IME is up      │  24dp
 * │ ● PLANNING — "ship it" to send   ← only in PLANNING  │  20dp
 * ├──────────────────────────────────────────────────────┤
 * │ ● mic  "speak to Skippy…" (weight 1)           [⌨]  │  40dp
 * │ ████░░░░░░░░░░░░░ countdown bar (STT only)           │   2dp
 * │ [invisible 1dp BasicTextField — focus + key capture] │  ~0dp
 * └──────────────────────────────────────────────────────┘
 *
 * Composing text is shown instead as a [PendingBubble] at the bottom of the
 * feed LazyColumn — always visible as a cursor landmark, grows into a full
 * bubble when draft is non-empty. Sent on Enter (keyboard) or 8s silence (STT).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun InputBar(
    draft:           String,
    onDraftChange:   (String) -> Unit,
    /** Called on every keystroke from the physical/soft keyboard.
     *  Signals that the draft now has user-typed content, disarming the
     *  STT auto-send countdown for this draft. */
    onKeyboardInput: () -> Unit = {},
    /** Called when Enter/Send fires from the keyboard to submit the draft. */
    onSend:          (String) -> Unit = {},
    sending:         Boolean,
    inputMode:       InputMode,
    progress:        Float,
    speechEngine:    SpeechInputEngine,
    isTranslating:   Boolean = false,
) {
    val focusRequester     = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible         = WindowInsets.isImeVisible
    val scope              = rememberCoroutineScope()

    // Claim focus on first composition.
    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    // Mic status
    val isMuted     = speechEngine.isMuted
    val isListening = speechEngine.isListening
    val partial     = speechEngine.partialTranscript
    val isSpanish   = speechEngine.targetLanguage.startsWith("es")
    val micColor    = when {
        isMuted     -> ChatPalette.DimGreen
        isSpanish   -> ChatPalette.Amber
        isListening -> ChatPalette.Green     // active listening = bright green, not red
        else        -> ChatPalette.DimGreenHi
    }
    val statusText = when {
        isMuted                  -> "muted"
        isTranslating            -> "🌐 translating…"
        partial.isNotBlank()     -> if (isSpanish) "🌐 $partial" else partial
        isListening && isSpanish -> "escuchando…"
        isListening              -> "listening…"
        else                     -> "speak to Skippy…"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChatPalette.Black)
            .border(1.dp, ChatPalette.DimGreenHi)
            .navigationBarsPadding()
            .imePadding(),
    ) {

        // ── "▼ close keyboard" — only when IME is up ─────────────────────
        if (imeVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clickable { keyboardController?.hide() }
                    .padding(horizontal = 12.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text       = "▼ close keyboard",
                    color      = ChatPalette.DimGreenHi,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 11.sp,
                )
                if (isMuted) Text("muted", color = ChatPalette.DimGreen,
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }

        // ── Planning banner ───────────────────────────────────────────────
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

        // ── Mic status + keyboard button row ─────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(micColor))
            Spacer(Modifier.width(8.dp))
            Text(
                text       = statusText,
                color      = if (partial.isNotBlank()) ChatPalette.Amber else ChatPalette.DimGreenHi,
                fontFamily = FontFamily.Default,
                fontSize   = 13.sp,
                maxLines   = 1,
                overflow   = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier   = Modifier.weight(1f),
            )
            // Right-side indicators
            val modeTag = when {
                isSpanish && inputMode == InputMode.PLANNING -> "● plan  🌐"
                isSpanish   -> "🌐 ES"
                inputMode == InputMode.PLANNING -> "● planning"
                else -> null
            }
            if (modeTag != null) {
                Text(
                    text       = modeTag,
                    color      = ChatPalette.Amber,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 10.sp,
                )
                Spacer(Modifier.width(6.dp))
            }
            // ⌨ button — the ONLY way to summon the soft keyboard
            Text(
                text     = "⌨",
                color    = if (imeVisible) ChatPalette.Green else ChatPalette.DimGreenHi,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable {
                        if (imeVisible) keyboardController?.hide()
                        else {
                            scope.launch {
                                try { focusRequester.requestFocus() } catch (_: Exception) {}
                                keyboardController?.show()
                            }
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        // ── Invisible keyboard capture ────────────────────────────────────
        //
        // No visible text box. Keyboard input is captured by this 1×1dp
        // transparent BasicTextField. The composing text is shown instead as
        // a PendingBubble inside the LazyColumn feed (always visible, with a
        // blinking cursor even when draft is empty — serves as the keyboard-
        // focus landmark).
        //
        // singleLine=true → Enter fires ImeAction.Send on both soft and
        // hardware keyboards. Autocorrect + sentence capitalisation remain on
        // so voice-typed and manually typed text is cleaned up automatically.
        BasicTextField(
            value           = draft,
            onValueChange   = { onKeyboardInput(); onDraftChange(it) },
            modifier        = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    if (!state.isFocused && !imeVisible) {
                        scope.launch {
                            kotlinx.coroutines.delay(150)
                            try { focusRequester.requestFocus() } catch (_: Exception) {}
                        }
                    }
                },
            enabled         = !sending,
            singleLine      = true,
            textStyle       = TextStyle(color = ChatPalette.White),
            keyboardOptions = KeyboardOptions(
                capitalization     = KeyboardCapitalization.Sentences,
                autoCorrectEnabled = true,
                keyboardType       = KeyboardType.Text,
                imeAction          = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(
                onSend = {
                    val text = draft.trim()
                    if (text.isNotBlank()) onSend(text)
                }
            ),
        )

        // ── Countdown bar — only shown when STT auto-send is armed ──────────
        if (inputMode == InputMode.VIBE && draft.isNotBlank() && speechEngine.isSilent) {
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
