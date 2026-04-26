package local.skippy.chat.model

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import local.skippy.chat.sms.SmsSender
import local.skippy.chat.transport.SkippyTelClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One message in the scrollback.
 *
 * Intentionally fat: carries everything the DevDetailSheet needs so
 * a long-press never has to hit the network again. The `raw` field
 * holds the literal JSON SkippyTel returned (assistant messages
 * only). `source` is an open string — Phase 1 uses `"text"` for the
 * Captain and `"assistant"` for the reply, but Phase 2 will add
 * `"glasses_observation"`, `"voice"`, `"context_tick"`, etc.
 */
data class Message(
    val seq: Long,
    val ts: Long,                  // epoch millis
    val role: Role,
    val source: String,
    val text: String,
    val tier: String? = null,      // "local" | "cloud" | null (user msgs)
    val latencyMs: Long? = null,   // round-trip for assistant replies
    val raw: String? = null,       // raw JSON body for dev sheet
    val failed: Boolean = false,   // user message failed to get a reply
    val imageBase64: String? = null, // base64 PNG data URL from SD, if any
    val imagePrompt: String? = null, // the SD prompt that produced the image
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

/**
 * Append-only in-memory scrollback + send pipeline.
 *
 * Phase 1: no persistence. When the Captain cold-launches the app,
 * scrollback is empty. Phase 2 replaces the backing list with a
 * server-sync'd feed from SkippyTel's shared conversation log —
 * nothing else about this class changes, which is why the UI reads
 * [messages] as a [StateFlow] instead of a [kotlinx.coroutines.flow.Flow].
 */
class ChatViewModel(
    private val client: SkippyTelClient,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _sending = MutableStateFlow(false)
    /** True while a `/intent/unmatched` request is in flight. */
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _inputMode = MutableStateFlow(InputMode.VIBE)
    /**
     * Current chat input mode. Owned here (not in the composable) so it
     * survives recomposition and screen rotation.
     *   NORMAL   — 5-second idle timer auto-sends
     *   PLANNING — accumulate until "ship it"
     */
    val inputMode: StateFlow<InputMode> = _inputMode.asStateFlow()

    /** Switch input mode. Called from the keyword scanner in the UI layer. */
    fun setMode(mode: InputMode) {
        if (_inputMode.value == mode) return   // idempotent — no spurious recompositions
        _inputMode.value = mode
    }

    // ── Paragraph key ─────────────────────────────────────────────────────
    // Emitted by MainActivity when the hardware Caps Lock key is pressed.
    // ChatScreen collects this and appends '\n' to the draft so the user
    // can insert paragraph breaks without reaching for Shift+Enter.
    private val _paragraphKey = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val paragraphKey: SharedFlow<Unit> = _paragraphKey.asSharedFlow()

    fun onParagraphKey() { _paragraphKey.tryEmit(Unit) }

    // ── Service manifest ──────────────────────────────────────────────────

    private val _services = MutableStateFlow<List<ServiceManifest>>(emptyList())
    /** Live service manifest from SkippyTel `GET /services`. */
    val services: StateFlow<List<ServiceManifest>> = _services.asStateFlow()

    /** Look up a service by id. Returns null if not found or manifest not yet loaded. */
    fun getManifest(id: String): ServiceManifest? = _services.value.find { it.id == id }

    /**
     * Fetch the SkippyTel service manifest and wire voice triggers into
     * [KeywordScanner.dynamicTriggers]. Re-polls every 5 minutes so the
     * phone picks up new services without a restart.
     *
     * Called once from init; stops automatically when the ViewModel is cleared.
     *
     * Poll interval: 5 minutes. This is intentionally longer than SkippyDroid's
     * 60s ServiceRegistry poll — voice triggers rarely change mid-session, and
     * the longer interval saves battery on the Captain's phone. The FIRST fetch
     * fires immediately (no initial lag); subsequent fetches run every 5 min.
     */
    private fun loadServices() {
        viewModelScope.launch {
            while (true) {
                val fetched = withContext(Dispatchers.IO) { client.getServices() }
                if (fetched != null) {
                    _services.value = fetched
                    // Build trigger map sorted longest-first (most-specific match wins).
                    KeywordScanner.dynamicTriggers = fetched
                        .filter { it.available }
                        .flatMap { svc ->
                            svc.voiceTriggers
                                .filter { it != "*" }   // exclude catch-all
                                .map { it.lowercase() to svc.id }
                        }
                        .sortedByDescending { (phrase, _) -> phrase.length }
                        .toMap()
                    Log.i(TAG, "Loaded ${fetched.size} services; " +
                            "${KeywordScanner.dynamicTriggers.size} voice triggers registered")
                }
                delay(5 * 60 * 1_000L)   // refresh every 5 minutes (SkippyDroid uses 60s for its HUD)
            }
        }
    }

    /**
     * Post a system bubble confirming a service was activated by voice, and
     * log the activation. Companion-app launch happens in ChatScreen (needs
     * a UI context).
     */
    fun activateService(serviceId: String) {
        val svc = getManifest(serviceId)
        val label = svc?.name ?: serviceId
        _messages.update {
            it + Message(
                seq    = nextSeq++,
                ts     = System.currentTimeMillis(),
                role   = Message.Role.SYSTEM,
                source = "service_intent",
                text   = "⚙ Opening $label…",
            )
        }
    }

    // ── Music status (DJ Bilby header glyph) ──────────────────────────────
    //
    // Polls /music/bilby-status every 20 seconds. SkippyChat only needs to
    // know playing/paused/none — no full session details required here.
    // The ♪ glyph in the Header composable reads this state.

    private val _musicStatus = MutableStateFlow("none")
    /** "playing" | "paused" | "none" — updated every 20 seconds. */
    val musicStatus: StateFlow<String> = _musicStatus.asStateFlow()

    private fun pollMusicStatus() {
        viewModelScope.launch {
            while (true) {
                val status = withContext(Dispatchers.IO) { client.getMusicStatus() }
                _musicStatus.value = status?.status ?: "none"
                delay(20_000L)
            }
        }
    }

    init {
        loadServices()
        pollMusicStatus()
    }

    private companion object {
        const val TAG = "Local.Skippy.Chat.VM"
    }

    // ── SMS ───────────────────────────────────────────────────────────────

    /**
     * Resolve [recipientName] to a contact, send the SMS, and append
     * the outbound bubble + a system confirmation to the feed.
     *
     * Uses applicationContext internally so it's safe to call from a
     * Composable via LocalContext.current.
     */
    fun sendSms(context: Context, recipientName: String, body: String) {
        val appCtx   = context.applicationContext
        val userSeq  = nextSeq++
        val preview  = "[→ $recipientName] $body"

        // Append the outbound bubble immediately (optimistic).
        _messages.update {
            it + Message(
                seq    = userSeq,
                ts     = System.currentTimeMillis(),
                role   = Message.Role.USER,
                source = "sms_out",
                text   = preview,
            )
        }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                SmsSender.send(appCtx, recipientName, body)
            }
            val sysText = when (result) {
                is SmsSender.SendResult.Sent -> {
                    // Update the outbound bubble to use the resolved display name.
                    val resolved = "[→ ${result.contact.displayName}] $body"
                    _messages.update { list ->
                        list.map { if (it.seq == userSeq) it.copy(text = resolved) else it }
                    }
                    "SMS sent to ${result.contact.displayName} (${result.contact.number})"
                }
                is SmsSender.SendResult.ContactNotFound ->
                    "⚠ Contact not found: \"${result.query}\""
                is SmsSender.SendResult.Failed ->
                    "⚠ SMS failed: ${result.error}"
            }
            _messages.update {
                it + Message(
                    seq    = nextSeq++,
                    ts     = System.currentTimeMillis(),
                    role   = Message.Role.SYSTEM,
                    source = "sms_status",
                    text   = sysText,
                )
            }
        }
    }

    /**
     * Post a one-shot SYSTEM bubble — used by MainActivity to surface the
     * hot-deploy note passed via `adb shell am start --es update_note "..."`.
     */
    fun postSystemNote(text: String) {
        _messages.update {
            it + Message(
                seq    = nextSeq++,
                ts     = System.currentTimeMillis(),
                role   = Message.Role.SYSTEM,
                source = "deploy_note",
                text   = text,
            )
        }
    }

    // ── Bilby ─────────────────────────────────────────────────────────────

    /**
     * Ask SkippyTel for the current Bilby now-playing status and post
     * a SYSTEM bubble with the result. Fires from the "what's playing"
     * voice keyword.
     */
    fun bilbyNowPlaying() {
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { client.getBilbyStatus() }
            val text = if (status == null) {
                "⚠ Bilby unreachable — SkippyTel or Mac may be offline."
            } else when (status.source) {
                "none"   -> "Bilby: no music source active."
                "traktor" -> {
                    val np = status.nowPlaying
                    if (np != null) {
                        val deck = status.playingDeck?.uppercase() ?: "?"
                        "♫ [Deck $deck] ${np.artist.ifBlank { "Unknown" }} — ${np.title}"
                    } else if (!status.traktorAlive) {
                        "Bilby: Traktor is not running on the Mac."
                    } else {
                        "Bilby: Traktor is up but nothing is loaded yet."
                    }
                }
                "drive"  -> status.nowPlaying?.let { "♫ ${it.artist.ifBlank { "Unknown" }} — ${it.title}" }
                             ?: "Bilby: Drive mode — nothing playing."
                else     -> "Bilby: source=${status.source}"
            }
            _messages.update {
                it + Message(
                    seq    = nextSeq++,
                    ts     = System.currentTimeMillis(),
                    role   = Message.Role.SYSTEM,
                    source = "bilby_status",
                    text   = text,
                )
            }
        }
    }

    /**
     * Tell Bilby to skip to the next track. Posts a SYSTEM bubble
     * confirming the command. Fires from "next track" / "bilby next".
     */
    fun bilbyNext() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { client.postBilbyNext() }
            _messages.update {
                it + Message(
                    seq    = nextSeq++,
                    ts     = System.currentTimeMillis(),
                    role   = Message.Role.SYSTEM,
                    source = "bilby_status",
                    text   = "Bilby: next track →",
                )
            }
        }
    }

    /**
     * Append an incoming SMS to the feed.
     * Called from ChatScreen's SmsReceiver collector.
     */
    fun receiveSms(sender: String, body: String) {
        _messages.update {
            it + Message(
                seq    = nextSeq++,
                ts     = System.currentTimeMillis(),
                role   = Message.Role.ASSISTANT,
                source = "sms_in",
                text   = "[← $sender] $body",
            )
        }
    }

    private var nextSeq: Long = 0L

    /**
     * Send a typed message. Appends the user bubble immediately,
     * fires the request, then appends the assistant bubble on
     * success or flips the user bubble to `failed=true` on failure.
     */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (_sending.value) return     // serial sends — no concurrent in-flight

        val userSeq = nextSeq++
        val userMsg = Message(
            seq = userSeq,
            ts = System.currentTimeMillis(),
            role = Message.Role.USER,
            source = "text",
            text = trimmed,
        )
        _messages.update { it + userMsg }
        _sending.value = true

        viewModelScope.launch {
            val reply = withContext(Dispatchers.IO) {
                client.postIntentUnmatched(trimmed, source = "text")
            }
            if (reply == null) {
                // Flip the user bubble to failed — no assistant message.
                _messages.update { list ->
                    list.map { if (it.seq == userSeq) it.copy(failed = true) else it }
                }
            } else {
                val assistantMsg = Message(
                    seq         = nextSeq++,
                    ts          = System.currentTimeMillis(),
                    role        = Message.Role.ASSISTANT,
                    source      = "assistant",
                    text        = reply.reply,
                    tier        = reply.tier,
                    latencyMs   = reply.latencyMs,
                    raw         = reply.rawJson,
                    imageBase64 = reply.imageBase64,
                    imagePrompt = reply.imagePrompt,
                )
                _messages.update { it + assistantMsg }
            }
            _sending.value = false
        }
    }
}
