FROM python:3.12-slim

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /workspace

COPY tools/glm_ocr_litert/requirements.txt /tmp/glm-ocr-litert-requirements.txt
RUN python -m pip install --no-cache-dir -r /tmp/glm-ocr-litert-requirements.txt

ENTRYPOINT ["python", "/workspace/tools/glm_ocr_litert/convert_glm_ocr_to_litert.py"]
