# zPhone Android Apps — Claude Code Context

Android apps for the Skippy AR ecosystem, developed on Windows.
ADB currently connected to `emulator-5554` (AVD SkippyS23).

## Apps

```
apps/
├── SkippyChat/    local.skippy.chat    — voice chat + AI interface (primary user app)
└── SkippyDroid/   local.skippy.droid   — AR HUD overlay for landscape/glasses mode
```

## Deploy cycle — SkippyChat

```
cd D:\Aaron\development\zPhone\apps\SkippyChat
.\gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am force-stop local.skippy.chat
adb shell am start -n local.skippy.chat/.MainActivity
```

Then update the APK served from SkippyTel (phone recovery):
```
copy app\build\outputs\apk\debug\app-debug.apk D:\Aaron\development\SkippyTel\apks\skippy-chat.apk
```

## Deploy cycle — SkippyDroid

```
cd D:\Aaron\development\zPhone\apps\SkippyDroid
.\gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am force-stop local.skippy.droid
adb shell am start -n local.skippy.droid/.MainActivity
```

Then update staging:
```
copy app\build\outputs\apk\debug\app-debug.apk D:\Aaron\development\SkippyTel\apks\skippy-droid.apk
```

## Key source files — SkippyChat

```
app/src/main/java/local/skippy/chat/
├── ui/
│   ├── ChatScreen.kt       — main composable, voice + keyboard input
│   ├── MessageBubble.kt    — USER/ASSISTANT/SYSTEM bubble rendering + image display
│   └── DevDetailSheet.kt   — long-press debug sheet
├── model/
│   ├── ChatState.kt        — ChatViewModel, Message data class
│   ├── KeywordScanner.kt   — voice command detection (SMS, Bilby, mode switches)
│   └── InputMode.kt        — VIBE / PLANNING modes
├── transport/
│   └── SkippyTelClient.kt  — HTTP client for all SkippyTel endpoints
├── audio/
│   └── SpeechInputEngine.kt — Android SpeechRecognizer wrapper
├── sms/
│   ├── SmsSender.kt        — contact resolution + SmsManager dispatch
│   └── SmsReceiver.kt      — BroadcastReceiver → SharedFlow
└── compositor/
    └── ChatPalette.kt      — color constants (Green, Amber, Red, Violet, etc.)
```

## ChatPalette colors (for UI changes)

```kotlin
object ChatPalette {
    val Black   = Color(0xFF000000)
    val White   = Color(0xFFFFFFFF)
    val Green   = Color(0xFF00FF88)   // assistant messages
    val DimGreen      = Color(0xFF003322)
    val DimGreenHi    = Color(0xFF006644)
    val Cyan    = Color(0xFF00FFFF)   // reachability dot online
    val Amber   = Color(0xFFFFAA00)  // SMS bubbles, warnings
    val Red     = Color(0xFFFF4444)   // failed messages, errors
    val Violet  = Color(0xFFAA44FF)  // cloud-tier indicator dot
}
```

To add a new color: edit `compositor/ChatPalette.kt`, then rebuild + deploy.

## Voice keyword triggers (KeywordScanner.kt)

- "cancel that" / "scratch that" → clear draft
- "ship it" / "send it" → send immediately
- "let's make a plan" → PLANNING mode
- "send a text to [name], [body]" → SMS dispatch
- "what's playing" / "now playing" → Bilby now-playing query
- "next track" / "bilby next" → Bilby skip

To add a new keyword: edit `model/KeywordScanner.kt`, add to the appropriate
keyword list and `scan()` method, add result handling in `ChatScreen.kt`.

## SkippyTel endpoint map (SkippyTelClient.kt)

- `POST /intent/unmatched` → AI chat (main)
- `GET  /bilby/status`     → Bilby now-playing
- `POST /bilby/next`       → skip track
- `POST /translate/text`   → Spanish translation
- `GET  /health`           → reachability ping (10s loop)

## Build time

- `assembleDebug`: ~5-30s (cached incremental), ~60s clean
- `adb install`: ~5s
- Full cycle: ~35s warm, ~90s cold

## Wireless ADB (physical S23 over Tailscale)

One-time setup on phone:
1. Settings → About phone → tap Build number 7× (unlock dev options)
2. Developer options → Wireless debugging → ON
3. Tap "Pair device with pairing code" → note IP:port and 6-digit code
4. On PC: `adb pair <ip>:<port>` then enter the code
5. `adb connect <phone-tailscale-ip>:5555`

After setup, every session just needs:
```
adb connect <phone-tailscale-ip>:5555
```
Then all deploy commands above work against the physical phone.
