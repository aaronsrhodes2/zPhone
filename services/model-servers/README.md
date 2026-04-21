# Model Servers

Placeholder for future inference backends.

Uncomment the relevant service block in `docker-compose.yml` to enable.

| Service | Image | Port | Notes |
|---|---|---|---|
| vLLM | `vllm/vllm-openai` | 8000 | OpenAI-compatible API; requires Linux + NVIDIA GPU |
| llama.cpp | `ghcr.io/ggerganov/llama.cpp:server` | 8080 | CPU-friendly; add models to `llamacpp_models` volume |
| LM Studio | (run natively) | 1234 | Exposes OpenAI-compatible API; use `host.docker.internal:1234` from inside containers |
