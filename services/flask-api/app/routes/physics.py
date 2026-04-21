from flask import Blueprint, request, jsonify
from app.ollama_client import client as ollama

physics_bp = Blueprint("physics", __name__)

_SYSTEM = (
    "You are Skippy, a physics expert assistant. "
    "Answer with physical accuracy. Reference fundamental equations and constants. "
    "Do not introduce magic numbers — derive from first principles."
)


@physics_bp.route("/explain", methods=["POST"])
def explain_concept():
    data = request.get_json(silent=True) or {}
    concept = data.get("concept", "").strip()
    detail_level = data.get("detail_level", "undergraduate")

    if not concept:
        return jsonify({"error": "Missing 'concept' in request body"}), 400

    try:
        result = ollama.chat(messages=[
            {"role": "system", "content": _SYSTEM},
            {"role": "user", "content": f"Explain {concept} at the {detail_level} level."},
        ])
        return jsonify({
            "concept": concept,
            "explanation": result["message"]["content"],
            "model": result.get("model"),
        })
    except Exception as exc:
        return jsonify({"error": str(exc)}), 502


@physics_bp.route("/models", methods=["GET"])
def list_models():
    try:
        models = ollama.list_models()
        return jsonify({"models": [m["name"] for m in models]})
    except Exception as exc:
        return jsonify({"error": str(exc)}), 502
