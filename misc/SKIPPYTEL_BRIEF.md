# SkippyTel — Handoff Brief

**Status:** v1 · April 22 2026 · authored from the SkippyDroid side
**For:** the engineers spinning up the SkippyTel project
**Companion docs (same repo):** `PASSTHROUGH_PROTOCOL.md`, `PASSTHROUGH_STANDARD.md`

SkippyTel is the PC-side AI brain that SkippyDroid (Android on a Samsung
Galaxy S23, driving VITURE Luma Ultra glasses) calls for inference that
can't run on the phone: LLM chat, Whisper transcription, face recognition,
precise celestial ephemeris. This brief is everything SkippyTel needs to
know about what the phone expects and how SkippyTel fits into the wider
Skippy system.

**Cross-project quarantine holds.** SkippyTel implements from this
document. Neither repo imports from the other. Only this brief crosses
the boundary.

---

## 1. What SkippyTel IS (and is NOT)

**IS.** An upstream AI inference service. Stateless HTTP over Tailscale.
Phone calls PC, PC answers, phone renders. Services wrapped in Docker
Compose on the Captain's RTX 4070 Ti Super PC. Partially real today:
Flask API on `:5001`, Ollama on `:11434`, Physics MCP scaffolded in
`services/physics-mcp/`.

**IS NOT.**
- **Not a passthrough view.** Passthrough apps mount *into* the phone's
  1540×960 viewport via `PASSTHROUGH_PROTOCOL.md` (scene-tree + MJPEG on
  phone port `:47823`). SkippyTel is the other direction — phone → PC.
  Different concern, different port family, different role. If SkippyTel
  ever wants its own dashboard UI on the glasses, it does so by
  implementing the Passthrough Protocol as a separate concern.
- **Not a push channel.** Phone hotspots drop. Tailscale mesh can
  partition. Everything is phone-initiated request/response. SSE allowed
  where the phone is the long-poller, not PC-initiated.
- **Not a frame transport.** Real-time AR frames are phone-local
  (loopback MJPEG between in-process producers and the passthrough host).
  SkippyTel never sees pose or frames.

---

## 2. Transport contract

| Property | Value |
|---|---|
| Binding | Tailscale mesh + loopback. **No public internet exposure.** |
| Auth | **Tailscale IS the auth.** Same doctrine as passthrough protocol. No JWT, no mTLS, no shared secret in Phase 1. |
| Cleartext | Allowed inside the mesh. Phase 2 revisits if the service ever leaves Tailscale. |
| Hostname the phone uses | `http://skippy-pc:5001` — literal string in SkippyDroid's `MainActivity.kt` (`pcUrl`). Change in lockstep with any hostname migration. |
| Phone HTTP client | OkHttp, 3s connect / 5s read timeout. Pings `/health` every 10s. |
| Degradation | `pcState = UNKNOWN / ONLINE / OFFLINE`. Phone modules that require network skip render when OFFLINE. |
| Spec version | `spec_version: "1"` in every response body. Integer increments on breaking changes. Phone may refuse mismatches. |

---

## 3. Endpoints the phone already expects (or plans to hit)

Ordered by when the phone will need them. Each row is the minimum
contract — SkippyTel may add response fields; the phone tolerates extras.

### 3.1 `GET /health`
**Used by:** SkippyDroid `TransportLayer` every 10 s.
**Contract:** Any 200 OK → phone flips to ONLINE. Response body ignored
for classification but used for latency measurement.
**Drives:** RightBar SignalStack glyph:

| round-trip | bars |
|---|---|
| `< 50 ms`  | 4 |
| `< 150 ms` | 3 |
| `< 400 ms` | 2 |
| reachable  | 1 |
| unreachable| 0 |

**Latency budget:** sub-100ms warm. Slow `/health` = always 1 bar.
**Existing:** Already implemented at `services/flask-api/app/routes/health.py`.
Leave alone — phone depends on it.

### 3.2 `POST /intent/unmatched`
**Used by:** SkippyDroid `CommandDispatcher.onUnmatched` (currently
logs-only; wiring pending).
**Purpose:** When the phone can't classify a voice command locally
(`navigate to X`, `cancel`, `help`), it escalates the raw text here
for LLM interpretation.
**Request:**
```json
{
  "text": "what time is sunset tomorrow in tokyo",
  "source": "voice",
  "context": { "lat": 37.77, "lng": -122.41, "mode": "walking" }
}
```
**Response:**
```json
{
  "spec_version": "1",
  "reply":  "5:48 PM JST tomorrow.",
  "speak":  "Sunset in Tokyo tomorrow is five forty-eight PM.",
  "actions": []
}
```
`reply` renders as text in the BottomCenter chat slot. `speak` (if
present) is handed to on-device TTS. `actions` is reserved for a future
capability where the LLM triggers named phone intents (e.g.
`{"intent":"navigate","args":"the park"}`).

**Latency budget:** < 2 s for conversational feel. Streamed SSE reply
welcome if `Accept: text/event-stream`.

### 3.3 `POST /translate/audio`
**Used by:** Future Translation feature module.
**Purpose:** Other-speaker language detected and translated to English
subtitles in the TopCenter slot.
**Request:** `multipart/form-data` with `audio` field (WAV or AAC, ~3s
clips), optional `hint_lang`.
**Response:**
```json
{
  "spec_version": "1",
  "detected_lang": "ja",
  "english_text":  "The train is leaving from platform nine.",
  "confidence": 0.94
}
```
**Backend:** `faster-whisper` medium.
**Latency budget:** < 3 s end-to-end (capture → subtitle).

### 3.4 `GET /starmap?lat=&lng=&t_iso=`
**Used by:** Future Star Map feature module (phone-native version,
distinct from the Passthrough Star Map producer in `apps/SkippyStarMap/`).
**Purpose:** Exact celestial positions at a given (lat, lng, time).
**Response:**
```json
{
  "spec_version": "1",
  "t_iso": "2026-04-22T23:00:00Z",
  "objects": [
    { "name": "Venus",  "type": "planet", "az_deg": 272.4, "alt_deg": 18.1, "mag": -4.2 },
    { "name": "Vega",   "type": "star",   "az_deg": 51.0,  "alt_deg": 67.3, "mag":  0.03 }
  ]
}
```
**Backend:** Thin adapter over `services/physics-mcp/`. Do not duplicate
ephemeris math.
**Latency budget:** cacheable; phone may cache 60 s per (lat, lng).

### 3.5 `POST /yeybuddy/identify`
**Used by:** Future Friend Recognizer.
**Purpose:** Identify a cropped face against the local friend database.
**Request:** `multipart/form-data` with `image` (face crop JPEG).
**Response:**
```json
{
  "spec_version": "1",
  "match": { "name": "Alice", "confidence": 0.87 }
}
```
or `{ "spec_version":"1", "match": null }` when no match ≥ threshold.
**Backend:** InsightFace `buffalo_l` embedding + cosine match against
SQLite. **The friend DB never leaves PC.**
**Latency budget:** < 1 s per face (camera delivers crops sparsely).

### 3.6 `GET /battery`
**Used by:** Future battery-panel aggregator.
**Purpose:** PC's own power state, to roll into the phone's
battery-panel summary.
**Response:**
```json
{
  "spec_version": "1",
  "level": 100,
  "source": "ac",
  "plugged": true,
  "ups_state": "online"
}
```
`source ∈ {ac, battery, ups}`. If on UPS and mains drops, flip `source`
to `battery` so the phone can highlight the PC as at-risk.
**Latency budget:** sub-second, polled every 60 s.

### 3.7 `POST /tts/speak` *(optional, Phase 2)*
**Used by:** Future — only if on-device Android TTS quality is
insufficient.
**Request:** `{ "text": "...", "voice": "skippy", "priority": "normal" }`.
**Response:** audio stream or a short-lived URL to fetch the audio.
**Current plan:** phone does on-device TTS (free, offline-capable);
this endpoint only lands if we measure a quality problem.

---

## 4. Doctrine inherited from SkippyDroid

- **Palette discipline for structured responses.** If SkippyTel ever
  returns colored status (severity, mode tag), use palette enum names
  only — `"cyan"`, `"amber"`, `"red"`, `"green"`, `"violet"`, `"black"`,
  `"white"` — never hex. The phone's palette is the authority
  (`compositor/HudPalette.kt`).
- **No ellipsis, no scrollbars.** Any text SkippyTel returns that lands
  in a HUD slot must be designed to wrap cleanly. Keep replies tight.
  A four-sentence answer beats a paragraph.
- **Stateless request/response by default.** Phone assumes it can
  retry any request after a partition without side effects.
- **Version field doctrine.** Every response body includes
  `spec_version: "1"`. Integer increments on breaking changes. Phone
  may refuse mismatches.
- **Quarantine holds.** SkippyTel does not import from SkippyDroid,
  SkippyAR, SkippyGlassesMac, SkippyStarMap, or any DJ sister project.
  Protocol details SkippyTel needs live here in this document, copied
  as inline comments into SkippyTel's own code.

---

## 5. Privacy posture

- **Face DB never leaves PC.** SQLite on the 4070 Ti box.
- **Voice audio never leaves the mesh.** faster-whisper runs locally.
- **Claude API routing is deliberate.** The existing
  `docker-compose.yml` passes `ANTHROPIC_API_KEY` into the Flask
  service, implying PC proxies Claude. Fine for Phase 1 but means PC
  must be online to Anthropic — design the Ollama-local fallback for
  when it isn't. Never route personal data (voice transcripts, faces,
  GPS trails) to Anthropic without an explicit opt-in from the Captain.

---

## 6. Existing infra SkippyTel should build on (not replace)

| What | Where | Role |
|---|---|---|
| Flask router | `services/flask-api/app/main.py` | Top-level dispatcher. Add blueprints; do not fork. |
| `/health` | `services/flask-api/app/routes/health.py` | Already exists. Phone depends on it. |
| `/physics` blueprint | `services/flask-api/app/routes/physics.py` | Bolt `/starmap` on top of this. |
| Physics MCP | `services/physics-mcp/physics_mcp/` | Celestial math. `/starmap` wraps it. |
| Ollama | docker-compose `ollama` on `:11434` | Default local LLM. Flask API already wired via `OLLAMA_BASE_URL`. |
| Docker Compose | `docker-compose.yml` at repo root | Add new services here, not as sibling stacks. |

---

## 7. Open questions for SkippyTel to resolve

These are deliberately left unresolved — SkippyTel picks them up.

1. **MCP vs REST naming.** The Captain calls this "the MCP server." If
   SkippyTel literally implements Anthropic's Model Context Protocol,
   the phone needs an MCP client, not OkHttp. If SkippyTel is a REST
   API that *hosts* MCP servers internally for tool use, the phone
   stays with OkHttp. **The phone currently assumes REST.** Pick a
   lane and record it.
2. **Streaming for `/intent/unmatched`.** SSE to the phone so partial
   LLM tokens render progressively? Or wait for full response? Affects
   perceived latency more than raw TTFT.
3. **Claude vs Ollama routing.** Who decides which model handles a
   request — the phone (sends `model_hint`), the router (picks per
   intent category), or is it always Ollama first with Claude fallback?
4. **Face DB enrollment UX.** Manual photo import is the plan. CLI on
   the PC? Tiny web UI served from Flask? Does not belong in
   SkippyDroid.
5. **Audio codec for `/translate/audio`.** S23 can record WAV or AAC;
   Whisper prefers 16 kHz mono PCM. Who transcodes (phone or PC), and
   at what quality tier.

---

## 8. Files in the Skippy repo SkippyTel may want to read

None should be imported. All are read-only references.

| Path | Why |
|---|---|
| `apps/SkippyDroid/.../layers/TransportLayer.kt` | Phone HTTP client — sets the `/health` cadence, latency tiers, and timeout budgets SkippyTel must hit. |
| `apps/SkippyDroid/.../MainActivity.kt` (line ~53) | `pcUrl = "http://skippy-pc:5001"` — canonical PC hostname. |
| `apps/SkippyDroid/.../layers/CommandDispatcher.kt` | `onUnmatched` hook — the escalation path for voice commands. |
| `docker-compose.yml` | Existing service topology. |
| `services/flask-api/app/main.py` | Existing Flask router. |
| `services/physics-mcp/physics_mcp/` | Celestial math; `/starmap` wraps it. |
| `misc/PASSTHROUGH_PROTOCOL.md` §§1, 14, 15 | Transport / auth / versioning doctrine to mirror. |
| `misc/PASSTHROUGH_STANDARD.md` | Palette + fit doctrine that structured responses must honor. |

---

## 9. What the phone will build next (heads-up for SkippyTel priority)

In rough order the phone intends to start calling SkippyTel:

1. `/health` — already called, lands in the RightBar signal glyph.
2. `/intent/unmatched` — first real use once voice works past
   locally-classified intents.
3. `/translate/audio` — Translation feature module.
4. `/starmap` — Star Map feature module (phone-side fallback, separate
   from the standalone `SkippyStarMap` passthrough producer).
5. `/yeybuddy/identify` — Friend Recognizer.
6. `/battery` — PC node of the battery-panel aggregator.

If SkippyTel picks the order of implementation: this list is the
phone's demand order.

---

*End of brief. Questions → the Captain.*
