# Models

Ollama model files live in the Docker named volume `skippy_ollama_models`, not here.

To pull a model:
    make pull-model MODEL=llama3.2

To list pulled models:
    make list-models

To inspect the volume directly:
    docker volume inspect skippy_ollama_models

To remove a model (frees disk space inside the volume):
    make rm-model MODEL=llama3.2
