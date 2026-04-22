# Skippy Passthrough Viewport Standard — v1

**Audience:** any team building an app intended to mount inside the Skippy OS
center viewport (phone mirror + VITURE Luma Ultra glasses display).
**Sample consumer:** the DJ Block Planner (`localhost:7334`).
**Status:** LOCKED for Phase 1 (April 21, 2026).
Breaking changes bump `passthrough_spec_version`.

---

## 1. What you are mounting into

Skippy OS reserves a single **1540 × 960 px**, **16:10** rectangle at the
center of the 1920 × 1200 chrome frame. You get that rectangle and nothing else.

```
┌───────────────────────────────────────────────────────┐
│ TopStart  │     TopCenter (world / inbound)   │ TopEnd │  ← Skippy's chrome
├───────────┼───────────────────────────────────┼────────┤
│           │                                   │        │
│  LeftBar  │   ╔══════════════════════════╗    │ Right  │
│  (symbol  │   ║                          ║    │  Bar   │
│   only)   │   ║   YOUR APP LIVES HERE    ║    │ (sym-  │
│           │   ║   1540 × 960 · 16:10     ║    │  bol   │
│           │   ║                          ║    │  only) │
│           │   ╚══════════════════════════╝    │        │
│ ~180 px   │                                   │ ~200 px│
├───────────┼───────────────────────────────────┼────────┤
│BotStart   │   BottomCenter (Captain / STT)    │ BotEnd │  ← Skippy's chrome
└───────────────────────────────────────────────────────┘
                    1920 × 1200 canvas
```

Everything outside the inner rectangle belongs to Skippy OS. Do not reach past
your bounds, do not draw full-screen, do not assume pixel 0,0 is the top-left
of the glasses display.

---

## 2. Hard rules (non-negotiable — violation = refusal to mount)

1. **No pointer interactions.** No click handlers, no hover states, no drag,
   no scrollbars. The Captain has no mouse, no trackpad, no touchscreen. Your
   app is **voice-driven or read-only**.
2. **Text-only input, routed through Skippy.** You do not open your own input
   fields. All input arrives via the intent bridge (see §6).
3. **Additive-light palette.** Background is `#000000`. Content uses only the
   seven palette hues (see §3). No gradients, no photographic backgrounds, no
   colored fills behind text.
4. **16:10 aspect ratio respected.** Your layout must look correct when the
   host letterboxes or pillarboxes it to fit. If you author at 1540 × 960,
   you will render pixel-perfect inside Skippy.
5. **Minimum text size 22 px at 1540×960.** Anything smaller is unreadable
   at glasses distance. No exceptions.
6. **No ellipsis. No scrollbars. No cutoff text.** Long content wraps. If
   wrap alone can't fit, you marquee or paginate. The Captain should never
   see `…`.
7. **Focus budget ≤ 7.** At any moment there are at most 7 nameable targets
   the Captain could address by voice (track tiles, buttons, panels). More
   than that and the voice mental model collapses.
8. **No audio autoplay.** Mic is shared with Skippy's STT. Don't fight for it.
   If your app plays audio, it must duck or pause when `skippy:listening`
   event fires on the intent bridge.
9. **No local persistence assumed.** Treat the mount as stateless. If you
   need state, GET it from your own backend on mount; POST changes when the
   Captain commits.
10. **No navigation away from the entry URL.** Your `entry_url` is your
    universe. Internal routing is fine; `window.location = "…"` to a
    different origin is not.

---

## 3. Canonical palette (exact hex only)

| Name     | HEX       | Use                                               |
|----------|-----------|---------------------------------------------------|
| Black    | `#000000` | Background. Always.                               |
| Green    | `#00FF00` | Primary accent · selection · OK · "self"          |
| White    | `#FFFFFF` | Primary content text (titles, Captain's words)    |
| Amber    | `#FFCC00` | Numeric metrics (BPM, time, speed, percent)       |
| Cyan     | `#00CCFF` | Identifiers (clock, IDs, hashes, coordinates)     |
| Violet   | `#818CF8` | Special class tag (instrumental, alt mode)        |
| Red      | `#FF3344` | Alert · listening · live — pulsing allowed        |
| DimGreen | `#003300`–`#005500` | Borders · placeholders · idle outlines |

Rule: **no text ever sits on a non-black fill.** Color is for the glyph, not
the background.

---

## 4. Manifest — `/.well-known/skippy-passthrough.json`

Serve this JSON at the root of your origin. Skippy OS fetches it before
mounting; if missing, malformed, or non-compliant the mount is refused and
Skippy paints a red `SizeJustifiedText` banner in the viewport explaining why.

```json
{
  "name": "DJ Block Planner",
  "version": "1.0.0",
  "passthrough_spec_version": "1",
  "entry_url": "/glasses",
  "aspect_ratio": "16:10",
  "palette": ["#000000", "#00FF00", "#FFCC00", "#FFFFFF"],
  "min_text_px": 22,
  "max_focus_targets": 7,
  "voice_commands": [
    { "phrase": ["play next", "next track"],     "intent": "advance"      },
    { "phrase": ["swap", "switch decks"],        "intent": "deck_swap"    },
    { "phrase": ["surprise me", "random"],       "intent": "random_pick"  },
    { "phrase": ["save", "save me", "bookmark"], "intent": "save_state"   },
    { "phrase": ["close", "exit"],               "intent": "exit"         }
  ],
  "polling_interval_s": null,
  "auth_required": false
}
```

### Field reference

| Field                      | Type                  | Notes                                                                                                          |
|----------------------------|-----------------------|----------------------------------------------------------------------------------------------------------------|
| `name`                     | string                | Human-readable app name. Shown in inventory if Captain asks "what's mounted."                                  |
| `version`                  | semver string         | Your app version.                                                                                              |
| `passthrough_spec_version` | string                | Must be `"1"` for this spec. Bump = breaking.                                                                  |
| `entry_url`                | string (path)         | Path under your origin. Skippy loads `https://<your-origin><entry_url>` in the viewport WebView.               |
| `aspect_ratio`             | enum                  | `"16:10"` for Phase 1 (only value accepted).                                                                   |
| `palette`                  | `string[]`            | Subset of §3. Declare only the hues you actually use. Validator compares against rendered frame (Phase 2).     |
| `min_text_px`              | integer               | Smallest text size your app will ever render. Must be ≥ 22.                                                    |
| `max_focus_targets`        | integer               | ≤ 7. The maximum simultaneous voice-addressable elements.                                                      |
| `voice_commands`           | `VoiceCommand[]`      | Your custom verbs. See below.                                                                                  |
| `polling_interval_s`       | number \| null        | If you need Skippy to refresh your mount every N seconds, declare it. `null` = never (you drive your own DOM). |
| `auth_required`            | boolean               | If true, Skippy passes a signed JWT in the `X-Skippy-Identity` header so your backend knows it's the Captain.  |

### VoiceCommand shape

```ts
{
  phrase: string[],   // accepted utterances — lowercase, no punctuation
  intent: string      // your app's internal intent name (snake_case)
}
```

Skippy matches the Captain's STT output against the `phrase` array (substring,
case-insensitive). On match, Skippy fires the intent at your app via the
bridge (§6).

### Reserved intent names (you may not shadow these)

- `help` / `inventory` / `what can I do` — Skippy handles these itself;
  enumerates current live verbs.
- `exit` / `close` — Skippy handles mount teardown. You receive a final
  `skippy:before_unmount` event to persist state, then your WebView is
  destroyed.
- `cancel` / `stop` / `never mind` — Skippy-level cancel for nav / timers.
  Not routed to you.

---

## 5. Lifecycle

```
  [Captain utters "open DJ Block Planner"]
                │
                ▼
  Skippy OS resolves name → origin  (service discovery, TBD)
                │
                ▼
  GET https://<origin>/.well-known/skippy-passthrough.json
                │  (validator: schema, palette, focus-budget, min-text)
                ▼
     [manifest OK?] ── no ──► red banner in viewport, mount refused
                │ yes
                ▼
  WebView loads <origin><entry_url>
                │
                ▼
  Skippy posts {type:"skippy:mounted", manifest}  to app via postMessage
                │
                ▼
         [app runs]   ← intents flow in via postMessage (§6)
                │
                ▼
  Captain utters "exit"
                │
                ▼
  Skippy posts {type:"skippy:before_unmount"}
                │
                ▼
  WebView destroyed, viewport returns to AR canvas
```

---

## 6. Intent bridge (postMessage contract)

Skippy OS and your app communicate over `window.postMessage`. Both sides
use structured JSON with a `type` discriminator.

### Skippy → App (inbound events)

```ts
// mount complete, here's your manifest echoed back
{ type: "skippy:mounted", manifest: PassthroughManifest }

// a registered voice command fired
{ type: "skippy:intent", intent: string, args: Record<string, string> }

// Captain is speaking — duck audio if you play any
{ type: "skippy:listening", active: boolean }

// teardown imminent — persist any state now
{ type: "skippy:before_unmount" }

// viewport size changed (Captain rotated or plugged/unplugged glasses)
{ type: "skippy:resize", width: number, height: number }
```

### App → Skippy (outbound, optional)

```ts
// app is ready; pass this before rendering interactive content
{ type: "app:ready" }

// ask Skippy to speak something through its TTS
{ type: "app:speak", text: string }

// expose a dynamic focus target list (optional; helps "what can I do")
{ type: "app:focus_targets", targets: string[] }

// app-initiated exit (e.g. user said "save" and you want to close)
{ type: "app:request_unmount" }
```

All messages must be posted to `window.parent` (Skippy embeds you in an
iframe / WebView). Do not target `"*"` — use the origin Skippy gave you in
`skippy:mounted`.

---

## 7. Worked example — DJ Block Planner conformance

What the DJ Block Planner team needs to do to mount:

1. **Serve `/.well-known/skippy-passthrough.json`** at `localhost:7334` (dev)
   / whatever origin in prod. Use the JSON in §4 verbatim as a starting point.
2. **Publish `/glasses` entry URL** — a cut-down view of the planner that
   fits 1540×960 and respects §2 rules. No click-to-select; selection is
   voice-driven ("select track 3", "focus deck A").
3. **Replace all `Color.*` literals with the palette in §3.** Amber for BPM
   numbers, Cyan for track IDs, White for titles, Green for the currently
   active deck, Red if something is flashing-live, Violet for the
   "instrumental" tag.
4. **Listen on `window.addEventListener("message", …)`** for `skippy:intent`
   events and map them to the planner's internal actions:
   - `advance` → `nextTrack()`
   - `deck_swap` → `swapDecks()`
   - `random_pick` → `surpriseMe()`
   - `save_state` → `saveCurrentSet()`
5. **Post `{type:"app:ready"}`** once the planner has rendered initial state.
6. **Drop any `onClick` / `onMouseEnter` handlers** that were decorative —
   they're not just unused on glasses, they're forbidden by §2.
7. **Pause WebAudio** (if any) on `skippy:listening: true`, resume on
   `skippy:listening: false`.

Once those seven changes land, the planner mounts cleanly. Skippy OS will
load it on "open DJ Block Planner" and the Captain's voice drives it from
there.

---

## 8. What Skippy OS validates (Phase 1 → Phase 2)

**Phase 1 (now):**
- Manifest fetchable, valid JSON, schema matches §4.
- `passthrough_spec_version == "1"`.
- `aspect_ratio == "16:10"`.
- `min_text_px >= 22`.
- `max_focus_targets <= 7`.
- No reserved intent names used.

**Phase 2 (planned — not built yet):**
- Rendered-frame palette audit (sample pixels, reject non-palette hues).
- Text size measurement (OCR floor enforcement).
- Focus-target count enforcement (querySelectorAll on declared target class).
- Pointer-handler scan (document-level capture: any handled click/touch rejects the mount).

Phase 2 is designed so Phase 1 apps that play fair don't need changes when
validation tightens.

---

## 9. Questions for the Music Organizer

These are the open items to resolve before the first DJ Block Planner mount:

1. **Origin + discovery.** Will the DJ app run at a fixed Tailscale hostname,
   or does Skippy OS resolve `"DJ Block Planner"` → origin via a registry?
   (Skippy-side: we can ship a hardcoded registry for Phase 1.)
2. **Auth model.** The Captain is the only user. Do we skip auth entirely
   (`auth_required: false`) or mint a JWT from the PC MCP and validate on
   each request? (Recommendation: skip for Phase 1, add JWT in Phase 2.)
3. **Asset delivery.** Album art, track metadata — served from the DJ app's
   own origin, or does it call the Music Organizer's MCP? (Skippy doesn't
   care, but it affects your own polling cadence.)
4. **Offline degradation.** What does the planner show if its backend is
   unreachable? Per §2.10, a red `SizeJustifiedText` banner saying
   `"DJ BACKEND UNREACHABLE"` at 22 px minimum is the standard fallback.

---

## 10. Versioning + change control

- Minor additions (new optional manifest fields, new Skippy → App event
  types) do NOT bump `passthrough_spec_version`. Apps ignore unknown
  fields/events.
- Breaking changes (renaming fields, tightening limits, changing palette)
  bump the version. Skippy OS refuses to mount apps declaring an older
  spec version once a new one ships.
- This document is the source of truth. If a Skippy OS implementation
  detail disagrees with this doc, the doc is right and the implementation
  is a bug.

---

*Maintained by Skippy the Magnificent under the authority of Captain
Aaron Rhodes. Questions, corrections, or proposed changes: route through
the Captain.*
