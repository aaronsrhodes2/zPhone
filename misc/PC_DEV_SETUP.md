# PC Dev Setup — Skippy Build Host Handoff

> Audience: a fresh agent (or Captain) standing up the Skippy dev environment on
> the SkippyTel host (Windows, RTX 4070 Ti Super) so the PC can build SkippyDroid
> and SkippyChat APKs and push them to the phone wirelessly over Tailnet.
>
> The Mac dev environment is the reference. Everything in this doc is "make the
> PC look enough like the Mac that `make droid-deploy-remote PHONE=<ip>:<port>`
> works the same way it does today."

---

## 0. The dev loop, in one screen

This is the steady-state. If the PC can do this, handoff is done:

```bash
git pull                                           # get latest Skippy
make droid-build                                    # APK in apps/SkippyDroid/.../debug/
make droid-deploy-remote PHONE=100.x.y.z:5555       # build + adb connect + install
make chat-deploy-remote  PHONE=100.x.y.z:5555       # same for SkippyChat
```

Replace `100.x.y.z:5555` with the phone's Tailnet IP and ADB port. For the live
emulator running on the Mac today: `100.109.117.8:5555` (proven path —
`./scripts/deploy_phone.sh skippychat 100.109.117.8:5555` from the Mac
installs cleanly).

For the real S23 the port comes from Settings → Developer options → Wireless
debugging (a stable port for `adb connect`, distinct from the random
pair-port). Pair once, connect every session.

---

## 1. Toolchain — what to install

| Tool | Version | Notes |
|---|---|---|
| **JDK 17** | Temurin 17 (Adoptium) | Set `JAVA_HOME` to its install root. AGP 8.5.2 refuses anything older. |
| **Android SDK** | API 34 (Android 14) | Either via Android Studio Setup → SDK Manager, or standalone `cmdline-tools` (smaller). Set `ANDROID_HOME` to the SDK root. |
| **platform-tools** | latest | Provides `adb`. Add `$ANDROID_HOME/platform-tools` to `PATH`. |
| **emulator + system-images** | `system-images;android-34;google_apis_playstore;arm64-v8a` | Same image SkippyS23 AVD uses on the Mac. Skip if PC won't run an emulator (deploy goes to the real S23 instead). |
| **Tailscale** | latest | Sign into the same tailnet the Mac and phone are on. Captain's tailnet auto-assigns hostnames; the PC will be `skippy-pc` (already true). |
| **git-bash or WSL or MSYS Make** | any | The Makefile is GNU Make + bash. Pick one shell. |
| **Gradle** | not needed standalone | Each app has `gradlew`; Gradle bootstraps itself. |

The Mac uses `ANDROID_HOME=$HOME/Library/Android/sdk` and
`JAVA_HOME=/opt/homebrew/opt/openjdk@17`. The Makefile already honors pre-set
`ANDROID_HOME` / `JAVA_HOME` (see lines 85-90), so on the PC just export them
to wherever you installed each, e.g.:

```bash
# in ~/.bashrc (git-bash) or your WSL profile:
export ANDROID_HOME=/c/Android/sdk            # or wherever you put it
export JAVA_HOME=/c/Program\ Files/Eclipse\ Adoptium/jdk-17.0.x-hotspot
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$PATH"
```

PowerShell equivalent:

```powershell
$env:ANDROID_HOME = "C:\Android\sdk"
$env:JAVA_HOME    = "C:\Program Files\Eclipse Adoptium\jdk-17.0.x-hotspot"
$env:PATH         = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:PATH"
```

Verify:

```bash
java -version          # → openjdk 17.x
adb version            # → Android Debug Bridge 1.0.41 (or newer)
```

---

## 2. AVD replication — the SkippyS23 emulator

The Mac runs a Galaxy-S23-shaped AVD called `SkippyS23`. The Makefile target
`droid-emu` (lines 128-135) creates it on first run; on the PC the same target
works after the system image is installed:

```bash
sdkmanager "system-images;android-34;google_apis_playstore;arm64-v8a"
sdkmanager "platform-tools" "platforms;android-34" "emulator"
make droid-emu          # creates SkippyS23 AVD if missing, then boots it
```

The AVD config (`~/.android/avd/SkippyS23.avd/config.ini`) carries
`hw.keyboard=yes` so the host keyboard types into the emulator — useful when
typing into SkippyChat from the dev host. If you create the AVD manually
instead of via the Makefile, set that line by hand (see Session 11e in the
plan log).

**Skip the emulator entirely** if you only target the real S23. The wireless
OTA lane below works against either with no code changes.

---

## 3. Wireless ADB pairing — one-time per device

### Real S23 (when the device arrives)

On the phone:

1. Settings → About phone → tap **Build number** 7× to unlock Developer options.
2. Settings → Developer options → **Wireless debugging** → ON.
3. Tap "Pair device with pairing code." Note the **IP:port** and 6-digit code.
4. Note the **separate** ADB port shown on the same screen (used for
   `adb connect`, distinct from the pair-port).
5. Install Tailscale from Play Store; sign into the Captain's tailnet. Note
   the phone's MagicDNS name (or `100.x.y.z` IP) on the Tailscale device list.

On the PC, pair once:

```bash
make phone-pair PAIR=100.x.y.z:<pair-port> CODE=<6-digit>
```

(Equivalent to `./scripts/deploy_phone.sh pair 100.x.y.z:<pair-port> <code>`.)

The pair-port is random per Wireless-debugging-restart; the connect-port is
stable. **Pair-port is single-use** — after the first successful pair you
never need it again unless the phone is reset.

### Emulator (today's dev-loop dry run)

No pairing needed. Tailscale carries plain TCP, ADB on the emulator binds
`5555`, and any Tailnet host can `adb connect` directly:

```bash
make droid-deploy-remote PHONE=100.109.117.8:5555
```

Use this from the PC right after handoff to validate the toolchain before
the real S23 arrives.

---

## 4. Standing dev loop

### bash / git-bash / WSL

```bash
make droid-build                                       # local build (smoke test)
make droid-deploy-remote PHONE=100.x.y.z:5555          # build + push HUD over Tailnet
make chat-deploy-remote  PHONE=100.x.y.z:5555          # build + push Chat over Tailnet
adb -s 100.x.y.z:5555 logcat -s 'Local.Skippy:*' 'Local.Skippy.Chat:*' AndroidRuntime:E *:S
```

Both `*-deploy-remote` targets shell out to `scripts/deploy_phone.sh`, which
runs `gradlew assembleDebug`, `adb connect <PHONE>`, then
`adb -s <PHONE> install -r <apk>`. Idempotent; safe to re-run.

### PowerShell equivalents (no Make required)

If a PC agent insists on staying inside PowerShell:

```powershell
# build SkippyDroid
Push-Location apps/SkippyDroid
./gradlew assembleDebug
Pop-Location

# push it
$phone = "100.x.y.z:5555"
adb connect $phone
adb -s $phone install -r apps/SkippyDroid/app/build/outputs/apk/debug/app-debug.apk

# tail logs
adb -s $phone logcat -s 'Local.Skippy:*' 'Local.Skippy.Chat:*' AndroidRuntime:E *:S
```

The Makefile + bash path is the canonical one — keep it green. PowerShell
is documented here so a fresh PC agent isn't blocked if Make is broken
on its host before they fix it.

---

## 5. Tailnet sanity checks

If `adb connect` hangs or returns "no route to host," diagnose in this order:

```bash
tailscale status                        # PC sees phone in the device list?
tailscale ping <phone-magicdns>         # round-trip < 50ms on a healthy mesh
nc -vz 100.x.y.z 5555                   # ADB port reachable?
adb kill-server && adb start-server     # fix flaky local ADB
```

The phone's Tailscale must be **on**. The S23 will Tailscale-sleep when
screen-off + battery-low; tap the Tailscale tile to bring it back.
The emulator's Tailscale (the existing install on `SkippyS23`) wakes
when the AVD is booted.

---

## 6. Files this setup touches

| Path | Role |
|---|---|
| `Makefile` | `droid-deploy-remote`, `chat-deploy-remote`, `phone-pair` targets |
| `scripts/deploy_phone.sh` | Backbone — `pair` / `skippydroid` / `skippychat` subcommands |
| `apps/SkippyDroid/` | Builds to `app/build/outputs/apk/debug/app-debug.apk` |
| `apps/SkippyChat/` | Same APK path under `apps/SkippyChat/` |
| `~/.android/avd/SkippyS23.avd/config.ini` | `hw.keyboard=yes` for host-keyboard input into the emulator |

---

## 7. What's NOT in this setup (deferred — separate plans)

- **SkippyTel `POST /deploy/<app>` endpoint** — the PC-side HTTP wrapper around
  `scripts/deploy_phone.sh` so Captain can voice-command "deploy" from
  anywhere on Tailnet. Script is shaped for verbatim reuse; endpoint itself
  is a separate dispatch.
- **Auto-deploy on git push** — manual `make` trigger only in Phase 1. CI/CD
  is a later session.
- **Production signing** — debug builds only for now. Release-signing key,
  `signingConfigs`, and Play upload are deferred until there's something
  worth shipping outside the lab.
- **Building from inside Docker on the PC** — the PC runs the Skippy services
  in Docker (Ollama, Flask), but Android builds run on the host JDK/SDK, not
  inside a container. Don't try to dockerize the Android build chain — it's
  a swamp.

---

## 8. Verification — handoff is "done" when

1. `java -version` shows JDK 17 on the PC shell of choice.
2. `adb version` works from the same shell.
3. `tailscale status` shows the PC, the Mac, and the phone (or emulator) all
   in the same tailnet.
4. From the PC: `make droid-deploy-remote PHONE=100.109.117.8:5555` (the
   Mac-hosted emulator's Tailnet IP) builds and installs cleanly. **This is
   the gold-standard dry run** — it proves the entire lane the real S23 will
   use, against a target that's already alive on the network.
5. `make chat-deploy-remote PHONE=100.109.117.8:5555` does the same for Chat.
6. `adb -s 100.109.117.8:5555 logcat -s 'Local.Skippy:*' 'Local.Skippy.Chat:*'` tails live
   logs from the Mac emulator, on the PC.

When all six pass on the PC, the Mac is no longer a critical-path build host.

---

## 9. Open questions left for the PC agent

1. **Repo location.** The PC clones into `C:\Skippy` or
   `~/development/Skippy` — Captain's call. Pick one and stick to it.
2. **Make vs PowerShell.** Both documented above. Captain's standing
   preference is the bash/Make path; choose it unless there's a hard reason
   not to.
3. **Long-term: who hosts the AVD?** Today the Mac runs SkippyS23. The PC
   has 16-32GB free RAM and a real GPU — if the PC takes over the emulator,
   the Mac becomes a thin client. Not required for handoff; revisit when
   the dev loop is steady.
