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
	cd services/physics-mcp && python -m venv .venv && .venv/bin/pip install -e ".[dev]"

mcp-run:     ## Run the physics MCP server locally (stdio mode for Claude Desktop)
	cd services/physics-mcp && .venv/bin/python -m physics_mcp.server

mcp-test:    ## Run MCP server tests
	cd services/physics-mcp && .venv/bin/pytest

# ── SkippyDroid (Android) ─────────────────────────────────────────────────────

DROID_DIR  := apps/SkippyDroid
DROID_PKG  := com.skippy.droid
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

droid-run:     ## Build, install, and stream filtered logs
	cd $(DROID_DIR) && ./gradlew installDebug && \
	  adb shell am start -n $(DROID_PKG)/.MainActivity && \
	  adb logcat -s SkippyDroid:* AndroidRuntime:E *:S

droid-stop:    ## Force-stop SkippyDroid on device
	adb shell am force-stop $(DROID_PKG)

droid-logs:    ## Stream logcat filtered to SkippyDroid
	adb logcat -s SkippyDroid:* AndroidRuntime:E *:S

droid-test:    ## Run unit tests
	cd $(DROID_DIR) && ./gradlew test

droid-clean:   ## Clean build outputs
	cd $(DROID_DIR) && ./gradlew clean

droid-emu:     ## Start Galaxy S23 emulator (creates AVD on first run)
	@if ! avdmanager list avd | grep -q "SkippyS23"; then \
	  echo "Creating SkippyS23 AVD..." && \
	  echo no | avdmanager create avd -n SkippyS23 \
	    -k "system-images;android-34;google_apis;arm64-v8a" \
	    -d "pixel_7_pro" --force; \
	fi
	emulator -avd SkippyS23 -gpu host -memory 4096 &

droid-adb-connect: ## Connect to device over WiFi (usage: make droid-adb-connect IP=192.168.x.x)
ifndef IP
	$(error IP is not set. Usage: make droid-adb-connect IP=192.168.1.x)
endif
	adb connect $(IP):5555

droid-shot:    ## Screenshot the phone/emulator screen → /tmp/skippy-droid.png
	@adb exec-out screencap -p > /tmp/skippy-droid.png && \
	  echo "Saved /tmp/skippy-droid.png ($$(wc -c < /tmp/skippy-droid.png) bytes)"

droid-shot-glasses: ## Screenshot the simulated glasses display (Overlay #1 in emulator) → /tmp/skippy-droid-glasses.png
	@adb exec-out screencap -p -d 1 > /tmp/skippy-droid-glasses.png && \
	  echo "Saved /tmp/skippy-droid-glasses.png ($$(wc -c < /tmp/skippy-droid-glasses.png) bytes)"

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
        droid-test droid-clean droid-emu droid-adb-connect
