package local.skippy.chat.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import local.skippy.chat.transport.SkippyTelClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
                    seq = nextSeq++,
                    ts = System.currentTimeMillis(),
                    role = Message.Role.ASSISTANT,
                    source = "assistant",
                    text = reply.reply,
                    tier = reply.tier,
                    latencyMs = reply.latencyMs,
                    raw = reply.rawJson,
                )
                _messages.update { it + assistantMsg }
            }
            _sending.value = false
        }
    }
}
