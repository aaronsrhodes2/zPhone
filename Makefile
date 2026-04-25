.DEFAULT_GOAL := help
COMPOSE := docker compose

# ── Core lifecycle ────────────────────────────────────────────────────────────

up:          ## Start all services (detached)
	$(COMPOSE) up -d

up-fg:       ## Start all services in foreground (all logs visible)
	$(COMPOSE) up

down:        ## Stop and remove containers (keeps volumes and images)
	$(COMPOSE) down

restart:     ## Restart all services
	$(COMPOSE) restart

build:       ## Rebuild all service images (use after requirements.txt changes)
	$(COMPOSE) build

rebuild:     ## Full rebuild — no cache
	$(COMPOSE) build --no-cache

# ── Logs ──────────────────────────────────────────────────────────────────────

logs:        ## Tail logs from all services
	$(COMPOSE) logs -f

logs-ollama: ## Tail Ollama logs only
	$(COMPOSE) logs -f ollama

logs-flask:  ## Tail Flask logs only
	$(COMPOSE) logs -f flask-api

# ── Ollama model management ───────────────────────────────────────────────────

pull-model:  ## Pull a model into Ollama. Usage: make pull-model MODEL=llama3.2
ifndef MODEL
	$(error MODEL is not set. Usage: make pull-model MODEL=llama3.2)
endif
	docker exec skippy-ollama ollama pull $(MODEL)

list-models: ## List models currently in Ollama
	docker exec skippy-ollama ollama list

rm-model:    ## Remove a model. Usage: make rm-model MODEL=llama3.2
ifndef MODEL
	$(error MODEL is not set. Usage: make rm-model MODEL=llama3.2)
endif
	docker exec skippy-ollama ollama rm $(MODEL)

# ── Shells ────────────────────────────────────────────────────────────────────

shell-ollama: ## Open a shell inside the Ollama container
	docker exec -it skippy-ollama /bin/bash

shell-flask:  ## Open a shell inside the Flask container
	docker exec -it skippy-flask /bin/bash

# ── Status and health ─────────────────────────────────────────────────────────

ps:          ## Show running containers and their status
	$(COMPOSE) ps

health:      ## Check Ollama API health from host
	curl -s http://localhost:$${OLLAMA_PORT:-11434}/api/tags | python3 -m json.tool

# ── Physics MCP server (runs locally, not in Docker) ─────────────────────────

mcp-install: ## Install physics-mcp into local venv
	# Use python3 explicitly — recent macOS releases no longer ship an
	# unversioned `python` symlink, so `python -m venv` fails on a fresh OS.
	cd services/physics-mcp && python3 -m venv .venv && .venv/bin/pip install -e ".[dev]"

mcp-run:     ## Run the physics MCP server locally (stdio mode for Claude Desktop)
	cd services/physics-mcp && .venv/bin/python -m physics_mcp.server

mcp-test:    ## Run MCP server tests
	cd services/physics-mcp && .venv/bin/pytest

# ── SkippyDroid (Android) ─────────────────────────────────────────────────────

DROID_DIR  := apps/SkippyDroid
DROID_PKG  := local.skippy.droid
ANDROID_HOME ?= $(HOME)/Library/Android/sdk
ANDROID_SDK_ROOT := $(ANDROID_HOME)
JAVA_HOME  ?= /opt/homebrew/opt/openjdk@17
export ANDROID_HOME
export ANDROID_SDK_ROOT
export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(ANDROID_HOME)/cmdline-tools/latest/bin:$(ANDROID_HOME)/platform-tools:$(ANDROID_HOME)/emulator:$(PATH)

droid-build:   ## Build debug APK
	cd $(DROID_DIR) && ./gradlew assembleDebug

droid-install: ## Build and install APK on connected device or running emulator
	cd $(DROID_DIR) && ./gradlew installDebug

droid-run:     ## Build, install, launch, and stream filtered logs
	cd $(DROID_DIR) && ./gradlew installDebug && \
	  adb shell am start -n $(DROID_PKG)/.MainActivity && \
	  adb logcat -s Local.Skippy:* Local.Skippy.Services:* Local.Skippy.Dispatcher:* \
	    Local.Skippy.PhraseBiaser:* Local.Skippy.PassthroughServer:* \
	    Local.Skippy.MockPassthrough:* AndroidRuntime:E *:S

droid-bridge-register: ## Show DJ Organizer registration URL for Tailscale (no adb needed)
	@echo "PassthroughServer binds 0.0.0.0:47823 and trusts Tailscale CGNAT (100.64.0.0/10)."
	@echo "DJ Organizer on aarons-macbook-pro should POST to:"
	@echo "  http://sdk-gphone64-x86_64:47823/passthrough/register  (MagicDNS)"
	@echo "  http://100.122.32.113:47823/passthrough/register        (IP fallback)"

droid-bridge-dj: ## Show DJ Organizer stream URL for Tailscale (no adb needed)
	@echo "aarons-macbook-pro is whitelisted in network_security_config.xml."
	@echo "After DJ Organizer registers, SkippyDroid fetches streams from:"
	@echo "  http://aarons-macbook-pro:7334/..."
	@echo "No bridge needed — emulator reaches Mac directly over Tailscale."

droid-bridge-skippy-tel: ## Windows portproxy :3004 -> :3003 + firewall (Tailscale emulator lane; run as Admin)
	@powershell -Command " \
	  netsh interface portproxy add v4tov4 listenport=3004 listenaddress=0.0.0.0 connectport=3003 connectaddress=127.0.0.1; \
	  New-NetFirewallRule -DisplayName 'SkippyTel Emulator Lane' -Direction Inbound -Protocol TCP -LocalPort 3004 -Action Allow -Profile Any -ErrorAction SilentlyContinue; \
	  Write-Host 'Port proxy active: 0.0.0.0:3004 -> 127.0.0.1:3003'; \
	  Write-Host 'Firewall rule active: inbound TCP 3004 allowed'; \
	  Write-Host 'Emulator can now reach SkippyTel via Tailscale at http://skippy-pc:3004' \
	"

droid-stop:    ## Force-stop SkippyDroid on device
	adb shell am force-stop $(DROID_PKG)

droid-logs:    ## Stream logcat filtered to SkippyDroid
	adb logcat -s Local.Skippy:* AndroidRuntime:E *:S

droid-test:    ## Run unit tests
	cd $(DROID_DIR) && ./gradlew test

droid-clean:   ## Clean build outputs
	cd $(DROID_DIR) && ./gradlew clean

droid-emu:     ## Start Galaxy S23 emulator (creates AVD on first run)
	@if ! avdmanager list avd | grep -q "SkippyS23"; then \
	  echo "Creating SkippyS23 AVD..." && \
	  echo no | avdmanager create avd -n SkippyS23 \
	    -k "system-images;android-34;google_apis;x86_64" \
	    -d "pixel_7_pro" --force; \
	fi
	@echo "NOTE: Set your USB mic as the default Windows recording device BEFORE this step."
	@echo "      Windows Sound settings → Input → choose USB mic → Set as default"
	emulator -avd SkippyS23 -gpu host -memory 4096 -audio winaudio &

droid-adb-connect: ## Connect to device over WiFi (usage: make droid-adb-connect IP=192.168.x.x)
ifndef IP
	$(error IP is not set. Usage: make droid-adb-connect IP=192.168.1.x)
endif
	adb connect $(IP):5555

# ── Remote deploy over Tailnet ────────────────────────────────────────────
# On the physical S23 (one-time):
#   1. Settings → About phone → tap Build number 7× → Developer options unlocked
#   2. Developer options → Wireless debugging → ON
#   3. Tap "Pair device with pairing code" — note the IP:port and 6-digit code
#   4. From this Mac:  adb pair <pair-ip>:<pair-port>   (enter the 6-digit code)
#   5. Install Tailscale from Play Store; sign in to the tailnet
#   6. Note the phone's tailnet MagicDNS name (Tailscale app → this device)
# After that, every update cycle is:
#   make droid-tailnet HOST=<magicdns-name>        # one adb connect per session
#   make droid-install                              # builds + installs over Tailnet
#   make droid-stop && make droid-run               # restart + tail logs

droid-tailnet: ## Connect to phone over Tailnet (usage: make droid-tailnet HOST=<magicdns or 100.x.x.x>)
ifndef HOST
	$(error HOST is not set. Usage: make droid-tailnet HOST=skippy-phone (MagicDNS) or HOST=100.x.x.x)
endif
	adb connect $(HOST):5555 && \
	  echo "Connected over Tailnet. ./gradlew installDebug now deploys to $(HOST)."

droid-adb-pair: ## Pair this Mac with the phone (usage: make droid-adb-pair PAIR=IP:PORT CODE=123456)
ifndef PAIR
	$(error PAIR is not set. Read IP:PORT from Settings → Dev options → Wireless debugging → Pair device with pairing code)
endif
ifndef CODE
	$(error CODE is not set. 6-digit pairing code from the phone screen)
endif
	@echo $(CODE) | adb pair $(PAIR)

# ── Wireless OTA over Tailnet (PC-handoff prep) ──────────────────────────
# Session 12 doctrine: any Tailnet host (Mac today, PC tomorrow) can build
# + push APKs to the phone (or emulator) wirelessly. The lane is plain ADB
# over plain TCP — Tailscale is the auth, just like SkippyTel.
#
# One-time per device:
#   make phone-pair PAIR=<ip>:<port> CODE=<6-digit>      # real S23 only
#   (emulator: nothing — `adb connect <ip>:5555` Just Works)
#
# Every dev cycle:
#   make droid-deploy-remote PHONE=<tailnet-ip>:<port>   # build + connect + install
#   make chat-deploy-remote  PHONE=<tailnet-ip>:<port>
#
# The emulator on this Mac is reachable from any Tailnet host as
#   sdk-gphone64-arm64.<tailnet>.ts.net:5555  (or 100.109.117.8:5555)
# so the same targets work for the dev-loop dry run today.

phone-pair: ## One-time pair with a real S23 (usage: make phone-pair PAIR=ip:port CODE=123456)
ifndef PAIR
	$(error PAIR is not set. Read IP:PORT from Settings → Dev options → Wireless debugging → Pair device with pairing code)
endif
ifndef CODE
	$(error CODE is not set. 6-digit pairing code shown on the phone)
endif
	@./scripts/deploy_phone.sh pair $(PAIR) $(CODE)

droid-deploy-remote: ## Build + push SkippyDroid over Tailnet (usage: make droid-deploy-remote PHONE=ip:port)
ifndef PHONE
	$(error PHONE is not set. Usage: make droid-deploy-remote PHONE=100.x.x.x:5555)
endif
	@./scripts/deploy_phone.sh skippydroid $(PHONE)

chat-deploy-remote: ## Build + push SkippyChat over Tailnet (usage: make chat-deploy-remote PHONE=ip:port)
ifndef PHONE
	$(error PHONE is not set. Usage: make chat-deploy-remote PHONE=100.x.x.x:5555)
endif
	@./scripts/deploy_phone.sh skippychat $(PHONE)

droid-shot:    ## Screenshot the phone/emulator screen → /tmp/skippy-droid.png
	@adb exec-out screencap -p > /tmp/skippy-droid.png && \
	  echo "Saved /tmp/skippy-droid.png ($$(wc -c < /tmp/skippy-droid.png) bytes)"

droid-shot-glasses: ## Screenshot the simulated glasses display (Overlay #1, display ID 2) → /tmp/skippy-droid-glasses.png
	@adb shell screencap -d 2 /sdcard/glasses.png && \
	  adb pull /sdcard/glasses.png /tmp/skippy-droid-glasses.png && \
	  adb shell rm /sdcard/glasses.png && \
	  echo "Saved /tmp/skippy-droid-glasses.png"

# ── VITURE glasses (emulator + physical glasses via SpaceWalker) ──────────────
# Workflow:
#   1. make glasses-setup    — creates virtual secondary display; SkippyDroid detects it
#   2. make glasses-mirror   — opens scrcpy window showing the glasses display
#   3. Drag the scrcpy window to the VITURE monitor, press F for fullscreen
#   4. make glasses-teardown — removes virtual display when done

glasses-setup: ## Create virtual 1920×1200 secondary display in emulator (triggers GlassesPresentation)
	@adb -s emulator-5554 shell settings put global overlay_display_devices 1920x1200/240 && \
	  echo "Virtual glasses display created. SkippyDroid will detect it within a few seconds."

glasses-teardown: ## Remove virtual secondary display from emulator
	@adb -s emulator-5554 shell settings delete global overlay_display_devices && \
	  echo "Virtual glasses display removed."

glasses-mirror: ## Mirror glasses display (ID 2) to a window — drag to VITURE monitor, press F for fullscreen
	scrcpy -s emulator-5554 --display-id=2 --window-title="SkippyGlasses" --always-on-top

# ── SkippyChat (Android, Phase 1 standalone) ─────────────────────────────────

CHAT_DIR := apps/SkippyChat
CHAT_PKG := local.skippy.chat

chat-build:    ## Build SkippyChat debug APK
	cd $(CHAT_DIR) && ./gradlew assembleDebug

chat-install:  ## Build and install SkippyChat on connected device or emulator
	cd $(CHAT_DIR) && ./gradlew installDebug

chat-run:      ## Build, install, launch, and stream filtered SkippyChat logs
	cd $(CHAT_DIR) && ./gradlew installDebug && \
	  adb shell am start -n $(CHAT_PKG)/.MainActivity && \
	  adb logcat -s Local.Skippy.Chat:* AndroidRuntime:E *:S

chat-stop:     ## Force-stop SkippyChat on device
	adb shell am force-stop $(CHAT_PKG)

chat-logs:     ## Stream logcat filtered to SkippyChat
	adb logcat -s Local.Skippy.Chat:* AndroidRuntime:E *:S

chat-test:     ## Run SkippyChat unit tests
	cd $(CHAT_DIR) && ./gradlew test

chat-clean:    ## Clean SkippyChat build outputs
	cd $(CHAT_DIR) && ./gradlew clean

chat-shot:     ## Screenshot SkippyChat → /tmp/skippy-chat.png
	@adb exec-out screencap -p > /tmp/skippy-chat.png && \
	  echo "Saved /tmp/skippy-chat.png ($$(wc -c < /tmp/skippy-chat.png) bytes)"

# ── SkippyGlassesMac (macOS) ──────────────────────────────────────────────────

MAC_DIR    := apps/SkippyGlassesMac
MAC_SCHEME := SkippyGlassesMac
MAC_APP    := $(HOME)/Library/Developer/Xcode/DerivedData/SkippyGlassesMac-depshqecdkhqwkeqqzsbxkcuxxzx/Build/Products/Debug/SkippyGlassesMac.app

mac-build:   ## Build SkippyGlassesMac
	cd $(MAC_DIR) && xcodebuild -project SkippyGlassesMac.xcodeproj \
	  -scheme $(MAC_SCHEME) -configuration Debug build \
	  | grep -E "error:|warning:|BUILD"

mac-run:     ## Build, kill old instance, relaunch SkippyGlassesMac
	cd $(MAC_DIR) && xcodebuild -project SkippyGlassesMac.xcodeproj \
	  -scheme $(MAC_SCHEME) -configuration Debug build \
	  | grep -E "error:|BUILD" && \
	  pkill -x SkippyGlassesMac 2>/dev/null; sleep 0.5; open "$(MAC_APP)"

mac-watch:   ## Watch Sources/ and auto-rebuild+relaunch on any Swift file save
	@which fswatch > /dev/null || (echo "Installing fswatch…" && brew install fswatch)
	@echo "Watching $(MAC_DIR)/Sources — will rebuild on save. Ctrl-C to stop."
	@fswatch -o $(MAC_DIR)/Sources/ | while read _; do \
	  echo "── Change detected — rebuilding… ──" && \
	  cd $(MAC_DIR) && xcodebuild -project SkippyGlassesMac.xcodeproj \
	    -scheme $(MAC_SCHEME) -configuration Debug build \
	    2>&1 | grep -E "error:|BUILD" && \
	  pkill -x SkippyGlassesMac 2>/dev/null; sleep 0.5; open "$(MAC_APP)" && \
	  echo "── Relaunched ──"; \
	done

mac-logs:    ## Stream SkippyGlassesMac console output via log
	log stream --predicate 'process == "SkippyGlassesMac"' --level debug

mac-shot:    ## Screenshot the VITURE glasses display → /tmp/skippy-glasses.png
	@if [ ! -f /tmp/skippy-glasses-display.id ]; then \
	  echo "No glasses display ID found. Is SkippyGlassesMac running and are the glasses connected?"; \
	  echo "Falling back to capturing display 2…"; \
	  screencapture -x -D 2 /tmp/skippy-glasses.png && \
	  echo "Saved /tmp/skippy-glasses.png"; \
	else \
	  ID=$$(cat /tmp/skippy-glasses-display.id | tr -d '[:space:]'); \
	  screencapture -x -l$$ID /tmp/skippy-glasses.png && \
	  echo "Saved /tmp/skippy-glasses.png (display id=$$ID)"; \
	fi

mac-shot-main: ## Screenshot the Mac main display → /tmp/skippy-main.png
	@screencapture -x -D 1 /tmp/skippy-main.png && echo "Saved /tmp/skippy-main.png"

# ── Dev setup ─────────────────────────────────────────────────────────────────

init:        ## First-time setup: copy .env.example → .env
	@if [ ! -f .env ]; then cp .env.example .env && echo ".env created from .env.example"; else echo ".env already exists"; fi

nuke:        ## DANGER: Remove all containers, images, AND volumes (destroys downloaded models)
	@echo "This will delete all Skippy containers, images, and volumes including downloaded models."
	@read -p "Are you sure? [y/N] " confirm && [ "$$confirm" = "y" ] && $(COMPOSE) down -v --rmi all || echo "Aborted."

help:        ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
	  awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-18s\033[0m %s\n", $$1, $$2}'

.PHONY: up up-fg down restart build rebuild logs logs-ollama logs-flask \
        pull-model list-models rm-model shell-ollama shell-flask \
        ps health mcp-install mcp-run mcp-test init nuke help \
        mac-build mac-run mac-watch mac-logs \
        droid-build droid-install droid-run droid-stop droid-logs \
        droid-test droid-clean droid-emu droid-adb-connect \
        droid-tailnet droid-adb-pair \
        droid-bridge-register droid-bridge-dj \
        chat-build chat-install chat-run chat-stop chat-logs \
        chat-test chat-clean chat-shot \
        phone-pair droid-deploy-remote chat-deploy-remote
