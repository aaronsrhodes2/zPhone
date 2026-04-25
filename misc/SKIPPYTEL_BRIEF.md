# SkippyTel — Handoff Brief

**Status:** v2 · April 25 2026 · updated from the SkippyDroid side
**v1 authored:** April 22 2026
**For:** engineers and external services integrating with SkippyTel
**Companion docs:** `PASSTHROUGH_PROTOCOL.md`, `PASSTHROUGH_STANDARD.md`

SkippyTel is the PC-side hub that all Skippy phone apps call for AI
inference, media, and service discovery. It runs on the Captain's RTX
4070 Ti Super PC inside Docker Compose, reachable via Tailscale at
`http://skippy-pc:3003`.

**Cross-project quarantine holds.** SkippyTel implements from this
document. Neither repo imports from the other. Only this brief crosses
the boundary.

---

## 1. What SkippyTel IS (and is NOT)

**IS.**
- An upstream AI inference service (LLM, Whisper, face recognition)
- A **service control plane** — the single source of truth for what
  capabilities exist in the Skippy ecosystem, who has them, and how
  phones should surface them
- REST over Tailscale. Stateless request/response. SSE allowed where the
  phone long-polls. Phone initiates; PC answers.

**IS NOT.**
- **Not a passthrough view.** Passthrough apps mount into the phone's
  1540×960 viewport via `PASSTHROUGH_PROTOCOL.md`. SkippyTel is the
  other direction.
- **Not a push channel.** Tailscale can partition. Design for
  phone-initiated only.
- **Not a data plane for external services.** When a service (e.g.
  Bilby on the Mac) registers a `base_url`, phone clients hit that
  service **directly** over Tailscale. SkippyTel only holds the
  manifest entry. See §10.

---

## 2. Transport contract

| Property | Value |
|---|---|
| Binding | Tailscale mesh + loopback. **No public internet exposure.** |
| Auth | **Tailscale IS the auth.** No JWT, no mTLS in Phase 1. Registration endpoint adds `X-Skippy-Token` shared secret (see §11). |
| Cleartext | Allowed inside the mesh. |
| Hostname (phone → SkippyTel) | `http://skippy-pc:3003` — real phone via Tailscale MagicDNS. |
| Hostname (emulator → SkippyTel) | `http://10.0.2.2:3003` — AVD host loopback. |
| Phone HTTP client | OkHttp, 3s connect / 5s read timeout. Pings `/health` every 10s. |
| Degradation | `pcState = UNKNOWN / ONLINE / OFFLINE`. Phone modules skip render when OFFLINE. |
| Spec version | `spec_version: "1"` in every response body. Integer increments on breaking changes. |

---

## 3. Core endpoints

### 3.1 `GET /health`
200 OK → phone flips to ONLINE. Body drives RightBar signal glyph:

| round-trip | bars |
|---|---|
| `< 50 ms`  | 4 |
| `< 150 ms` | 3 |
| `< 400 ms` | 2 |
| reachable  | 1 |
| unreachable| 0 |

### 3.2 `POST /intent/unmatched`
Voice command escalation. Request: `{ text, source?, context? }`.
Response: `{ spec_version, reply, speak, tier }`.
`tier` identifies which model answered: `"local"` (Ollama), `"claude"`,
`"gemini"`, etc. Streamed SSE available via `Accept: text/event-stream`.
**Budget: < 2 s.**

### 3.3 `POST /translate/audio` / `POST /translate/text`
Whisper transcription + Gemini translation. Audio: multipart WAV/AAC.
Text: `{ text, target_lang? }`. Response: `{ detected_lang, english_text, confidence }`.
**Budget: < 3 s.**

### 3.4 `GET /starmap?lat=&lng=&t_iso=`
Celestial positions. Response: `{ objects: [{name, type, az_deg, alt_deg, mag}] }`.
Thin adapter over `services/physics-mcp/`. **Cacheable.**

### 3.5 `POST /heybuddy/identify`
Face crop JPEG → `{ match: {name, confidence} | null }`.
InsightFace buffalo_l + cosine against local SQLite. **Face DB never leaves PC.**
**Budget: < 1 s.**

### 3.6 `GET /battery`
PC power state: `{ level, source, plugged, ups_state }`.
`source ∈ {ac, battery, ups}`. Polled every 60 s.

### 3.7 `POST /image` *(stub — not yet deployed)*
Returns 503 with `error_type: "not_deployed"`. Endpoint reserved for a
future first-party Stable Diffusion service inside the Docker stack.
The `[IMAGE: prompt]` sentinel is preserved in the intent protocol;
`/intent/unmatched` strips the tag from replies until the service lands.

### 3.8 `GET /vault/bootstrap`
Phone bootstrap config: SkippyTel URL, APK download base, feature flags.
Called on first launch to self-configure.

---

## 4. Service discovery — `GET /services` *(the control plane)*

The phone's primary integration point after `/health`. Every SkippyChat
and SkippyDroid instance polls this every **5 minutes** to discover the
live ecosystem.

### Request
```
GET /services
```

### Response
```json
{
  "spec_version": "1",
  "refreshed_at": "<ISO-8601>",
  "services": [ <ServiceManifest>, ... ]
}
```

### ServiceManifest shape
```json
{
  "id":           "bilby",
  "name":         "Bilby",
  "tagline":      "Automated DJ — Traktor decks or Drive shuffle",
  "available":    true,
  "status_detail":"Mac Bilby reachable (Traktor mode)",
  "base_url":     "http://skippy-mac:7334",
  "display": {
    "mode":          "overlay",
    "hud_zone":      "topcenter",
    "companion_app": null
  },
  "voice_triggers": ["what's playing", "now playing", "bilby next"],
  "endpoints": {
    "status": "/bilby/status",
    "next":   "/bilby/next"
  }
}
```

### Field semantics

| Field | Meaning |
|---|---|
| `id` | Machine key. Stable. Used as icon cache key, deep-link param, registration id. |
| `available` | Live-computed at request time (TCP probe or config check). Sub-50ms. |
| `status_detail` | Human-readable reason. Shown in SkippyDroid services panel and widget tooltip. |
| `base_url` | **Non-null = external service on Tailscale.** Phone routes data traffic to this host directly. SkippyTel is the control plane only. Null = traffic goes through SkippyTel. |
| `display.mode` | `"overlay"` render in HUD zone · `"companion"` launch companion app · `"inline"` render inside chat · `"background"` invisible to user |
| `display.hud_zone` | SkippyDroid zone name: `"topcenter"`, `"topend"`, `"viewport"`, etc. Null for non-overlay. |
| `display.companion_app` | Android package name to launch. Widget tap resolves this first. |
| `voice_triggers` | Phrases that activate the service. `"*"` = catch-all (Skippy AI only). |
| `endpoints` | Short name → relative path. Appended to `base_url` when set, else to SkippyTel base. |

### Phone-side behaviour on receipt

1. **SkippyChat `ChatViewModel`** — loads `dynamicTriggers` into
   `KeywordScanner`: flat map of `phrase → service_id`, sorted
   longest-first. New services become voice-activatable within 5 min,
   no rebuild needed.
2. **SkippyDroid `ServiceRegistry`** — exposes `services: StateFlow`,
   drives the `ServicesPanelModule` live list.
3. **`ServiceGridWidget`** — Android home screen widget populated from
   this manifest. Fetches per-service icons from `GET /services/<id>/icon`.
   Tap action: `companion_app` → launch app · `base_url` → open in
   browser · neither → SkippyChat hint.

### Service icon endpoint
```
GET /services/<service_id>/icon   →  128×128 PNG
```
Generated on first request (Stable Diffusion when available, Pillow
fallback otherwise). Cached permanently in `state/icons/<id>.png`.
Delete the file to force regeneration.

---

## 5. Dynamic service registration protocol *(pending implementation)*

External services (Mac Bilby, future Mac/PC daemons) self-register with
SkippyTel at startup instead of being hardcoded in `services.py`. Phone
picks them up on the next 5-minute poll with no config changes on either
side.

### Registration
```
POST /services/register
X-Skippy-Token: <SKIPPY_REGISTRATION_TOKEN from .env>
Content-Type: application/json

{
  "id":           "bilby",
  "name":         "Bilby",
  "tagline":      "...",
  "base_url":     "http://skippy-mac:7334",
  "display":      { "mode": "overlay", "hud_zone": "topcenter" },
  "voice_triggers": ["what's playing", "bilby next", ...],
  "endpoints":    { "status": "/bilby/status", "next": "/bilby/next" },
  "ttl_seconds":  90
}
```
Response: `204 No Content` on success, `401` on bad token.

### Heartbeat (keep-alive)
```
POST /services/heartbeat/<id>
X-Skippy-Token: <token>
```
Call every **60 seconds**. Miss two consecutive → SkippyTel sets
`available: false` on the manifest entry. Triggers go dark on phones
within 5 minutes.

### Clean deregister
```
DELETE /services/<id>
X-Skippy-Token: <token>
```
Called on SIGTERM / clean shutdown.

### Semantics
- Registrations persist to `state/registered_services.json` — survive
  SkippyTel container restarts.
- `GET /services` merges static entries (hardcoded in `services.py`)
  with dynamic registrations. **Dynamic registration wins on id
  collision** — lets an external service override its own static stub
  once it starts self-registering.
- `SKIPPY_REGISTRATION_TOKEN` env var. Tailscale is the outer auth
  layer; this token is tamper-proofing only.

### Mac Bilby startup sequence (once this is built)
```python
# On startup
POST /services/register  { ...bilby manifest... }

# Every 60s (background thread)
POST /services/heartbeat/bilby

# On SIGTERM
DELETE /services/bilby
```

---

## 6. Current service manifest (April 25 2026)

| id | mode | base_url | available |
|---|---|---|---|
| `intent` | companion → `local.skippy.chat` | null (lives in SkippyTel) | always |
| `bilby` | overlay → `topcenter` | `http://skippy-mac:7334` | TCP probe |
| `dropship` | companion → `local.skippy.gallery` | null | Drive folder configured |
| `image` | inline | null | **stub — always false** |
| `translate` | inline | null | always |
| `ups` | overlay → `topend` | null | TCP probe :9338 |
| `heybuddy` | inline | null | always |
| `jellyfin` | companion → `org.jellyfin.mobile` | `http://skippy-pc:8096` | TCP probe |
| `starmap` | overlay → `viewport` | null | **stub — always false** |
| `vault` | background | null | always |

---

## 7. Doctrine

- **Palette discipline.** Colored status uses palette enum names only:
  `"cyan"`, `"amber"`, `"red"`, `"green"`, `"violet"`. Never hex.
- **No ellipsis, no scrollbars.** Any text in a HUD slot must wrap
  cleanly. Tight replies beat prose.
- **Stateless by default.** Phone may retry any request after partition.
- **Version field.** Every response includes `spec_version: "1"`.
- **Quarantine holds.** SkippyTel does not import from SkippyDroid,
  SkippyAR, SkippyGlassesMac, or any DJ project. Protocol details live
  here and are copied as comments into SkippyTel's own code.

---

## 8. Privacy posture

- **Face DB never leaves PC.** SQLite on the 4070 Ti box.
- **Voice audio never leaves the mesh.** faster-whisper runs locally.
- **Image generation** (when deployed) runs locally on the same box.
  No image prompt or pixel ever reaches a third-party API.
- **Claude/Gemini routing is deliberate.** `ANTHROPIC_API_KEY` and
  `GEMINI_API_KEY` are in `.env`. Only text intent queries reach these
  APIs — never voice audio, face crops, GPS, or image data.
  Ollama (local) is always the default tier.

---

## 9. Open questions / resolved

| # | Question | Status |
|---|---|---|
| 1 | MCP vs REST naming | **Resolved** — phone stays on REST/OkHttp. MCP is internal SkippyTel tooling only. |
| 2 | Streaming for `/intent/unmatched` | **Resolved** — SSE supported. `Accept: text/event-stream`. |
| 3 | Claude vs Ollama routing | **Resolved** — explicit phrase triggers per tier. No auto-fallback. See `routes/intent.py`. |
| 4 | Face DB enrollment UX | Open — manual import CLI planned. |
| 5 | Audio codec for `/translate/audio` | Open — phone records WAV; Whisper handles it server-side. |
| 6 | Dynamic service registration | **Designed (§5), implementation pending.** |
| 7 | TTS / voice profiles | **Future sprint** — Skippy/Bilby/Nagatha voices from R.C. Bray characterizations. Local XTTS v2. Never leaves the mesh. |

---

## 10. Files SkippyTel may reference (read-only)

| Path (zPhone repo) | Why |
|---|---|
| `apps/SkippyDroid/.../layers/TransportLayer.kt` | Sets `/health` cadence and timeout budgets. |
| `apps/SkippyDroid/.../MainActivity.kt` | Canonical `pcUrl`. |
| `apps/SkippyDroid/.../features/services/ServiceManifest.kt` | Kotlin mirror of the JSON shape — keep in sync manually. |
| `apps/SkippyChat/.../model/ServiceManifest.kt` | SkippyChat's local copy — same quarantine rule. |
| `apps/SkippyChat/.../model/KeywordScanner.kt` | How dynamic triggers are consumed client-side. |
| `apps/SkippyDroid/.../features/widget/ServiceGridWidget.kt` | How the home screen widget consumes the manifest. |
| `misc/PASSTHROUGH_PROTOCOL.md §§1,14,15` | Transport / auth / versioning doctrine. |

---

*End of brief. Questions → the Captain.*
