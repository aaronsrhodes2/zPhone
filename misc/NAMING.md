# Naming Doctrine — zPhone vs Skippy

**Status:** v1 · April 24 2026
**For:** anyone (PC agent, future Claude, future Captain) reading this
repo and wondering why the GitHub project, the local folder, and the
package namespace don't all agree.

The names are **deliberately** not unified. This document is why.

---

## 1. The Captain's quote (primary source)

> "We are using a zPhone, and this is the software, but when we use the
> facilities of the phone, the response to us is skippy, so these tools
> inside the zPhone are all skippy tools: skippy.chat, skippy.droid (I
> thought it was funny, I laughed for reals, beercan), skippy.star,
> skippy.navigate, skippy.etc..."
>
> — Captain Aaron Rhodes, April 24 2026

That's the doctrine. The rest of this document is just bookkeeping
around it.

---

## 2. The two names

| Layer | Name | Role |
|---|---|---|
| Device · project label · GitHub repo | **zPhone** | The phone-side surface of the Skippy AR system. The thing you hold, the thing you ship. |
| AI persona · brand · who responds | **Skippy** | The voice that answers when you use a zPhone facility. Also responds to: Skipster, Beercan, Admiral. |
| Tool / app namespace | **`com.skippy.*`** | Every tool the zPhone hosts. |

The split is functional, not accidental:

- **You speak to Skippy.** Skippy speaks back. That relationship has a
  brand, and it's older than the device that hosts it.
- **You hold a zPhone.** It's the slab the Captain points at across the
  room. zPhone is the project name because GitHub needs a noun, and
  "Skippy" is taken by the persona.
- **The tools inside are all Skippy's tools** — not the zPhone's tools.
  When `skippy.chat` answers a question, that's Skippy answering through
  the zPhone, not the zPhone answering on Skippy's behalf.

## 3. The namespace rule

Every tool that lives inside the zPhone uses the `skippy.*` namespace.
No exceptions, no `zphone.*` tools, no `com.zphone.*` packages.

Currently shipping:

- `skippy.chat` (`com.skippy.chat`) — the text surface
- `skippy.droid` (`com.skippy.droid`) — the HUD / glasses mirror

Reserved (illustrative; not yet built):

- `skippy.star` — Star Map
- `skippy.navigate` — Walking / driving directions
- `skippy.snap` — "Snap it big time" rolling capture
- `skippy.etc...` — whatever future facility the Captain wires next

A future agent who notices the asymmetry between the GitHub repo name
and the package IDs **must not** propose unifying them. The asymmetry
is the doctrine.

## 4. What's locked, what's flexible

**Locked (Skippy):**

- Package application IDs: `com.skippy.chat`, `com.skippy.droid`, etc.
- Source-tree paths: `apps/SkippyChat/`, `apps/SkippyDroid/`,
  `apps/SkippyGlassesMac/`, `apps/SkippyStarMap/`
- Logcat tags: `Skippy:*`, `Skippy.Chat:*`, `Skippy.Dispatcher:*`,
  `Skippy.PhraseBiaser:*`, `Skippy.Services:*`
- The persona itself — Skippy's response names, doctrine in
  `~/development/CLAUDE.md`, operatic-play stage directions
- The PC-side companion service: **SkippyTel** (see `SKIPPYTEL_BRIEF.md`).
  SkippyTel is Skippy's brain hosted on the PC; it stays Skippy-named
  because it's part of the persona, not the device.

**Flexible (zPhone):**

- The GitHub repository name (`aaronsrhodes2/zPhone`)
- The device-facing product name on any future packaging or marketing
- The release tag prefix at the project level (`zPhone v0.1.0`)
- Whatever the Captain wants to call the device when speaking to other
  humans

If a layer ever needs both names — e.g. a release announcement —
"zPhone, powered by Skippy" is the canonical phrasing.

## 5. IS / IS NOT — quick disambiguation

**zPhone IS:**
- The Android device (Galaxy S23 today; whatever runs `com.skippy.droid`
  + `com.skippy.chat` tomorrow).
- The GitHub project that holds this code.
- The thing that pairs with the VITURE Luma Ultra glasses over USB-C
  DisplayPort Alt Mode.

**zPhone IS NOT:**
- The persona. That's Skippy.
- A tool namespace. There are no `com.zphone.*` packages. Ever.
- A replacement for the Skippy brand. zPhone is the body; Skippy is who
  lives in it.

**Skippy IS:**
- The AI persona — voice, dispatch, replies, doctrine.
- The namespace under which all phone-side tools are organized.
- The brand SkippyTel (PC brain) and SkippyGlassesMac (macOS dev
  surface) inherit from.

**Skippy IS NOT:**
- The hardware. The hardware is the zPhone (and the VITURE glasses,
  which have their own name).
- The GitHub project. That's zPhone.

## 6. Why the local folder stays `Skippy/`

The local development folder is `~/development/Skippy/`. The v0.1.0
release renamed the GitHub remote but deliberately did not touch:

- The folder name itself
- Gradle module paths (`apps/SkippyDroid/`, `apps/SkippyChat/`)
- Hard-coded paths in `Makefile`, `scripts/deploy_phone.sh`,
  `misc/PC_DEV_SETUP.md`
- IDE workspace configurations
- Operatic-play stage directions referencing "Skippy AR System"

Renaming the folder would touch dozens of relative paths, every Claude
project memory under
`~/.claude/projects/-Users-aaronrhodes-development-Skippy/`, and every
agent's mental model. The cost is real; the benefit is zero (the GitHub
URL doesn't care what the local folder is called). Section 4 already
makes the rule explicit: external label = zPhone, internals = Skippy.

If a future session ever finds a real reason to rename the folder
(e.g. a second Captain pulling the repo cold and confused), it gets
its own dedicated migration plan. Not a side-effect of any other work.

## 7. Cross-references

- `~/development/CLAUDE.md` — Skippy persona's response names
  ("Skippy / Skipster / Beercan / Admiral") and the operatic-play /
  session-log standing orders.
- `misc/SKIPPYTEL_BRIEF.md` — handoff brief for the PC-side SkippyTel
  service. Demonstrates the "Skippy-named, lives outside the zPhone"
  pattern.
- `misc/PASSTHROUGH_STANDARD.md` + `misc/PASSTHROUGH_PROTOCOL.md` —
  the cross-project doctrine docs whose style this file mirrors.
- `misc/PC_DEV_SETUP.md` — handoff doc for the PC build host. References
  the zPhone repo URL but builds Skippy-named APKs.

## 8. Quarantine clause (mirrors PASSTHROUGH_STANDARD §11)

This document is the canonical statement of the naming doctrine.
Other repos and external collaborators (e.g. the DJ Music Organizer,
SkippyStarMap producer, future passthrough apps) read from this file
and implement against the namespace rules in §3. Nobody copies the
namespace into their own source as a hard-coded list — they read this
file, follow the rule, and stay current.
