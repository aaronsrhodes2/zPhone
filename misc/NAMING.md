# Naming Doctrine — zPhone, Skippy, and the Three-Word Namespace

**Status:** v2 · April 24 2026
**For:** anyone (PC agent, future Claude, future Captain) reading this
repo and wondering why the GitHub project, the local folder, and the
package namespace don't all agree — and why everything is in three-word
form instead of Java reverse-DNS.

The names are **deliberately** not unified. The package namespace is
**deliberately** not Java reverse-DNS. This document is why.

---

## 1. The Captain's quotes (primary sources)

**Quote A — zPhone vs Skippy** (April 24 2026, morning):

> "We are using a zPhone, and this is the software, but when we use the
> facilities of the phone, the response to us is skippy, so these tools
> inside the zPhone are all skippy tools: skippy.chat, skippy.droid (I
> thought it was funny, I laughed for reals, beercan), skippy.star,
> skippy.navigate, skippy.etc..."

**Quote B — repurposing the leading segment** (April 24 2026, afternoon):

> "I always thought the com. package prefix was underused by developers
> and it was intended and can be used for more. Let's plan a convention
> that allows us to bridge URI and package in a way that does not need
> to follow the .com .info .org because that's organizational
> information that I do not need, I am an individual. So let's use that
> silly tail as a naming convention that helps us identify the type of
> source it is and helps us match up with the URI in a sensible way
> that helps the user be informed about what it is. We should still use
> the three.word.format though."

That's the doctrine. The rest of this document is bookkeeping around it.

---

## 2. The two names

| Layer | Name | Role |
|---|---|---|
| Device · project label · GitHub repo | **zPhone** | The phone-side surface of the Skippy AR system. The thing you hold, the thing you ship. |
| AI persona · brand · who responds | **Skippy** | The voice that answers when you use a zPhone facility. Also responds to: Skipster, Beercan, Admiral. |

The split is functional, not accidental:

- **You speak to Skippy.** Skippy speaks back. That relationship has a
  brand, and it's older than the device that hosts it.
- **You hold a zPhone.** It's the slab the Captain points at across the
  room. zPhone is the project name because GitHub needs a noun, and
  "Skippy" is taken by the persona.
- **The tools inside are all Skippy's tools** — not the zPhone's tools.
  When `local.skippy.chat` answers a question, that's Skippy answering
  through the zPhone, not the zPhone answering on Skippy's behalf.

---

## 3. The three-word namespace rule

Every identifier inside the system follows the form:

    <source>.<owner>.<tool>

- **`<source>`** — where the thing lives. **Closed vocabulary**, exactly
  three values:

  | Source | Means | URI / host bridge |
  |---|---|---|
  | `local`     | Lives on the zPhone itself                  | loopback / on-device |
  | `skippytel` | Lives on the PC brain (SkippyTel)           | `skippy-pc` over Tailnet |
  | `remote`    | Anything else (third-party producers, etc.) | `<brand>-host` (Tailnet or Internet) |

- **`<owner>`** — the brand or producer. Skippy's own tools say
  `skippy`; a third-party DJ Music Organizer says `dj`; an external
  SkippyStarMap producer says `starmap`.
- **`<tool>`** — the specific facility (`chat`, `droid`, `navigate`,
  `clock`, `passthrough`, …). Underscore-separated when the verb has
  modifiers (`teleprompt_pause`, `services_list`).

The leading `com.` is **deliberately not used**. Reverse-DNS encoding
("I work for a `.com`, here's my org's tld") is organizational
information the Captain does not have and does not need. The leading
segment is repurposed to encode **source-of-truth**, which is what the
runtime actually needs to know.

### Currently shipping

- `local.skippy.chat`  (`apps/SkippyChat/`)  — the text surface
- `local.skippy.droid` (`apps/SkippyDroid/`) — the HUD / glasses mirror

Both Android `applicationId`s are exactly those strings.

### Reserved (illustrative; not yet built)

- `local.skippy.star`     — Star Map (when it lives on-phone)
- `local.skippy.navigate` — Walking / driving directions
- `local.skippy.snap`     — "Snap it big time" rolling capture
- `skippytel.skippy.chat` — when the chat backend talks to SkippyTel
- `remote.starmap.demo`   — the in-app mock star-map producer
- `remote.dj.organizer`   — the cross-Tailnet DJ Music Organizer view

A future agent who notices the asymmetry between the GitHub repo name
(`zPhone`) and the package IDs (`local.skippy.*`) **must not** propose
unifying them. The asymmetry is the doctrine. A future agent who
notices the lack of `com.` **must not** add it back. That, too, is the
doctrine.

### Exception: dynamically composed dispatch IDs

`PassthroughServer` composes voice-control intent IDs at runtime as
`"${mount.view.id}.${verb}"`. When the view's id is already three-word
(e.g. `remote.starmap.demo`), the resulting dispatch id is **four**
words (`remote.starmap.demo.advance`). This is correct: a third-party
producer's tool gets a verb appended. It is the only allowed exception
to the three-word rule, because the third segment is itself a tool name
and the fourth segment is the verb.

---

## 4. What's locked, what's flexible

**Locked (three-word, `local.skippy.*` for zPhone-internal):**

- Android `applicationId`: `local.skippy.chat`, `local.skippy.droid`
- Gradle `namespace`: same
- Source-tree paths: `apps/SkippyChat/app/src/main/java/local/skippy/chat/`,
  `apps/SkippyDroid/app/src/main/java/local/skippy/droid/`
- Logcat tags: `Local.Skippy:*`, `Local.Skippy.Chat:*`,
  `Local.Skippy.Dispatcher:*`, `Local.Skippy.PhraseBiaser:*`,
  `Local.Skippy.Services:*`, `Local.Skippy.PassthroughServer:*`, etc.
  (capitalised because Android `Log.d` tags conventionally are; the
  three-word structure is preserved.)
- Voice-intent dispatcher IDs: `local.skippy.navigate`,
  `local.skippy.cancel`, `local.skippy.teleprompt_*`, etc.
- FeatureModule `id` fields: `local.skippy.clock`,
  `local.skippy.battery`, `local.skippy.passthrough`, etc.
- The persona itself — Skippy's response names, doctrine in
  `~/development/CLAUDE.md`, operatic-play stage directions.
- The PC-side companion service: **SkippyTel** (see
  `SKIPPYTEL_BRIEF.md`). When SkippyTel exposes facilities back into
  the zPhone, those use the `skippytel.skippy.<tool>` form.

**Flexible (zPhone):**

- The GitHub repository name (`aaronsrhodes2/zPhone`)
- The device-facing product name on any future packaging or marketing
- The release tag prefix at the project level (`zPhone v0.1.0`)
- Whatever the Captain wants to call the device when speaking to other
  humans

**Out of scope for the three-word rule (deliberately):**

- Apple-side bundle IDs in `apps/SkippyAR/`, `apps/SkippyVoice/`, and
  `apps/SkippyGlassesMac/`. They still carry `com.skippy.*` because
  Apple's app-store / TCC / launch-services machinery assumes
  reverse-DNS. A future "Apple-side three-word rename" plan can revisit
  if the Captain wants symmetry.
- Tailscale MagicDNS hostnames (`skippy-pc`, etc.) — different layer,
  different vocabulary, intentionally separate.
- JSON wire-protocol field names inside the passthrough protocol (e.g.
  `"context_mode"` as a key name) — those are protocol fields, not
  identifiers in the three-word namespace.

If a layer ever needs both names — e.g. a release announcement —
"zPhone, powered by Skippy" is the canonical phrasing.

---

## 5. IS / IS NOT — quick disambiguation

**zPhone IS:**
- The Android device (Galaxy S23 today; whatever runs `local.skippy.droid`
  + `local.skippy.chat` tomorrow).
- The GitHub project that holds this code.
- The thing that pairs with the VITURE Luma Ultra glasses over USB-C
  DisplayPort Alt Mode.

**zPhone IS NOT:**
- The persona. That's Skippy.
- A tool namespace. There are no `zphone.*` packages. Ever.
- A replacement for the Skippy brand. zPhone is the body; Skippy is who
  lives in it.

**Skippy IS:**
- The AI persona — voice, dispatch, replies, doctrine.
- The owner segment in the three-word namespace for tools the Captain
  built (everything `<source>.skippy.<tool>`).
- The brand SkippyTel (PC brain) and SkippyGlassesMac (macOS dev
  surface) inherit from.

**Skippy IS NOT:**
- The hardware. The hardware is the zPhone (and the VITURE glasses,
  which have their own name).
- The GitHub project. That's zPhone.
- The leading segment. The leading segment is `local` / `skippytel` /
  `remote`, which says where the tool lives, not who built it.

**`local` / `skippytel` / `remote` IS:**
- A closed vocabulary. Exactly those three values. Nothing else.
- A bridge to the URI / host where the tool can be reached.

**`local` / `skippytel` / `remote` IS NOT:**
- An ownership statement. (That's `<owner>`, the second word.)
- An organizational tld. (`com.` / `info.` / `org.` carry that meaning;
  we don't.)

---

## 6. Why the local folder stays `Skippy/`

The local development folder is `~/development/Skippy/`. The v0.1.0
release renamed the GitHub remote but deliberately did not touch:

- The folder name itself
- Hard-coded paths in `Makefile`, `scripts/deploy_phone.sh`,
  `misc/PC_DEV_SETUP.md`
- IDE workspace configurations
- Operatic-play stage directions referencing "Skippy AR System"

Renaming the folder would touch dozens of relative paths, every Claude
project memory under
`~/.claude/projects/-Users-aaronrhodes-development-Skippy/`, and every
agent's mental model. The cost is real; the benefit is zero (the GitHub
URL doesn't care what the local folder is called).

If a future session ever finds a real reason to rename the folder
(e.g. a second Captain pulling the repo cold and confused), it gets
its own dedicated migration plan. Not a side-effect of any other work.

---

## 7. Cross-references

- `~/development/CLAUDE.md` — Skippy persona's response names
  ("Skippy / Skipster / Beercan / Admiral") and the operatic-play /
  session-log standing orders.
- `misc/SKIPPYTEL_BRIEF.md` — handoff brief for the PC-side SkippyTel
  service. Demonstrates the "Skippy-named, lives outside the zPhone"
  pattern; under the three-word convention SkippyTel-hosted tools take
  the `skippytel.skippy.<tool>` form.
- `misc/PASSTHROUGH_STANDARD.md` + `misc/PASSTHROUGH_PROTOCOL.md` —
  the cross-project doctrine docs whose style this file mirrors. The
  passthrough mount IDs follow the three-word rule (`remote.<owner>.<tool>`
  for external producers).
- `misc/PC_DEV_SETUP.md` — handoff doc for the PC build host. References
  the zPhone repo URL but builds `local.skippy.*` APKs.

---

## 8. Quarantine clause (mirrors PASSTHROUGH_STANDARD §11)

This document is the canonical statement of the naming doctrine.
Other repos and external collaborators (e.g. the DJ Music Organizer,
SkippyStarMap producer, future passthrough apps) read from this file
and implement against the namespace rules in §3. Nobody copies the
namespace into their own source as a hard-coded list — they read this
file, follow the rule, and stay current.
