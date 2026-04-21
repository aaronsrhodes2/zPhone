from flask import Blueprint, jsonify
from app.ollama_client import client as ollama

health_bp = Blueprint("health", __name__)


@health_bp.route("/health")
def health():
    ollama_ok = ollama.is_healthy()
    return jsonify({
        "status": "ok",
        "ollama": "reachable" if ollama_ok else "unreachable",
        "ollama_url": ollama.base_url,
    }), 200 if ollama_ok else 503
