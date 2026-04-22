# Skippy Passthrough Transfer Protocol — v1

**Audience:** any team building an app that mounts into the Skippy OS center
viewport.
**Sample consumer:** DJ Block Planner (parallel project, first real mount).
**Status:** LOCKED for Phase 1 (April 21, 2026).
**Companion:** [`PASSTHROUGH_STANDARD.md`](./PASSTHROUGH_STANDARD.md) locks the
*doctrine* layer (palette, UX rules, focus budget, min text). This document
locks the *transport + vocabulary* layer.

> **Note on overlap.** Where this document and the Standard both touch a
> subject, this document wins for **transport + lifecycle + wire format**; the
> Standard wins for **palette + UX rules + manifest content**. Specifically,
> §5 and §6 of the Standard (WebView lifecycle and postMessage bridge) are
> **superseded by §4 and §10 of this document.** All other Standard sections
> remain in force.

---

## 0. Why this protocol exists

The Standard answered "what the app looks like." It left "how the app *talks*
to Skippy" abstract — two-way postMessage across an iframe, essentially. That
has three problems:

1. **Palette enforcement is cosmetic, not structural.** A WebView can paint
   any hex it wants; Skippy can only audit after the fact.
2. **WebView overhead is heavy.** Full JS runtime to render a grid of text
   and bars is wasteful, and doesn't play well with native Compose rendering.
3. **AR frames need their own lane.** Skippy's viewport is a real-time AR
   surface — world-pinned dots, passthrough video, future apps that composite
   camera frames. Structured UI and frame pixels cannot share a channel.

This protocol replaces the transport layer with two explicit lanes:

- **Structured lane** — scene tree + JSON patches over SSE+POST. Native
  Compose render. Palette enforced at parse.
- **Frame lane** — MJPEG over HTTP + pose SSE. Real-time AR.

Both lanes ship in `spec_version: "1"`. The frame lane is not optional or
deferred; it is the reason the glasses exist.

The single cross-project artifact is this document. There is no shared code,
no shared build dependency, no shared repo. Both teams implement against the
document; compatibility is by spec, not by library.

---

## 1. Transport

### 1.1 Roles

- **Skippy is the server.** It runs an embedded HTTP server inside the
  SkippyDroid app on the Captain's phone.
- **Views are clients.** A mounted app POSTs registration and scene updates
  to Skippy; Skippy pushes events back over SSE.

This inverts the classic "app is the server, host connects to it" model, but
it matches lifecycle reality — Skippy is the long-running anchor, views are
transient mounts.

### 1.2 Bind addresses

The Skippy HTTP server binds **only** to:

- `127.0.0.1` (loopback) — for views running on the same phone.
- Tailnet interfaces — for views running on other devices in the Captain's
  Tailscale mesh (PC, Mac, future phone).

**It does not bind to public interfaces.** Tailscale is the authentication
substrate (see §14). Serving cleartext across loopback + Tailnet is
doctrine-correct.

### 1.3 Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/passthrough/register`          | View announces itself |
| `GET`  | `/passthrough/stream?view=<id>`  | Control-channel SSE (events from Skippy → view) |
| `GET`  | `/passthrough/pose?view=<id>`    | Pose-channel SSE (high-rate head pose from Skippy → view) |
| `POST` | `/passthrough/patch`             | View sends `scene:patch` |
| `POST` | `/passthrough/intent_result`     | View acknowledges / denies / replies to an intent |
| `POST` | `/passthrough/speak`             | View requests TTS |
| `POST` | `/passthrough/log`               | View sends a log line |
| `POST` | `/passthrough/error`             | View reports `view:error` |
| `POST` | `/passthrough/request_unmount`   | View asks to close itself |
| `POST` | `/passthrough/request_full_scene`| View asks for a fresh `scene:full` |

All POST bodies are `application/json; charset=utf-8`.
All SSE streams use `text/event-stream`, one JSON event per `data:` line.

### 1.4 Frame lane endpoints

The frame lane is hosted **by the view**, not by Skippy (frames flow view →
Skippy). See §16.

| Method | Path (on the view's origin) | Purpose |
|--------|------------------------------|---------|
| `GET`  | `/stream.mjpeg?view=<id>`    | `multipart/x-mixed-replace` MJPEG stream |

---

## 2. Discovery & activation

### 2.1 Registration

Once per boot, a view POSTs `/passthrough/register`:

```json
{
  "type": "register",
  "seq": 1,
  "id": "dj_block_planner",
  "name": "DJ Block Planner",
  "manifest_url": "http://dj-pc.tail-xxxxx.ts.net:7334/.well-known/skippy-passthrough.json",
  "spec_version": "1",
  "stream_origin": "http://dj-pc.tail-xxxxx.ts.net:7334",
  "capabilities": ["speak", "focus_targets", "frame_stream"]
}
```

Skippy responds `200 OK` with:

```json
{ "type": "register_ack", "seq": 1, "accepted": true }
```

or `400` with an error payload if the manifest fails validation (see §13).

### 2.2 Activation

Captain activates a view by voice ("open DJ Block Planner") or via the
Skippy registry UI. Activation triggers the handshake in §4.

### 2.3 mDNS (Phase 2, deferred)

In Phase 2 views may advertise `_skippy-passthrough._tcp` over Tailscale
mDNS to skip manual registration. Phase 1 is explicit POST only.

---

## 3. Manifest

Manifest schema is defined in `PASSTHROUGH_STANDARD.md §4`. This protocol adds
or clarifies the following fields:

| Field | Type | Role |
|-------|------|------|
| `spec_version` | string | MUST be `"1"` for this protocol. Mismatch → refuse mount + `host:error schema_mismatch`. |
| `stream_origin` | string | Base URL under which the view serves its frame-lane endpoint (if any). Only required when `capabilities` contains `"frame_stream"`. |
| `capabilities` | string[] | Optional. Declares which protocol features the view uses. Known values: `"speak"`, `"focus_targets"`, `"frame_stream"`, `"intent_args"`. Unknown values are ignored. |

Everything else (palette, min_text_px, focus_targets, voice_commands, etc.)
is governed by the Standard.

---

## 4. Handshake (sequenced)

All steps are strictly ordered. Skippy does not proceed to step N+1 until
step N succeeds.

```
   View                                         Skippy
   ──────                                       ────────
(a)  POST /passthrough/register          ────►
                                         ◄────  200 register_ack

     [Captain activates view via voice / UI]

(b)                                      ◄────  GET /passthrough/stream?view=<id>   (view would open, BUT see below)

     ─── Actually, Skippy is the server — the view opens the SSE:

(b)  GET /passthrough/stream?view=<id>   ────►
                                         ◄────  event: scene:full
                                         ◄────  event: mount_ack

(c)  GET /passthrough/pose?view=<id>     ────►   (only if capabilities contains "frame_stream")
                                         ◄────  event: pose (60 Hz)

(d)  POST /passthrough/patch             ────►  (view starts sending scene deltas)
     ...

(e)  [Voice commands from manifest are registered on Skippy's
      CommandDispatcher keyed "<view_id>.<intent>"]

(f)  [normal operation — patches flow view→host, intents flow host→view]

(g)  [Captain says "close" OR another view requests mount]
                                         ◄────  event: before_unmount

(h)  POST /passthrough/intent_result
        { intent: "exit", ready_to_unmount: true }   ────►

     [500 ms timer — view MUST reply within window or Skippy tears down anyway]

(i)  Skippy closes SSE streams, unregisters intents, returns viewport to AR canvas.
```

### Mount ack payload

```json
{
  "type": "mount_ack",
  "seq": 1,
  "viewport_px":  [1540, 960],
  "palette_enum": ["black", "green", "white", "amber", "cyan", "violet", "red", "dim_green"],
  "context_mode": "stationary",
  "spec_version": "1",
  "glasses_connected": true
}
```

---

## 5. Scene tree — the UI vocabulary

Every scene is a tree of typed nodes:

```ts
interface Node {
  id:       string;          // stable within the view's lifetime
  type:     NodeType;        // one of the whitelist below
  props:    NodeProps;       // typed per node-type
  children?: Node[];         // layout nodes only
}
```

### 5.1 Whitelist

| Category | Node types | Summary |
|---|---|---|
| Layout | `Box`, `Column`, `Row`, `Spacer` | Compose container parity. `children[]` allowed. |
| Content | `Text`, `Canvas` | Leaf nodes. |
| Media | `FrameStream` | Leaf node. Real-time frames (§16). |
| Interaction | `FocusTarget`, `Button` | Voice-addressable / intent-binding leaves. |
| Symbology | `VerticalFillBar`, `SignalStack`, `RmsGlyph`, `ModeSegments`, `HeadingTick` | Primitives mirroring Skippy's own `compositor/Symbology.kt`. Leaf nodes, Canvas-drawn. |

Any `type` not in the whitelist → `host:error unknown_node_type`, node
omitted from render.

### 5.1a Render order

The scene is rendered by depth-first walk of the tree; **later siblings paint
on top of earlier siblings**, and children paint on top of their parent's
background. There is no separate `z_index` prop — the tree position IS the
z-order.

Practical consequence for `FrameStream`:

```
// Frames behind everything (classic AR overlay)         // Frames on top
Column                                                   Column
 ├─ FrameStream   ← paints first                          ├─ Canvas [bg]
 ├─ Text "label"                                          ├─ Text "label"
 └─ Canvas [markers]                                      └─ FrameStream  ← paints last, covers
```

Multiple `FrameStream` nodes are allowed and follow the same rule (e.g. a
main AR canvas plus a small PiP in a later tree position).

### 5.2 Props by node type

Only props listed here are accepted. Unknown prop → `host:error unknown_prop`.

#### Layout

```ts
Box      { width?:  SizeSpec, height?: SizeSpec, padding?: number, align?: Align }
Column   { width?:  SizeSpec, height?: SizeSpec, padding?: number, gap?: number, main_axis?: MainAxis, cross_axis?: CrossAxis }
Row      { width?:  SizeSpec, height?: SizeSpec, padding?: number, gap?: number, main_axis?: MainAxis, cross_axis?: CrossAxis }
Spacer   { weight?: number,   width?:  SizeSpec, height?: SizeSpec }

SizeSpec    = number | "fill" | "wrap"      // px, fill parent, or wrap children
Align       = "start" | "center" | "end"
MainAxis    = "start" | "center" | "end" | "space_between" | "space_around"
CrossAxis   = "start" | "center" | "end" | "stretch"
```

#### Content

```ts
Text {
  text:          string;
  color:         PaletteEnum;                       // REQUIRED. enum name only, never hex
  size_px:       number;                            // must be >= manifest.min_text_px
  weight?:       "normal" | "bold";
  align?:        "start" | "center" | "end";
  monospace?:    boolean;                           // default true (HUD doctrine)
  size_justify?: boolean;                           // if true, ignores size_px and size-justifies to slot
}

Canvas {
  width:  number;
  height: number;
  ops:    CanvasOp[];       // see §5.3
}
```

#### Media

```ts
FrameStream {
  width?:  SizeSpec;                                // default "fill"
  height?: SizeSpec;                                // default "fill"
  url:     string;                                  // absolute URL to MJPEG endpoint
  fit:     "cover" | "contain" | "fill";
  pose?:   boolean;                                 // if true, view subscribes to pose channel
  background?: PaletteEnum;                         // optional — painted behind frames if fit=contain. default "black".
}
```

See §16 for the frame lane itself.

#### Interaction

```ts
FocusTarget {
  focus_id: string;                                 // voice-addressable name ("deck A", "track 3")
  child:    Node;                                   // the single node that represents this target
  intent:   string;                                 // intent name fired when Captain addresses this target
}

Button {
  focus_id: string;
  label:    string;                                 // spoken label (also rendered as Text if visible_label=true)
  intent:   string;
  visible_label?: boolean;
  color?:   PaletteEnum;                            // border color; default "dim_green"
}
```

#### Symbology (mirroring `compositor/Symbology.kt`)

```ts
VerticalFillBar { fraction: number /*0..1*/, color: PaletteEnum }
SignalStack     { bars: number /*0..max_bars*/, max_bars?: number /*default 4*/, color: PaletteEnum }
RmsGlyph        { rms: number /*0..1*/, color: PaletteEnum }
ModeSegments    { active_index: number, total: number, color: PaletteEnum }
HeadingTick     { heading_deg: number, color: PaletteEnum }
```

### 5.3 Canvas ops

`Canvas.ops` is an ordered list of primitives:

```ts
type CanvasOp =
  | { op: "rect",   x: number, y: number, w: number, h: number, color: PaletteEnum, stroke?: boolean, stroke_px?: number }
  | { op: "circle", cx: number, cy: number, r: number, color: PaletteEnum, stroke?: boolean, stroke_px?: number }
  | { op: "line",   x1: number, y1: number, x2: number, y2: number, color: PaletteEnum, stroke_px?: number }
  | { op: "path",   d: string, color: PaletteEnum, stroke?: boolean, stroke_px?: number }    // SVG path subset: M,L,Q,C,Z
```

### 5.4 Palette enum

Palette props accept exactly these strings; anything else → `host:error off_palette_hex` (name retained for symmetry with the hex-rejection case):

```
"black" "green" "white" "amber" "cyan" "violet" "red" "dim_green" "dim_green_hi"
```

Opacity is applied host-side for specific semantic uses (RmsGlyph pulse, Text
fade). Views do not supply alpha.

---

## 6. Scene patches (delta wire format)

Scene patches are JSON-patch-inspired but operate over the tree by path:

### 6.1 Initial scene (host → view, or view → host via `/passthrough/patch`)

A `scene:full` event carries the entire tree. Views typically send one at
mount start; Skippy caches it (§11).

```json
{
  "type": "scene:full",
  "seq":  1,
  "root": {
    "id": "root",
    "type": "Column",
    "props": { "padding": 16, "gap": 8 },
    "children": [
      { "id": "title", "type": "Text",
        "props": { "text": "Now Playing", "color": "white", "size_px": 44 } }
    ]
  }
}
```

### 6.2 Patches

```json
{
  "type": "scene:patch",
  "seq":  42,
  "ops": [
    { "op": "set",    "path": "/root/children/0/props/text",  "value": "Up Next" },
    { "op": "insert", "path": "/root/children/1",             "value": { /* node */ } },
    { "op": "remove", "path": "/root/children/2" }
  ]
}
```

Path grammar:
- `/root` — the root node.
- `/root/children/<i>` — ith child (zero-indexed).
- `/root/children/<i>/props/<name>` — prop by name.
- Arbitrary depth allowed.

Ops:
- `set` — replace value at path. Works on primitives or whole nodes.
- `insert` — insert at array index (shifts later siblings). Requires the
  target path to be `/**/children/<i>`.
- `remove` — remove at path. Array siblings shift.

Patches are applied **in order**. If any op fails validation, the entire
patch is rejected (`host:error schema_mismatch`) and no ops apply.

### 6.3 Sequence numbers

Every patch carries a `seq` monotonic per direction. On reconnect, views may
include the last `seq` Skippy acknowledged to resume without a full refresh —
Phase 2 feature; Phase 1 always sends `scene:full` on reconnect.

---

## 7. Cadence — structured lane

- Skippy coalesces inbound patches at **10 patches/sec per view**.
- Faster arrivals are merged before the next render: last-write-wins per path.
- `insert` + later `remove` at the same index collapse to no-op.
- Views do not receive back-pressure signals. They send as fast as they like;
  Skippy absorbs.
- Rate limit is doctrine, not tunable per view.

**Frame pixels never travel the patch lane.** See §16.

---

## 8. Multi-view policy

- **One active view at a time.** The viewport cannot composite multiple
  passthrough apps.
- A new mount request while another view is active:
  1. Skippy fires `before_unmount` at the current view.
  2. Grace window: 500 ms to reply `ready_to_unmount`.
  3. Skippy tears down regardless after the grace window.
  4. Skippy mounts the new view.
- **Corners are Skippy-owned.** Views cannot place nodes outside their
  1540×960 rectangle, cannot request corner placement, cannot write to
  LeftBar / RightBar. Any attempt is ignored silently (not even logged —
  the whitelist prevents it structurally).
- **Sidebars are Skippy-owned.** Same rule as corners.

---

## 9. Voice intent routing

### 9.1 Registration (at mount)

Skippy iterates `manifest.voice_commands` and calls
`CommandDispatcher.register(...)` for each, keying the intent id as:

```
<view_id>.<intent>
```

e.g. `dj_block_planner.advance`, `dj_block_planner.deck_swap`. This prevents
view-A and view-B from colliding on the same intent name.

### 9.2 Match (during operation)

When the dispatcher matches a phrase bound to a view's intent, Skippy emits
on the control-channel SSE:

```json
{
  "type":   "intent",
  "seq":    88,
  "intent": "advance",
  "args":   { },
  "source": "voice"
}
```

### 9.3 Reply

Views POST `/passthrough/intent_result` within 1000 ms:

```json
{
  "type":   "intent_result",
  "seq":    88,
  "intent": "advance",
  "result": "ok",                       // "ok" | "deny" | "error"
  "speak":  "advancing to track four"   // optional: Skippy TTSs this back
}
```

Missing reply → Skippy logs `intent_no_reply`, no user-visible effect.

### 9.4 Unregistration (at unmount)

Skippy calls `CommandDispatcher.unregister("<view_id>.<intent>")` for every
registered intent before tearing down SSE.

### 9.5 Reserved intents (view cannot shadow)

- `help`, `inventory`, `what can I do` — Skippy-owned.
- `exit`, `close` — Skippy-owned; teardown path.
- `cancel`, `stop`, `never mind` — Skippy-owned.

(Mirrors Standard §4.)

---

## 10. Event catalog — v1

### 10.1 Host → view (SSE, control channel `/passthrough/stream`)

| Type | Payload summary | When |
|---|---|---|
| `scene:full` | `{ root }` | Handshake; or on `request_full_scene`; or on view-reconnect |
| `mount_ack` | `{ viewport_px, palette_enum, context_mode, glasses_connected }` | After `scene:full` on mount |
| `intent` | `{ intent, args, source }` | Voice/text match on registered phrase |
| `context_change` | `{ mode }` (`stationary` / `walking` / `driving`) | ContextEngine mode transition |
| `palette_update` | `{ palette_enum }` | Reserved — palette edits not expected in v1 |
| `listening` | `{ active: bool }` | Skippy STT mic open/close |
| `pose_lost` | `{ reason }` | Glasses disconnected; `FrameStream` views should degrade |
| `host:error` | `{ error_type, message, detail? }` | Host-side validation failures |
| `before_unmount` | `{ reason }` (`"user_exit"`, `"preempted"`, `"error"`) | Teardown imminent |

### 10.2 Host → view (SSE, pose channel `/passthrough/pose`)

| Type | Cadence | Payload |
|---|---|---|
| `pose` | 60 Hz when tracking live, 10 Hz degraded, 0 Hz disconnected | see §16.3 |

### 10.3 View → host (POST)

| Type | Endpoint | Payload |
|---|---|---|
| `register` | `/passthrough/register` | `{ id, name, manifest_url, spec_version, stream_origin?, capabilities? }` |
| `scene:full` | `/passthrough/patch` | `{ root }` |
| `scene:patch` | `/passthrough/patch` | `{ ops }` |
| `intent_result` | `/passthrough/intent_result` | `{ intent, result, speak? }` |
| `speak` | `/passthrough/speak` | `{ text, priority? }` |
| `log` | `/passthrough/log` | `{ level, message, detail? }` |
| `view:error` | `/passthrough/error` | `{ node_id?, error_type, message }` |
| `request_unmount` | `/passthrough/request_unmount` | `{ reason? }` |
| `request_full_scene` | `/passthrough/request_full_scene` | `{ }` |

### 10.4 View → host (frame lane, view-hosted GET)

| Method | Path | Payload |
|---|---|---|
| `GET` | `/stream.mjpeg?view=<id>` | `multipart/x-mixed-replace` of JPEG parts — see §16 |

### 10.5 Envelope rules

Every JSON message carries at minimum:

```ts
{
  type: string;
  seq:  number;      // monotonic per direction per channel
  ...
}
```

Unknown top-level fields are ignored. Unknown `type` on view-facing SSE is
ignored (forward-compat).

---

## 11. State persistence

- Skippy caches the **last `scene:full` snapshot per view id** (or the state
  after the last applied patch) for 10 minutes after unmount.
- Reactivating a view within that window → Skippy sends the cached scene as
  the `scene:full` event on handshake, so the viewport paints familiar
  content immediately while the view starts sending patches.
- Views may force a full refresh by POSTing
  `/passthrough/request_full_scene`; Skippy discards its cache and the view
  posts a fresh `scene:full`.
- View-side caching is optional and opaque to Skippy.

---

## 12. Audio

- Views do not play raw audio. They request TTS.
- `POST /passthrough/speak`:
  ```json
  { "type": "speak", "seq": 12, "text": "track saved", "priority": "low" }
  ```
  `priority` ∈ `"low" | "normal" | "high"`. Default `"normal"`.
- Skippy owns the TTS pipeline. It may:
  - queue the utterance if the Captain is currently being spoken to,
  - drop low-priority utterances during mic-open windows,
  - replace in-flight utterances if a higher-priority one arrives.
- Skippy emits `listening { active: true }` on the control channel whenever
  the STT mic is open. Views SHOULD avoid requesting `speak` while
  `listening == true` unless the utterance is high priority.

---

## 13. Error protocol

### 13.1 View-originated errors

`POST /passthrough/error`:

```json
{
  "type":       "view:error",
  "seq":        99,
  "node_id":    "deck_a_tile",
  "error_type": "render_failed",
  "message":    "missing required prop"
}
```

### 13.2 Host-originated errors

Delivered on the control-channel SSE:

```json
{
  "type":       "host:error",
  "seq":        100,
  "error_type": "unknown_node_type",
  "message":    "node type 'VideoPanel' not in v1 whitelist",
  "detail":     { "node_id": "xyz" }
}
```

Enumerated host-side `error_type` values:

| error_type | Meaning |
|---|---|
| `unknown_node_type` | `type` not in §5 whitelist |
| `unknown_prop` | prop name not valid for the node type |
| `off_palette_hex` | palette prop carried a hex or unknown enum |
| `schema_mismatch` | top-level message / patch op malformed |
| `spec_version_mismatch` | `register.spec_version != "1"` |
| `manifest_invalid` | fetched manifest failed validation |
| `patch_path_invalid` | path does not resolve in current tree |
| `frame_decode` | MJPEG part failed to decode (rate-limited; emitted ≤ 1 per 5 s per view) |

### 13.3 Unrecoverable errors

- Spec-version mismatch → mount refused, red banner in viewport.
- Manifest invalid → mount refused.
- Patch schema mismatch → patch dropped, view remains mounted.
- Frame decode → frame dropped, view remains mounted.

Red banner uses Skippy's native `SizeJustifiedText` (HudPalette.Red); views
do not control this surface.

---

## 14. Security — Phase 1

- HTTP server binds loopback + Tailnet only (see §1.2).
- **No JWT, no mTLS, no shared secret — Tailscale is the auth.** Anyone who
  can reach the phone's Tailnet address is already authenticated by
  WireGuard.
- No TLS termination on Skippy side; HTTP is fine over private mesh.
- Views running on-phone loopback: trivially authenticated by "being on the
  same device."
- Views running on PC/Mac over Tailnet: authenticated by Tailscale's mesh
  identity.

### Phase 2 (deferred, not in v1)

- Per-view API tokens issued at register.
- Optional mTLS for Tailnet hops.
- Capability-scoped tokens (e.g. `speak` but not `request_full_scene`).

The Phase 1 assumption **explicitly depends on** Skippy never being exposed
outside the Captain's Tailnet. If that invariant is ever violated the
protocol is not safe as specified.

---

## 15. Versioning

- `spec_version` is an integer string. Current: `"1"`.
- Views MUST include it in `register`; Skippy echoes it in `mount_ack`.
- Mismatch policy:
  - Skippy refuses mount with `host:error spec_version_mismatch`.
  - Log line includes both versions.
  - No fallback to older spec.
- Bumps are **integer**, not semver. A new version is a new protocol — old
  and new may run side-by-side on Skippy during transitions.
- Additive changes (new optional manifest field, new optional prop, new SSE
  event type) do **not** bump the spec version; views ignore unknown fields
  and events per §10.5.
- This document is the source of truth. Implementation ≠ doc → the
  implementation is a bug.

---

## 16. Frame lane — real-time AR pixels

The structured lane cannot carry frames. Cadence doctrine holds it to
10 patches/sec, and JSON patches are not a pixel transport. This section
defines the separate lane for real-time frame data.

### 16.1 The `FrameStream` node

A view declares a rectangle for streamed frames by placing a `FrameStream`
node in its scene tree:

```json
{
  "id": "ar_canvas",
  "type": "FrameStream",
  "props": {
    "url":  "http://dj-pc.tail-xxxxx.ts.net:7334/stream.mjpeg?view=dj_block_planner",
    "fit":  "cover",
    "pose": true,
    "background": "black"
  }
}
```

Size + position inherit from parent layout like any other node. Multiple
`FrameStream` nodes in one scene are allowed (e.g. a main AR canvas plus a
small picture-in-picture).

### 16.2 Wire format — MJPEG over HTTP

- `Content-Type: multipart/x-mixed-replace; boundary=frame`
- Long-lived GET; Skippy holds the connection open for the lifetime of the
  view's `FrameStream` being mounted.
- Each part:
  ```
  --frame\r\n
  Content-Type: image/jpeg\r\n
  Content-Length: <bytes>\r\n
  X-Frame-Seq: <n>\r\n
  X-Pose-Seq: <n>\r\n       (optional — pose the producer rendered against)
  X-Pose-Tms: <ms>\r\n      (optional — millisecond timestamp of the pose used)
  \r\n
  <JPEG bytes>\r\n
  ```

- JPEG baseline profile; YUV 4:2:0; any resolution (Skippy scales to the
  FrameStream rectangle via `fit`).
- Zero codec negotiation. Android decodes with `BitmapFactory`. W3C-standard
  since 1996 — not a Skippy invention. Keeps the cross-project quarantine
  intact.

### 16.3 Pose channel — host → view

When `FrameStream.props.pose == true` the view is expected to open:

```
GET /passthrough/pose?view=<id>
```

SSE events:

```json
{
  "type":        "pose",
  "seq":         12345,
  "t_ms":        1713726400123,
  "pos_m":       [0.0, 0.0, 0.0],
  "quat":        [1.0, 0.0, 0.0, 0.0],
  "fov_h_deg":   45.0,
  "fov_v_deg":   28.0,
  "viewport_px": [1540, 960]
}
```

- Quaternion ordering: `[w, x, y, z]`.
- Coordinates: ENU (x=East, y=North, z=Up) in local tangent plane anchored
  at mount start.
- Cadence:
  - **60 Hz** when glasses tracking is live.
  - **10 Hz** when degraded (IMU-only, no visual tracking).
  - **0 Hz** when glasses disconnected; Skippy emits `pose_lost` on the
    control channel.

### 16.4 Latency budgets

Pose-to-photon (glasses IMU → photon exit):

| Topology | Target | Hard ceiling |
|---|---|---|
| View on same phone (loopback) | < 50 ms | < 80 ms |
| View over Tailnet (PC/Mac) | < 100 ms | < 150 ms |

Budget includes: pose emit → producer render → JPEG encode → HTTP wire →
JPEG decode → Compose frame commit.

### 16.5 Frame pacing

- No host-side rate limit on frames.
- Producer paces itself; targeting **display refresh** (90 Hz on glasses,
  120 Hz native panel) is the aim.
- If producer exceeds display refresh, Skippy drops excess at decode.
- Skippy MAY drop any frame older than **2× the pose interval** (i.e. ~33 ms
  at 60 Hz pose) to prevent stale-frame pile-up in the decoder queue.
- If producer lags > 500 ms, Skippy continues painting the last frame and
  overlays an amber `stale` pixel indicator (host-drawn; not view-visible in
  the scene tree).

### 16.6 Frame lane failure modes

| Failure | View-visible effect |
|---|---|
| MJPEG GET fails / connection refused | `host:error frame_stream_unreachable`; red banner inside the FrameStream rectangle; rest of scene renders normally |
| MJPEG connection drops mid-stream | Skippy retries with exponential backoff (500 ms, 1 s, 2 s, 4 s, 8 s, cap 8 s); amber `reconnecting` overlay |
| JPEG part malformed | Frame dropped; `host:error frame_decode` rate-limited to 1 per 5 s per view |
| Pose channel fails | `pose_lost` event on control channel; producer decides whether to keep rendering (degraded) or pause its MJPEG emission |

### 16.7 Security

Same as §14 — loopback + Tailnet only. Frame URLs in `FrameStream.props.url`
MUST resolve to the view's declared `stream_origin` (from `register`) or
loopback. Skippy refuses to fetch from any other origin.

### 16.8 Non-goals in v1

- **WebRTC.** Reserved for Phase 2 only if MJPEG latency measurements force it.
- **H.264 / H.265 / AV1.** Same.
- **Audio in frame stream.** Frame lane is visual-only. Audio uses §12.
- **Bidirectional frames** (view ← host). Skippy never sends frames to views.

---

## 17. Agnostic pattern — future passthrough apps

This protocol is intentionally generic. The vocabulary below is **not
DJ-specific**; it is Skippy's general Passthrough Vocabulary. Future apps
implement the same contract:

| App | Uses | Lane | Skippy sees |
|---|---|---|---|
| **Star Map** ★ | `FrameStream` pinned by pose (stars rendered producer-side against live head quaternion); `Text` labels for constellations | **Frame — reference impl** | Palette-clean star field pinned to the real sky; voice commands "show Orion", "jump to year 1969" |
| **DJ Block Planner** ★ | `Row`/`Column` of `Text` + `Button`, Amber for BPM, Cyan for track IDs | **Structured — reference impl** | Pure scene-tree; voice commands "advance", "deck swap" |
| Coding Assistant HUD | `Column` of `Text`, `FocusTarget`s for dictation | Structured | Voice: "explain this", "next suggestion" |
| Teleprompter | Scrolling `Text` with patches every 100 ms | Structured | Voice: "pause", "faster", "slower" |
| Object Measurement | `FrameStream` + `Canvas` overlay anchored by pose | Both | Voice: "measure", "clear" |

The two ★ apps are **Test Vehicle Zero** for the protocol — one per lane.
The Star Map was chosen for the frame lane specifically because it requires
no ML models, no external inference, and no camera access: producer just
projects celestial coordinates through the current pose quaternion and emits
JPEGs. If the Star Map looks right pinned to the sky, the protocol's pose
channel + MJPEG lane are correct.

Each app is a separate codebase that imports nothing from Skippy and nothing
from any other passthrough app. They all speak palette-enum names, the node
whitelist, and the patch ops defined here. Skippy renders them with the
same Compose parser. The result: every mounted app automatically looks like
Skippy (palette enforced at parse), sounds like Skippy (TTS throttled
host-side), and obeys the Captain's one-input voice dispatcher.

This is the pattern the Captain named: Test Vehicle Zero is the DJ Block
Planner. After it lands, the star-field app, the coding HUD, and everything
else are the same contract — only content differs.

---

## 18. Worked example — minimal mount

A view that displays a single title and a battery fill bar, updating the
bar every second.

### 18.1 Manifest

```json
{
  "name": "Hello Skippy",
  "version": "0.1.0",
  "passthrough_spec_version": "1",
  "entry_url": "/glasses",
  "aspect_ratio": "16:10",
  "palette": ["#000000", "#FFFFFF", "#00FF00"],
  "min_text_px": 28,
  "max_focus_targets": 1,
  "voice_commands": [
    { "phrase": ["hello"], "intent": "hello" }
  ]
}
```

### 18.2 Register

```
POST /passthrough/register
{
  "type": "register", "seq": 1,
  "id": "hello_skippy", "name": "Hello Skippy",
  "manifest_url": "http://hello.tail-xxxxx.ts.net:9000/.well-known/skippy-passthrough.json",
  "spec_version": "1"
}
```

### 18.3 Open SSE + send initial scene

```
GET /passthrough/stream?view=hello_skippy
  ← event: scene:full      (empty shell Skippy cached, if any)
  ← event: mount_ack       { viewport_px: [1540, 960], ... }

POST /passthrough/patch
{
  "type": "scene:full", "seq": 1,
  "root": {
    "id": "root", "type": "Column",
    "props": { "padding": 32, "gap": 16, "main_axis": "center", "cross_axis": "center" },
    "children": [
      { "id": "title", "type": "Text",
        "props": { "text": "Hello, Captain.", "color": "white", "size_px": 64 } },
      { "id": "bar", "type": "VerticalFillBar",
        "props": { "fraction": 0.5, "color": "green" } }
    ]
  }
}
```

### 18.4 Patch every second

```
POST /passthrough/patch
{
  "type": "scene:patch", "seq": 2,
  "ops": [
    { "op": "set", "path": "/root/children/1/props/fraction", "value": 0.51 }
  ]
}
```

### 18.5 Handle intent

```
← event: intent    { intent: "hello", args: {}, source: "voice" }

POST /passthrough/intent_result
{
  "type": "intent_result", "seq": 99,
  "intent": "hello", "result": "ok",
  "speak": "hello yourself"
}
```

### 18.6 Clean shutdown

```
← event: before_unmount  { reason: "user_exit" }

POST /passthrough/intent_result
{
  "type": "intent_result", "seq": 100,
  "intent": "exit", "result": "ok", "ready_to_unmount": true
}
```

---

## 19. Open questions for the DJ team

Resolve these before first mount lands:

1. **Origin & port** — what hostname/port will the DJ app serve on?
   (Skippy needs it for `manifest_url` registration and to avoid firewalling.)
2. **Frame lane use?** — does the DJ Block Planner need `FrameStream` in v1,
   or is it all structured (Text + bars + tiles)? If not needed, the DJ side
   can skip §16 entirely for v1.
3. **Intent args** — the Standard's voice_commands have no args. Does the
   Planner want parameterised intents (e.g. "select track 3" → `args: { track: 3 }`)?
   If yes, we add `"intent_args"` capability and an arg-extraction grammar in a
   minor addition (non-breaking).
4. **Polling vs push** — the Planner is free to push patches whenever state
   changes. Is there any need for Skippy to `poll` on an interval? If no,
   drop `polling_interval_s` from manifest; Phase 1 simpler.

---

## 20. Change control

- This document is the source of truth for the passthrough protocol.
- Breaking changes bump `spec_version` (integer).
- Additive changes are made in place; views ignore unknown fields/events.
- Proposals for changes: route through the Captain.

---

*Maintained by Skippy the Magnificent under the authority of Captain Aaron
Rhodes. Companion document: [`PASSTHROUGH_STANDARD.md`](./PASSTHROUGH_STANDARD.md).*
