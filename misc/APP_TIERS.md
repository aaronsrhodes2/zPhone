# Skippy Application Tiers

*Doctrine — April 23 2026. Names the four kinds of things we call "apps"
so future plans stop reaching into four different documents to disambiguate.*

This document names tiers; it does **not** replace the per-tier specs. It
cross-references them.

---

## Tier A — Chrome Modules

**What:** SkippyDroid's in-process HUD chrome — clocks, compass, battery,
coordinates, speed, services panel, sidebars, left/right symbology.

**Where it runs:** Inside the SkippyDroid APK. No IPC, no network.

**Protocol:** None. Kotlin interface `FeatureModule` with `Overlay()` /
`GlassesOverlay()` composables, a `HudZone`, and a `zOrder`.

**Mounts into:** The six corners + two bands + two sidebars (never
`HudZone.Viewport` — that is reserved for Tier B/C).

**Examples:**
- `ClockModule`, `CompassModule`, `BatteryModule`, `SpeedModule`,
  `CoordinatesModule`, `NavigationModule`, `ServicesPanelModule`,
  `LeftBarModule`, `RightBarModule`, `TelepromptModule`.

**Trust model:** Implicit — same process as the host.

**Lifecycle:** `start()` / `stop()` bound to `MainActivity.onStart` /
`onStop`. No registration step.

**Cross-references:** `compositor/HudZones.kt`, `layers/FeatureModule.kt`,
`PASSTHROUGH_STANDARD.md` (palette doctrine).

---

## Tier B — Passthrough Views, Local Transport

**What:** Applications that register with SkippyDroid to mount a UI into
`HudZone.Viewport`, where the producer runs **on the phone** (same
process, or a sibling process reachable over loopback).

**Where it runs:** On the phone. Either in-process (`MockPassthroughView`
today) or as a separate Android app hitting `127.0.0.1:47823`.

**Protocol:** Full `PASSTHROUGH_PROTOCOL.md` v1 — register, scene-tree +
patches, MJPEG frame lane, pose SSE, voice-commands, manifest.

**Mounts into:** `HudZone.Viewport` exclusively. One active at a time
(§8 one-active policy).

**Trust model:** Loopback is its own boundary. The phone's own kernel
enforces that nothing outside the device can speak loopback. No Tailnet
check needed for Tier B.

**Lifecycle:** Producer POSTs `/passthrough/register` → Captain activates
(voice or tap) → Skippy opens control + frame channels → view streams
scene patches / frames → view or Captain triggers unmount.

**Cleartext allowance:** The app's `network_security_config.xml` must
permit cleartext to `127.0.0.1` / `localhost`. Added April 23 2026
(Session 11e) when `MockPassthroughView` first hit its own loopback
endpoint and was blocked.

**Examples:**
- `MockPassthroughView` — in-process proto-StarMap (Session 11e).
- Future `apps/SkippyStarMap` — real Android service as a sibling app.

**Cross-references:** `PASSTHROUGH_PROTOCOL.md`, `PASSTHROUGH_STANDARD.md`,
`compositor/passthrough/`.

---

## Tier C — Passthrough Views, Remote Transport

**What:** Same as Tier B, but the producer runs on another machine on
the Tailnet — the Mac, the SkippyTel PC, a NAS, a future IoT node.

**Where it runs:** Anywhere on the mesh.

**Protocol:** Same `PASSTHROUGH_PROTOCOL.md` v1 as Tier B. The wire
shape is **identical** — a producer doesn't know if it's Tier B or C;
only the phone's server-side trust guard knows.

**Mounts into:** `HudZone.Viewport`, same one-active-at-a-time rule as
Tier B.

**Trust model:** **Tailnet IS the gate.** The phone's
`PassthroughServer` accepts registrations only from the Tailscale CGNAT
range (`100.64.0.0/10`) plus loopback. No JWT, no mTLS, no shared
secret. This mirrors the same decision in
`PASSTHROUGH_PROTOCOL.md §14` and `SKIPPYTEL_BRIEF.md`.

**Cleartext allowance:** Tailnet cleartext is allowed inside the mesh —
listed hostnames live in `network_security_config.xml`. Current
allowlist: `skippy-pc`, `aarons-macbook-pro`, `100.64.0.0/10` (by
rule, not CIDR — add specific tailnet IPs if MagicDNS fails).

**Emulator dev loop:** A physical phone on the Tailnet talks to remote
producers directly. An emulator has no Tailnet interface, so we use
`adb forward tcp:47823 tcp:47823` (host:47823 → emulator:47823) so Mac
producers can hit the phone's embedded server via loopback from the
host. Target: `make droid-bridge-register`. Reverse direction for
emulator-reaches-DJ on the Mac: `make droid-bridge-dj`
(`adb reverse tcp:7334 tcp:7334`).

**Examples:**
- DJ Organizer on `aarons-macbook-pro:7334` (Session 11e demo).
- Future real `apps/SkippyStarMap` producer if it graduates off the
  phone and onto skippy-pc.

**Cross-references:** Same as Tier B, plus `SKIPPYTEL_BRIEF.md` for
the parallel trust doctrine on the inference-service side.

---

## Tiers B and C are the same tier, except where they're not

From the protocol's perspective there is one tier: "Passthrough View."
From the **trust** perspective there are two: local (loopback) and
remote (Tailnet-gated). A producer's code is identical either way; the
phone's server-side guard admits or refuses based on origin IP.

Doctrine: **name the two variants when the distinction matters** (trust
discussions, dev-loop bridging, cleartext config) and **collapse to
"passthrough view" when it doesn't** (protocol, manifest, voice
commands, UX).

---

## Tier D — SkippyTel Inference Services

**What:** HTTP endpoints on the Skippy Tel Network PC that the phone
calls for inference or data the phone can't produce locally.

**Where it runs:** The Skippy Tel Network PC (RTX 4070 Ti Super,
Docker Compose; Flask/FastAPI + Ollama + faster-whisper + InsightFace
+ Physics MCP).

**Protocol:** REST (phone-initiated request/response) or SSE when the
phone is the long-poller. Defined in `SKIPPYTEL_BRIEF.md`. Contract
fields include `spec_version`.

**Mounts into:** Nothing. Tier D is **data, not pixels**. Replies
render through existing Tier A modules (teleprompter for a spoken
reply, `SizeJustifiedText` banner in `BottomCenter` for a chat reply,
etc.).

**Trust model:** Tailnet. Same as Tier C. No public-internet exposure.

**Lifecycle:** Stateless per call, except `/health` which is polled
every 10s by `TransportLayer` and drives the SignalStack symbology.

**Examples (from SKIPPYTEL_BRIEF):**
- `GET /health`
- `POST /intent/unmatched` (LLM fallback for voice commands)
- `POST /translate/audio` (faster-whisper)
- `GET /starmap?lat&lng&t_iso` (Physics MCP)
- `POST /yeybuddy/identify` (InsightFace face-DB)
- `GET /battery/:node` (PC's own state; aggregated by the phone)

**Cross-references:** `SKIPPYTEL_BRIEF.md`, `layers/TransportLayer.kt`.

---

## Tier D-sub — Model Routes

**What:** A shape of Tier D where the endpoint is specifically an LLM
inference call — `/intent/unmatched` is today's only example. Inside
SkippyTel the call is routed to Ollama, Claude, or some future model;
the phone never learns which.

**Why this is a sub-tier, not a tier:** The phone's wire contract is
identical to any other Tier D endpoint. The routing decision lives
entirely on SkippyTel's side. If the phone ever gains a `model_hint`
parameter (Phase 2 of Session 9), it's a routing *suggestion*, not a
different protocol.

**Escape hatch:** If a model becomes UI-bearing — e.g., a "Claude
Conversation" panel that wants to paint a scrollback in the Viewport —
it graduates to a **Tier C view whose backend is a Tier D-sub model
route**. The two tiers compose cleanly.

---

## What deliberately is **not** a tier

### SkippyChat
A sibling Android app (Session 9). UI-only, phone-local, talks Tier D
(`/intent/unmatched` with `source="text"`). Not a Tier-C view — it
does not mount into SkippyDroid's Viewport. It's a second surface on
the same device, sharing (Phase 2) SkippyTel's conversation log.

### SkippyGlassesMac
A macOS app that renders to its own VITURE glasses, connected to the
Mac. Orthogonal to the phone tiers entirely. If it ever wanted to
appear on the phone's Viewport it would do so by implementing the
Tier C protocol as a producer on the Mac.

### The glasses (VITURE Luma Ultra)
Hardware. Not an app tier. The glasses display is a second Presentation
to which the SkippyDroid compositor mirrors Tier A chrome + the active
Tier B/C view.

### Feature flags / context gates
`ContextEngine` decides which Tier A modules are active given motion /
time / place. Not a tier — a visibility policy over Tier A.

---

## Tier-by-tier quick reference

| Tier | Runs on | Protocol | Mounts into | Trust gate | Canonical doc |
|---|---|---|---|---|---|
| A — Chrome Modules | Phone, in-process | `FeatureModule` interface | Corners / bands / sidebars | N/A (in-process) | `PASSTHROUGH_STANDARD.md` |
| B — Passthrough, local | Phone (loopback) | `PASSTHROUGH_PROTOCOL.md` v1 | `HudZone.Viewport` | Loopback-only | `PASSTHROUGH_PROTOCOL.md` |
| C — Passthrough, remote | Tailnet peer | `PASSTHROUGH_PROTOCOL.md` v1 | `HudZone.Viewport` | Tailscale CGNAT | `PASSTHROUGH_PROTOCOL.md` |
| D — Inference services | SkippyTel PC | REST / SSE | Nothing (data only) | Tailscale CGNAT | `SKIPPYTEL_BRIEF.md` |
| D-sub — Model routes | SkippyTel PC | Inside a Tier D endpoint | Nothing | Same as Tier D | `SKIPPYTEL_BRIEF.md` |

---

## Versioning

This doctrine is v1. Each tier owns its own spec's version field — `A`
has no wire protocol and so no version; `B/C` share
`PASSTHROUGH_PROTOCOL.md §15` (`spec_version: "1"`); `D` has
`SKIPPYTEL_BRIEF.md`'s `spec_version` requirement. When a new tier
appears (e.g. if we ever build Tier E — device-to-device peer apps
between phones and watches, perhaps), increment this document's
version and add a row.
