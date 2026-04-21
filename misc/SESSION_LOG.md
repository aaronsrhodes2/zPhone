## SESSION 1 — April 20, 2026
**Players:** Captain Aaron Rhodes, Skippy the Magnificent

---

CAPTAIN: My PC is quickly becoming a store of running models from Flask and Ollama and others, as well as a physics MCP I want to make. What is the best way for the PC to manage all those models and services?

SKIPPY: Docker Compose for everything that speaks HTTP (Ollama, Flask, future model servers). Physics MCP stays native on the Mac — Claude Desktop connects via stdio and can't exec into Docker containers. Named volume for Ollama models so they survive `docker compose down`. Flask gets port 5001 because macOS AirPlay Receiver squats on 5000. One `make up` to rule them all.

**Key decisions made:**
- Docker Compose as the orchestration layer (Captain's choice)
- Ollama + Flask inside Docker; Physics MCP server runs natively on Mac
- Named volume `skippy_ollama_models` — model downloads survive container restarts
- Flask port 5001 on host (avoids macOS AirPlay conflict on 5000)
- `OLLAMA_HOST=0.0.0.0` required so Flask can reach Ollama across the Docker network
- Flask calls Ollama at `http://ollama:11434` (service name), never `localhost`
- Physics MCP uses FastMCP + stdio transport; Claude Desktop launches it as a subprocess
- Constant values in constants.py are CODATA/PDG placeholders pending Sigma Ground cascade integration

**Notable moments:**
- The Skippy directory was completely empty — pure greenfield
- macOS GPU gotcha documented: Docker Desktop on Mac = Linux VM = no Metal access; Ollama in Docker is CPU-only
- Port 5000 is occupied by macOS AirPlay Receiver — a classic trap

**Files modified:**
- docker-compose.yml (created)
- .env.example (created)
- .gitignore (created)
- Makefile (created)
- services/flask-api/Dockerfile
- services/flask-api/requirements.txt
- services/flask-api/.dockerignore
- services/flask-api/app/__init__.py
- services/flask-api/app/main.py
- services/flask-api/app/ollama_client.py
- services/flask-api/app/routes/__init__.py
- services/flask-api/app/routes/health.py
- services/flask-api/app/routes/physics.py
- services/physics-mcp/pyproject.toml
- services/physics-mcp/physics_mcp/__init__.py
- services/physics-mcp/physics_mcp/server.py
- services/physics-mcp/physics_mcp/tools/__init__.py
- services/physics-mcp/physics_mcp/tools/kinematics.py
- services/physics-mcp/physics_mcp/tools/constants.py
- services/model-servers/README.md
- models/README.md
- misc/SESSION_LOG.md

---

## SESSION 2 — April 20, 2026
**Players:** Captain Aaron Rhodes, Skippy the Magnificent

---

CAPTAIN: I want VITURE AR glasses, hands-free dev interface with SkippyVoice, and a PC AI inference server. Five parallel workstreams. Ready for a good next four hours?

SKIPPY: Built SkippyVoice (Mac menubar app — LED bar, silence detector, SFSpeechRecognizer, paste-on-silence to Claude Desktop), wired Karabiner-Elements (Caps Lock macro + Right Option dictation), scaffolded SkippyAR (external display scene, HUD with aviation compass tape, Translation layer), and built video passthrough for the glasses. Got both apps to BUILD SUCCEEDED. Ran out of runway on actually seeing the passthrough running — that's first up next session.

**Key decisions made:**
- SkippyVoice uses its own SFSpeechRecognizer → clipboard paste → Cmd+V into Claude Desktop. No macOS Dictation, no modifier keys, no Karabiner needed for mic trigger.
- macOS 14.0 deployment target (was 13.0 — @Observable requires 14)
- iOS 18.0 deployment target for SkippyAR (Apple Translation framework requires it)
- xcodegen info: key removed from both project.yml files — Info.plist now survives xcodegen generate
- Passthrough architecture: fullscreen NSWindow on VITURE external screen, AVCaptureSession from UVC camera, HUD drawn on top. Clock + compass upper-right only.
- VITURE must be in extended display mode (not mirror) for passthrough window to work. Captain set this up.
- Captain has Apple Watch — future goal: Watch as universal authentication device. Noted for later.
- Five future AR apps logged: Star Map, Coding Assistant HUD, Teleprompter, Notification Mirror, Object Measurement.

**Notable moments:**
- "I am not really fond of using modifier keys. We need to be moving away from keyboards." — drove clipboard-paste architecture
- "These glasses are growing on me."
- Captain correctly identified the core AR problem: "I see my whole Mac screen, I need to just see the HUD and nothing else." — video passthrough architecture built in response
- Both projects hit BUILD SUCCEEDED. Passthrough untested at end of session — first thing next session.

**Files modified:**
- apps/SkippyVoice — AudioDeviceManager, CommandRecognizer, KeystrokeInjector, VoiceSubmitEngine, LEDBarLabel, MenuBarView, SkippyVoiceApp, Passthrough/ (3 new files), Info.plist, entitlements, project.yml
- apps/SkippyAR — HUDOverlayView, HUDViewModel, GlassesRootView, ContentView, GlassesNotConnectedView, Translation/ (3 new files), GlassesAppState, project.yml
- ~/.config/karabiner/karabiner.json

---

## SESSION 3 — April 20, 2026
**Players:** Captain Aaron Rhodes, Skippy the Magnificent

---

CAPTAIN: Full power, full terminal access, glasses plugged in. Android platform purchased — let's build SkippyDroid. Also add a future feature: multi-battery display of all battery-powered devices in the network.

SKIPPY: Installed Android SDK + JDK 17 + emulator via Homebrew, no Android Studio required. Scaffolded the full SkippyDroid Kotlin + Compose project from scratch with 6-layer architecture, feature flag system, Battery Panel module (S23 self + remote Tailscale nodes), GitLab CI pipeline, and Makefile integration. Created SkippyS23 AVD (Pixel 7 Pro / API 34 / ARM64). First `./gradlew assembleDebug` — BUILD SUCCESSFUL. All 6 compass unit tests pass.

**Key decisions made:**
- Kotlin + Jetpack Compose (not Flutter) — VITURE SDK is Java/Kotlin native
- 6-layer architecture: Transport → Device → Image Processing → Context Engine → Voice → Feature Modules → Compositor
- Feature flag system from day one — each module has `var enabled: Boolean`, toggleable without rebuild
- Battery Panel monitors: S23 (BatteryManager API, self), Glasses (VITURE SDK or USB HID), remote nodes (GET /battery over Tailscale, polled 60s)
- Battery display: thin strip at screen edge, color-coded green/yellow/red, degrades gracefully offline
- GitLab Pipelines: lint + unit tests + assemble APK on push to main/MR
- Makefile: `make droid-run`, `make droid-stop`, `make droid-logs`, `make droid-test`, `make droid-emu`
- AVD name: SkippyS23, Pixel 7 Pro skin, API 34, ARM64, 4GB RAM
- PC URL placeholder: `http://skippy-pc:5001` — replace with Tailscale hostname when configured
- Captain: "I don't care about selling it, I just want it to work flawlessly for me, and I want the development cycle to be a joy."

**Notable moments:**
- Homebrew avdmanager (from `android-commandlinetools` cask) doesn't find SDK targets; fixed by installing `cmdline-tools;latest` into ANDROID_HOME via sdkmanager, then using the SDK-internal avdmanager
- gradle-wrapper.jar from GitHub raw URL (42KB) was silently corrupt — fixed by running `gradle wrapper` after installing Gradle via brew
- `Theme.Material.NoTitleBar.Fullscreen` not in API 34 — replaced with `Theme.Material.Light.NoActionBar` + `windowFullscreen`
- Battery Panel added mid-session from Captain's message during build — landed in the first working APK

**Files created:**
- apps/SkippyDroid/ (entire project — see plan for full listing)
- apps/SkippyDroid/app/src/main/java/com/skippy/droid/layers/ (FeatureModule.kt, DeviceLayer.kt, TransportLayer.kt)
- apps/SkippyDroid/app/src/main/java/com/skippy/droid/compositor/Compositor.kt
- apps/SkippyDroid/app/src/main/java/com/skippy/droid/features/ (clock, compass, battery modules)
- apps/SkippyDroid/app/src/test/java/com/skippy/droid/CompassModuleTest.kt
- apps/SkippyDroid/.gitlab-ci.yml

**Files modified:**
- Makefile (added droid-* targets)

---

## SESSION 4 — April 21, 2026
**Players:** Captain Aaron Rhodes, Skippy the Magnificent

---

CAPTAIN: I see it on the android simulator. Now we need the glasses to see it as passthrough hud. / Can you wire the glasses up to see the simulated hud in passthrough mode? / Okay, so you are saying that on the mac we are going to get the extended background instead of the camera feed no matter what and we cannot hijack it so we are the only ones feeding the glasses? If that's the case, I could set my mac wallpaper to black, would that simulate it well enough? / Okay, proceed with the hijack. / OMGOMGOMGOM it works

SKIPPY: Wired Android Presentation API — GlassesPresentation shows Compositor on VITURE secondary display when S23 plugged in. Added Camera2 passthrough (Layer 2): PassthroughCamera manages dual-surface priority (glasses steal camera from phone; phone reclaims on disconnect). Clarified Mac path: VITURE on Mac shows extended desktop — cannot be bypassed by Android app. Built SkippyGlassesMac: macOS menu-bar utility that covers the VITURE display entirely with an NSWindow at .screenSaver level. Black canvas + cyan clock + compass (--° on Mac, no magnetometer) + GPS. Captain launched it. HUD appeared in the glasses. OMGOMGOMGOM.

**Key decisions made:**
- Android Presentation API: GlassesPresentation created/dismissed by MainActivity's DisplayManager.DisplayListener on DISPLAY_CATEGORY_PRESENTATION events
- PassthroughCamera dual-surface priority: glasses always win; phone reclaims on detachGlasses()
- SkippyGlassesMac: LSUIElement=YES (no Dock icon), MenuBarExtra for Quit, NSWindow.Level.screenSaver covers full external screen
- Compass shows --° on Mac (no magnetometer); real heading comes from S23 IMU via SkippyDroid
- Camera passthrough on Mac: not possible (Mac drives the DisplayPort, Android Presentation API requires S23). Confirmed via Captain's question.
- .screenSaver window level confirmed working — macOS desktop fully buried on VITURE

**Notable moments:**
- "OMGOMGOMGOM it works" — Captain's first HUD in the glasses
- stopUpdatingHeading() unavailable on macOS (Mac has no magnetometer) — heading removed from Mac HUD, shows --°
- authorizedWhenInUse unavailable on macOS — replaced with authorizedAlways check
- Black wallpaper suggestion explored but rejected in favour of true fullscreen window hijack
- Camera passthrough on Android: two compile errors fixed (onRequestPermissionsResult removed from ComponentActivity in modern AndroidX; replaced with registerForActivityResult)

**Files created:**
- apps/SkippyDroid/app/src/main/java/com/skippy/droid/compositor/GlassesPresentation.kt
- apps/SkippyDroid/app/src/main/java/com/skippy/droid/layers/PassthroughCamera.kt
- apps/SkippyGlassesMac/project.yml
- apps/SkippyGlassesMac/Sources/Info.plist
- apps/SkippyGlassesMac/Sources/SkippyGlassesMacApp.swift
- apps/SkippyGlassesMac/Sources/AppDelegate.swift
- apps/SkippyGlassesMac/Sources/GlassesWindowManager.swift
- apps/SkippyGlassesMac/Sources/GlassesHUDView.swift
- apps/SkippyGlassesMac/Sources/HUDViewModel.swift
- apps/SkippyGlassesMac/SkippyGlassesMac.xcodeproj/ (generated by xcodegen)

**Files modified:**
- apps/SkippyDroid/app/src/main/java/com/skippy/droid/MainActivity.kt (Presentation API, camera passthrough, permission handling)

---

## SESSION 5 — April 21, 2026
**Players:** Captain Aaron Rhodes, Skippy the Magnificent

---

CAPTAIN: [Context resumed — navigation feature was mid-wire when previous session ended]

SKIPPY: Dropped right back in. Added NavigationModule(navEngine) to the modules list in MainActivity, ran assembleDebug with OpenJDK 17 — clean build first try. Committed 387 insertions: NavigationEngine, NavigationModule, the build.gradle.kts secret injection, and the wired MainActivity.

**Key decisions made:**
- NavigationEngine owns all Directions API logic — `navigateTo(destination, mode)` is the single public entry point for future voice trigger ("Hey Skippy, navigate to X")
- Step advancement thresholds: 15m walking / 40m driving (GPS accuracy at speed is lower)
- NavigationModule self-hides when `!nav.isNavigating` — no activeIn restriction needed
- ETA is live: `remainingDurationSeconds` sums all steps from current forward, not the original API estimate
- Mode badge (🚶/🚗) in the HUD panel so the Captain always knows which routing mode is active
- MAPS_API_KEY lives in local.properties (gitignored), injected into BuildConfig at compile time

**Notable moments:**
- Java not on PATH — found OpenJDK 17 via Homebrew at `/opt/homebrew/Cellar/openjdk@17/17.0.18/...`; build succeeded without Android Studio installed at CLI
- 387 lines landed clean, zero compile errors — the navigation engine wrote straight through

**Files modified:**
- apps/SkippyDroid/app/src/main/java/com/skippy/droid/features/navigation/NavigationEngine.kt (new)
- apps/SkippyDroid/app/src/main/java/com/skippy/droid/features/navigation/NavigationModule.kt (new)
- apps/SkippyDroid/app/src/main/java/com/skippy/droid/MainActivity.kt (NavigationModule wired in)
- apps/SkippyDroid/app/build.gradle.kts (MAPS_API_KEY BuildConfig injection)

---
