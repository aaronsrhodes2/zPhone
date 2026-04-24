#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# deploy_phone.sh — wireless ADB build+install backbone for the Skippy phone
#
# Used by `make {droid,chat}-deploy-remote PHONE=<ip>:<port>` and intended to
# be called verbatim by a future SkippyTel `POST /deploy/<app>` endpoint
# (Session 12 deferred). The contract is:
#
#   ./scripts/deploy_phone.sh skippydroid <ip>:<port>     # build + push HUD
#   ./scripts/deploy_phone.sh skippychat  <ip>:<port>     # build + push Chat
#   ./scripts/deploy_phone.sh pair        <ip>:<port> <6-digit-code>
#
# Doctrine:
#   - Tailscale IS the auth. No tokens, no shared secrets.
#   - Plain ADB over plain TCP across the Tailnet.
#   - The same path that targets the live emulator today (100.109.117.8:5555)
#     targets the real S23 tomorrow. Different IP, identical lane.
#
# Cross-platform: bash on macOS/Linux/WSL/git-bash. Avoids `set -o pipefail`
# inside conditionals that older bashes choke on; ports cleanly to PowerShell
# wrapper if a PC agent ever wants pure Windows (PC_DEV_SETUP.md tracks that).
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

usage() {
    cat <<'EOF'
deploy_phone.sh — Skippy wireless OTA backbone

Usage:
  deploy_phone.sh skippydroid <host>:<port>
  deploy_phone.sh skippychat  <host>:<port>
  deploy_phone.sh pair        <host>:<port> <6-digit-code>

Examples:
  # Dev-loop dry run against the live emulator over Tailnet:
  deploy_phone.sh skippydroid 100.109.117.8:5555

  # Real S23, after one-time pair:
  deploy_phone.sh pair        100.x.y.z:41234 654321
  deploy_phone.sh skippydroid 100.x.y.z:5555
EOF
}

if [ $# -lt 2 ]; then
    usage
    exit 64   # EX_USAGE
fi

CMD="$1"
PHONE="$2"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Pick up the toolchain the way the Makefile does so this script also works
# when invoked outside `make` (e.g. from SkippyTel later, or directly by the
# Captain).  Honor pre-set values; default to the Mac dev box.
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

if ! command -v adb >/dev/null 2>&1; then
    echo "adb not found on PATH (looked in $ANDROID_HOME/platform-tools)." >&2
    echo "Install platform-tools or set ANDROID_HOME." >&2
    exit 69   # EX_UNAVAILABLE
fi

case "$CMD" in
    pair)
        if [ $# -lt 3 ]; then
            echo "pair requires <host>:<port> <code>" >&2
            usage
            exit 64
        fi
        CODE="$3"
        echo "── Pairing with $PHONE (code $CODE) ──"
        # adb pair reads code from stdin; piping makes it noninteractive so
        # this works as a SkippyTel HTTP backend later.
        echo "$CODE" | adb pair "$PHONE"
        echo "Paired. Now run: deploy_phone.sh skippydroid $PHONE  (or chat)"
        ;;

    skippydroid|skippychat)
        case "$CMD" in
            skippydroid)
                APP_DIR="$REPO_ROOT/apps/SkippyDroid"
                APK="$APP_DIR/app/build/outputs/apk/debug/app-debug.apk"
                PKG="local.skippy.droid"
                ;;
            skippychat)
                APP_DIR="$REPO_ROOT/apps/SkippyChat"
                APK="$APP_DIR/app/build/outputs/apk/debug/app-debug.apk"
                PKG="local.skippy.chat"
                ;;
        esac

        echo "── Building $CMD (assembleDebug) ──"
        (cd "$APP_DIR" && ./gradlew --quiet assembleDebug)

        if [ ! -f "$APK" ]; then
            echo "Build succeeded but APK missing at $APK" >&2
            exit 70   # EX_SOFTWARE
        fi

        echo "── Connecting ADB to $PHONE ──"
        # `adb connect` is idempotent; re-running on an already-connected
        # endpoint just prints "already connected" and exits 0.
        adb connect "$PHONE" >/dev/null

        # Wait briefly for the device to appear in `adb devices`.  On a fresh
        # connect it can take ~1s; on a warm session it's instant.
        for _ in 1 2 3 4 5; do
            if adb -s "$PHONE" get-state >/dev/null 2>&1; then
                break
            fi
            sleep 1
        done

        if ! adb -s "$PHONE" get-state >/dev/null 2>&1; then
            echo "Could not reach $PHONE over ADB after connect." >&2
            echo "Hints:" >&2
            echo "  - Tailscale up on both ends?  tailscale ping <phone>" >&2
            echo "  - Phone Wireless debugging still ON? (real S23 only)" >&2
            echo "  - Pair-port differs from connect-port; pair once with phone-pair." >&2
            exit 75   # EX_TEMPFAIL
        fi

        echo "── Installing $(basename "$APK") on $PHONE ──"
        adb -s "$PHONE" install -r "$APK"
        echo "── $CMD ($PKG) deployed to $PHONE ──"
        ;;

    -h|--help|help)
        usage
        ;;

    *)
        echo "Unknown command: $CMD" >&2
        usage
        exit 64
        ;;
esac
