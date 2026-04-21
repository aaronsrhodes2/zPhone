from flask import Flask
from app.routes.health import health_bp
from app.routes.physics import physics_bp


def create_app() -> Flask:
    app = Flask(__name__)
    app.register_blueprint(health_bp)
    app.register_blueprint(physics_bp, url_prefix="/physics")
    return app


app = create_app()

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)
