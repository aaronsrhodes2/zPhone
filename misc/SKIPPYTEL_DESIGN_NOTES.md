## SkippyTel — Design Notes (resolving §7 open questions)

**Status:** v1 · April 22 2026
**Author:** SkippyTel side, in response to `SKIPPYTEL_BRIEF.md` v1 (April 22 2026)
**Scope:** answer the four open questions in §7 of the brief. No production code,
only decisions and impact.

All recommendations are compatible with the brief's Golden Doctrine: REST at
the phone boundary, Tailscale-as-auth, stateless request/response, `spec_version`
envelope, palette-enum names in any structured response, and hard cross-project
quarantine. Where the Passthrough Protocol set precedent ("simplest thing that
works" for the frame lane, MJPEG as zero-negotiation transport), SkippyTel
mirrors that doctrine: boring transports, no clever runtimes, nothing that
requires a shared library between phone and PC.

No new physics constants or measured values are introduced. Golden Rule 3 is
not tripped by any of the four questions.

---

### 1. Streaming for `/intent/unmatched`

**Question (verbatim, brief §7.1).**
SSE to the phone so partial LLM tokens render progressively? Or wait for full
response? Affects perceived latency more than raw TTFT.

**Recommendation.** Phase 1: plain POST, full-response JSON, one shot. Build
the SSE streaming variant behind an `Accept: text/event-stream` content
negotiation as a **Phase 2** flip once the first real latency measurements are
in. The brief already reserves the SSE option at §3.2 ("Streamed SSE reply
welcome if `Accept: text/event-stream`") — honor that contract by leaving the
server-side door open, but do not ship it first.

**Rationale.**
- **The BottomCenter chat slot is not a typewriter.** The brief's
  §4 doctrine says "no ellipsis, no scrollbars" and "four-sentence answer beats
  a paragraph." Replies are designed to be *short*. A 60-token answer at
  Ollama-local speeds (roughly 40 tok/s on the 4070 Ti Super for an 8B model)
  is ~1.5 s end-to-end. The phone's 2 s latency budget absorbs that. Streaming
  earns its complexity when replies are long; these are not.
- **Phone transport is plain POST only today.** `TransportLayer.kt:103-111`
  exposes one `post(path, body): String?` method that does
  `response.body?.string()` — a blocking single-shot read. SSE on the phone
  means a long-lived OkHttp `EventSource` subscription, a new parser, and a
  way to surface partial text to `CommandDispatcher.onUnmatched`'s caller.
  That's 100+ lines of new Android code for a sub-second UX win.
- **Claude's Messages API streaming is different from Ollama's.** Both support
  streaming, but the framing differs (Anthropic SSE vs Ollama NDJSON). SkippyTel
  would have to normalize — more scope for a Phase 1 sprint.
- **Retry semantics get messy.** Stateless request/response is the brief's
  doctrine (§4). A half-received SSE reply interrupted by a Tailscale partition
  is ambiguous: did the intent execute? Did `actions` arrive? Full-shot POST
  has clean retry: either you got 200 or you didn't.
- **Server-side non-streaming is trivial.** One Flask route, one blocking call
  to the model router, one JSON response. Fits inside a one-session sprint.
- **Escape hatch is cheap.** Because the phone sends `Accept: application/json`
  today, SkippyTel can add streaming later without breaking existing clients:
  if `Accept` contains `text/event-stream`, switch code paths; otherwise, plain
  JSON. Zero spec bump (forward-compatible per §15 of Passthrough Protocol
  doctrine).

**Ruled out:**
- **Always-SSE.** Would force the phone to rewrite its transport for a Phase 1
  gain that's speculative. Violates "simplest thing that works."
- **WebSocket.** Bidirectional, stateful, and the brief explicitly says "not a
  push channel" (§1). OkHttp's WS support is fine but nothing in the use case
  needs it.

**Impact on the phone side.** None in Phase 1. `TransportLayer.kt:103` stays
as-is. When Phase 2 SSE lands, add an `EventSource` helper and modify
`CommandDispatcher.onUnmatched` (`CommandDispatcher.kt:107`) to accept an
optional progressive-text callback — shape would become roughly
`onUnmatched: ((text, source, partial: (String) -> Unit) -> Unit)?`. Not today.

**Impact on SkippyTel side.**
- New Flask blueprint at `services/flask-api/app/routes/intent.py` registering
  `POST /intent/unmatched`. Extends `main.py` via `app.register_blueprint`.
- New module `services/flask-api/app/llm_router.py` — see question 2 for shape.
- Dependencies already in tree: Flask, the Ollama client at
  `app/ollama_client.py`. Add `anthropic` SDK (Python) for Claude routing.

**Risks / Phase 2 escape hatches.**
- **Risk:** If voice-escalated replies start wanting to be long (e.g. the
  Captain asks "summarize my calendar"), perceived latency will bite. Revisit
  threshold: if measured median reply length crosses ~120 tokens, flip the SSE
  switch.
- **Escape hatch:** Content-negotiate on `Accept` header. Server can ship SSE
  support without any phone change; phone adopts it when ready.

---

### 2. Claude vs Ollama routing

**Question (verbatim, brief §7.2).**
Who decides which model handles a request — the phone (sends `model_hint`),
the router (picks per intent category), or is it always Ollama first with
Claude fallback?

**Recommendation.** **Router-side policy, per-request, with Ollama-first-with-
Claude-fallback as the default branch and a declarative override table keyed
by intent category.** The phone does not send `model_hint`. SkippyTel owns the
policy; the phone stays dumb about model selection.

**Rationale.**
- **The phone must not know about model costs.** `pcUrl` is already a literal
  string in `MainActivity.kt`. Burying model preference in the phone makes it
  another thing that has to ship a new APK to retune. Model policy changes
  weekly as Ollama models get better and Claude prices shift. Keep that
  volatility on the PC side where it's a Python dict edit and a Docker
  restart.
- **Privacy posture demands local-first.** The brief §5 is explicit: "Never
  route personal data to Anthropic without an explicit opt-in." Ollama-first
  is the doctrine-correct default. Claude only engages when:
  - Ollama is unavailable (model not loaded, container crashed), OR
  - The intent category is flagged "needs frontier reasoning" in the override
    table, OR
  - Ollama returned low-confidence / malformed output and we have a retry
    budget.
- **Offline-from-internet behavior is clean.** Tailscale mesh keeps working
  when the Captain's ISP is down. Claude doesn't. Ollama-first means the
  common case keeps working offline-from-internet. Claude fallback is a
  bonus, not a dependency.
- **Router picks better than phone could.** The phone doesn't see the current
  load on the 4070 Ti, whether `llama3.2` is actually loaded vs. swapped out,
  or what Anthropic's rate-limit budget looks like. The router does. This is
  a classic "put the policy where the signal lives" call.
- **Override table is auditable.** A small YAML or Python dict like:
  ```
  intent_category -> preferred_model
    "celestial"        -> "claude-haiku"     # rare, cheap, needs precision
    "navigation"       -> "ollama:llama3.2"  # common, fast, local fine
    "friend_lookup"    -> "ollama:llama3.2"  # names are local data
    "general_chat"     -> "ollama:llama3.2"  # default
    "long_reasoning"   -> "claude-sonnet"    # opt-in only
  ```
  lives in version control next to `llm_router.py`. Changes are reviewable.

**Ruled out:**
- **Phone sends `model_hint`.** Pushes policy to the client. Violates the
  "phone stays dumb about what's behind the URL" principle that makes REST
  the right transport in the first place.
- **Always-Claude-with-Ollama-failover.** Paid path by default, internet
  dependency by default, privacy-leak surface by default. Wrong direction.
- **Always-Ollama-only.** Forecloses on the case where the Captain explicitly
  wants the better answer (e.g. a physics question that Haiku can actually
  reason through). The override table solves this cleanly.

**Impact on the phone side.** None. `CommandDispatcher.onUnmatched` fires the
raw text and `context` blob to `/intent/unmatched`; SkippyTel returns `reply`
+ optional `speak`. No new fields on the phone request, no new fields on the
phone's parsed response. The router's choice is invisible (it MAY add a
`model_used` field in the response for debugging — phone ignores unknown
fields per §15 doctrine).

**Impact on SkippyTel side.**
- New module: `services/flask-api/app/llm_router.py`. Contains the override
  table, the Ollama-first-with-Claude-fallback logic, and a single
  `route(text: str, context: dict, intent_category: str | None) -> dict`
  entry point.
- Extends `services/flask-api/app/ollama_client.py` minimally (already exists;
  just used for fallback detection — `is_healthy()` already present).
- New dependency: `anthropic` Python SDK. Already has `ANTHROPIC_API_KEY`
  plumbed in `docker-compose.yml:51`.
- Intent classification happens router-side too, using a tiny Ollama prompt or
  a keyword heuristic. If intent classification itself would need Claude, that
  is a sign the request is too unstructured — fail to `general_chat` default
  category and move on.

**Risks / Phase 2 escape hatches.**
- **Risk:** Ollama model hot-swaps (loading a new model into VRAM) can spike
  latency past the 2 s budget. Mitigation: pin one model loaded by default
  (`DEFAULT_MODEL` in `docker-compose.yml` already supports this).
- **Risk:** The override table silently skews Claude-heavy over time as
  categories proliferate. Mitigation: log every Claude call with category +
  reason; review monthly.
- **Escape hatch:** If router-side intent classification proves flaky, add an
  optional `intent_category` field to the request body. The phone has enough
  context to hint ("voice command that failed local match in NavigationModule
  → probably navigation-adjacent"). Additive, not breaking — still no spec bump.
- **Escape hatch (explicit opt-in):** Per the brief §5, a future request field
  `allow_cloud: true` could let the Captain opt in to Claude routing on a
  per-request basis. Not built in Phase 1.

---

### 3. Face DB enrollment UX

**Question (verbatim, brief §7.3).**
Manual photo import is the plan. CLI on the PC? Tiny web UI served from
Flask? Does not belong in SkippyDroid.

**Recommendation.** **Flask-served web UI** — a single HTML page at
`GET /yeybuddy/enroll` plus a `POST /yeybuddy/enroll` multipart handler, both
living in the existing Flask app. Served loopback + Tailnet only (same binding
doctrine as everything else). No Tauri, no Electron, no standalone process.

**Rationale.**
- **Enrollment is rare and deliberate.** The brief's framing is "one friend at
  a time over months." A CLI script works for the first week and then the
  Captain will never remember the invocation. A browser tab is muscle memory.
- **Flask is already in the stack.** `services/flask-api` is running. Adding
  a Jinja template and a route is minutes, not days. Zero new runtime
  dependencies, zero new containers, zero new ports to document.
- **Same-origin for `/yeybuddy/*` is a feature.** The enrollment UI and the
  `/yeybuddy/identify` endpoint share a Flask blueprint, share the SQLite
  file, share the embedding pipeline. No cross-process file-lock coordination,
  no migration scripts.
- **Tailscale-as-auth extends cleanly.** The Captain can enroll from the Mac,
  the phone's browser, or the PC directly — anything on the Tailnet opens
  `http://skippy-pc:5001/yeybuddy/enroll` and gets a simple form. Matches the
  doctrine that already governs `/health`.
- **Tauri/Electron is overkill.** Both require a separate build toolchain, a
  codesigning story for the Captain's Mac, auto-update machinery, and — worst
  — they would live in their own repo under a name like "SkippyEnroller."
  Another codebase to maintain. The brief is about extending an existing Flask
  scaffold; a sibling Rust-or-Node app is the opposite of that spirit.
- **The web UI can be 60 lines.** One file upload field, a name field, a
  "save" button, a list of currently-enrolled friends with thumbnails. Uses
  vanilla HTML + a touch of CSS. No React, no build step. Consistent with
  Passthrough Protocol §16's "W3C-standard since 1996, not a Skippy invention"
  ethos.

**Ruled out:**
- **CLI script.** Works for the first few. Falls over once the Captain starts
  re-enrolling variants ("Alice with glasses," "Alice without") or wants to
  inspect what's already there.
- **Tauri/Electron.** Violates "simplest thing that works." Drags in
  codesigning, distribution, versioning.
- **SkippyDroid.** Explicitly excluded in the brief. Not considered.
- **Static Docker volume with magic file names.** "Drop photos in this folder
  named `alice_01.jpg` and they'll be enrolled." Brittle, un-auditable, can't
  delete or rename without editing the filesystem.

**Impact on the phone side.** None. The phone only calls `POST
/yeybuddy/identify` (brief §3.5). Enrollment is out-of-band.

**Impact on SkippyTel side.**
- New blueprint `services/flask-api/app/routes/yeybuddy.py` handles both
  `/yeybuddy/identify` (phone) and `/yeybuddy/enroll` (browser).
- New Jinja template at `services/flask-api/app/templates/enroll.html`.
  (First templated route in the app; enable Flask's default template folder
  by placing it there.)
- New dependency: `insightface` + `onnxruntime`. Already required by brief
  §3.5 (InsightFace `buffalo_l`).
- SQLite file lives at `services/flask-api/data/friends.db` (Docker volume
  mounted from host). Schema:
  `friends(id INTEGER PK, name TEXT UNIQUE, embedding BLOB, photo_path TEXT,
  enrolled_at TIMESTAMP)`. One row per friend, one photo per row — keep it
  tiny.
- Delete / rename handled by the same UI: `POST /yeybuddy/enroll/delete` with
  a friend id. No GraphQL, no REST CRUD purism.

**Risks / Phase 2 escape hatches.**
- **Risk:** A single photo per friend is under-sampled. InsightFace tolerates
  this for a small N but degrades. Mitigation: allow multiple photos per
  friend in Phase 2 (embedding = mean of the crop embeddings). Schema change
  is additive (`friends_photos` side table).
- **Risk:** Browser auth is "whatever Tailscale lets through." If the Captain
  ever shares his Tailnet with a guest device, that device could enroll
  faces. Mitigation: Phase 2 gate the enroll endpoints behind a simple
  `X-Skippy-Admin` header or an environment-variable secret. The phone-facing
  `/yeybuddy/identify` stays token-free (doctrine).
- **Escape hatch:** If the Captain later wants to enroll from SkippyDroid
  anyway (hypothetical reversal), the same endpoint accepts multipart uploads
  — no new server code needed.

---

### 4. Audio codec for `/translate/audio`

**Question (verbatim, brief §7.4).**
S23 can record WAV or AAC; Whisper prefers 16 kHz mono PCM. Who transcodes
(phone or PC), and at what quality tier?

**Recommendation.** **Phone records 16 kHz mono PCM WAV directly and uploads
it raw as `audio/wav` in the multipart body. PC does no transcoding — hands
the bytes straight to `faster-whisper`.** Phase 2 may add AAC support behind
a `Content-Type` negotiation if WAV bandwidth proves painful.

**Rationale.**
- **Whisper wants 16 kHz mono PCM.** Anything else is transcoding. The only
  question is where the transcoding happens. Recording at-spec on the phone
  eliminates it entirely.
- **The S23's `AudioRecord` API produces 16 kHz mono PCM natively.** It's the
  default configuration for voice capture. No resampling, no decoding, no
  bitrate guessing. The phone's VoiceEngine already picks a sample rate —
  configuring it for 16 kHz mono is a one-line change when the translate
  module lands.
- **3-second clips at 16 kHz mono 16-bit are tiny.** `16000 * 2 * 3 = 96 KB`
  per clip. Over Tailscale that's ~8 ms of wire time at 100 Mbps. AAC would
  save ~80 % of that — saving ~75 KB per clip — and cost a mobile CPU
  encoding round-trip and a PC decoding round-trip for zero user-visible
  benefit. Not worth it.
- **No transcoding = no ambiguity.** AAC has profile variants (HE-AAC, LC-AAC),
  container formats (.m4a, raw ADTS), and sample-rate choices. Every one is a
  potential "works on my phone, breaks on yours" moment. WAV PCM has none of
  those.
- **Mirrors the MJPEG doctrine from the Passthrough Protocol.** §16.2:
  "Zero codec negotiation... W3C-standard since 1996 — not a Skippy
  invention." WAV PCM is the audio equivalent. RIFF-WAVE header, raw
  interleaved samples. `faster-whisper` ingests this directly via
  `soundfile` / `numpy`.
- **Latency budget is the constraint, not bandwidth.** The brief's §3.3
  target is < 3 s end-to-end. Whisper medium takes ~200-400 ms on the 4070 Ti
  for a 3 s clip. Phone capture + upload + response is the rest. Transcoding
  steals from that budget.

**Ruled out:**
- **Phone records AAC, PC transcodes.** Adds a decode step on PC (ffmpeg or
  `pydub`), which is fine in isolation but drags in system-level audio libs
  into the Docker image. More surface, no gain.
- **Phone records WAV at 44.1 kHz, PC resamples.** Same story — resampling
  on PC is ~10 ms but it's a library dependency and a potential place to
  introduce artifacts.
- **Opus.** Better codec but same story as AAC: negotiation, decoding,
  dependencies. Not worth the Phase 1 scope.

**Impact on the phone side.** When the Translation FeatureModule lands, it
will use `AudioRecord(sampleRateInHz = 16000, channelConfig = MONO,
audioFormat = ENCODING_PCM_16BIT)` and wrap the captured shorts in a minimal
WAV header. That's ~30 lines of Kotlin, no new OkHttp behavior beyond
multipart upload. `TransportLayer.post()` at line 103 already takes an
arbitrary `RequestBody`; multipart bodies are an OkHttp primitive. No
transport-layer changes required.

**Impact on SkippyTel side.**
- New blueprint `services/flask-api/app/routes/translate.py` handling `POST
  /translate/audio`.
- New dependency: `faster-whisper` (pulls `ctranslate2`, `onnxruntime`-or-
  `torch`; target CPU build since the RTX 4070 Ti Super is shared with other
  workloads and Whisper medium runs fine on CPU for 3 s clips).
- Container: the Flask container needs the `faster-whisper` model cached.
  Either bake into the image or mount a volume (`./services/flask-api/
  models/` → `/app/models/`), set `WHISPER_MODEL_DIR`.
- Zero ffmpeg, zero `pydub`. Direct `soundfile.read(bytes)` → numpy array →
  `WhisperModel.transcribe()`.

**Risks / Phase 2 escape hatches.**
- **Risk:** Longer-than-3s clips (Captain wants to translate a whole paragraph
  of a conversation) push WAV sizes up. A 20 s clip is 640 KB — still fine
  over Tailscale, but the phone will want to chunk if clips grow to minutes.
  Mitigation: the brief §3.3 specifies "~3s clips" as the pattern; keep that
  invariant. If the usage pattern shifts, add a chunked upload or a streaming
  endpoint in Phase 2.
- **Risk:** faster-whisper's medium model might miss rare languages the
  Captain cares about. Mitigation: support `hint_lang` (brief already has
  this field) to bias; ship `large-v3` if medium underperforms.
- **Escape hatch (AAC):** If a future use case *requires* AAC (e.g. an
  embedded recorder that only emits AAC), negotiate on `Content-Type`:
  `audio/wav` → direct path, `audio/aac` → decode via a PC-side ffmpeg
  dependency. Additive, not breaking.

---

## Summary decisions table

| # | Question | Decision | Phone change? | SkippyTel touches |
|---|---|---|---|---|
| 1 | Streaming for `/intent/unmatched` | Plain POST in Phase 1. SSE reserved behind `Accept: text/event-stream` for Phase 2. | **No.** `TransportLayer.kt` unchanged. | New `routes/intent.py`, new `llm_router.py`. |
| 2 | Claude vs Ollama routing | Router-side, per-request. **Ollama-first with Claude fallback** as default; per-intent-category override table. Phone sends no model hint. | **No.** `CommandDispatcher.onUnmatched` shape unchanged. | `llm_router.py` with override table. `anthropic` SDK dependency. |
| 3 | Face DB enrollment UX | **Flask-served web UI** at `GET /yeybuddy/enroll`. No CLI, no Tauri/Electron, no SkippyDroid involvement. | **No.** Phone only calls `/yeybuddy/identify`. | New `routes/yeybuddy.py`, `templates/enroll.html`, SQLite at `data/friends.db`, `insightface` dependency. |
| 4 | Audio codec for `/translate/audio` | **Phone records 16 kHz mono PCM WAV and uploads raw.** PC does no transcoding. | Future — Translation module records at-spec. `TransportLayer.post()` signature is already multipart-capable. | New `routes/translate.py`, `faster-whisper` dependency, model cache volume. |

**Net phone-side changes required for Phase 1 SkippyTel rollout: zero.** Every
wiring change on the phone (SSE adapter, translate capture) is gated behind a
feature module that doesn't exist yet. The phone's current `TransportLayer`
and `CommandDispatcher` are forward-compatible with every recommendation
above.

**Doctrine alignment:** every recommendation honors the brief's core rules —
stateless request/response, phone stays dumb, privacy-first (Ollama default,
no cloud by default, face DB local), palette enum names in structured
responses (no structured color fields are introduced here), version envelope
on every response, simplest-thing-that-works transports at every lane.

*End of design notes. Disagreements → the Captain.*
