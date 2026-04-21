"""
Thin wrapper for calling Ollama's HTTP API from within Docker.

OLLAMA_BASE_URL is set to http://ollama:11434 in docker-compose.yml.
'ollama' resolves to the Ollama container via the skippy-net Docker network.
Do NOT use 'localhost' — that refers to the Flask container itself.
"""

import os
import requests
from typing import Optional

OLLAMA_BASE_URL = os.environ.get("OLLAMA_BASE_URL", "http://ollama:11434")
DEFAULT_MODEL = os.environ.get("DEFAULT_MODEL", "llama3.2")


class OllamaClient:
    def __init__(
        self,
        base_url: str = OLLAMA_BASE_URL,
        default_model: str = DEFAULT_MODEL,
        timeout: int = 120,
    ):
        self.base_url = base_url.rstrip("/")
        self.default_model = default_model
        self.timeout = timeout

    def generate(
        self,
        prompt: str,
        model: Optional[str] = None,
        system: Optional[str] = None,
        stream: bool = False,
    ) -> dict:
        payload = {
            "model": model or self.default_model,
            "prompt": prompt,
            "stream": stream,
        }
        if system:
            payload["system"] = system
        r = requests.post(f"{self.base_url}/api/generate", json=payload, timeout=self.timeout)
        r.raise_for_status()
        return r.json()

    def chat(
        self,
        messages: list[dict],
        model: Optional[str] = None,
        stream: bool = False,
    ) -> dict:
        payload = {
            "model": model or self.default_model,
            "messages": messages,
            "stream": stream,
        }
        r = requests.post(f"{self.base_url}/api/chat", json=payload, timeout=self.timeout)
        r.raise_for_status()
        return r.json()

    def list_models(self) -> list[dict]:
        r = requests.get(f"{self.base_url}/api/tags", timeout=10)
        r.raise_for_status()
        return r.json().get("models", [])

    def is_healthy(self) -> bool:
        try:
            requests.get(f"{self.base_url}/api/tags", timeout=5)
            return True
        except requests.RequestException:
            return False


client = OllamaClient()
