#!/usr/bin/env bash
#
# Reproducibly convert GLM-OCR vision and cached decoder components to LiteRT.
#
# The successful conversion boundary is:
#   1. multimodal prefill with vision-feature insertion and explicit 3D RoPE;
#   2. one-token cached decode with the prefill-derived RoPE delta.
#
# LiteRT-Torch's lightweight conversion path keeps the shared weights in one
# two-signature FlatBuffer while avoiding the conversion-time memory pressure
# seen in the original dense-constant attempt.

set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

MACHINE_NAME="glm-ocr-litert"
CONTAINER_NAME="glm-ocr-litert-convert"
MACHINE_CPUS=6
MACHINE_MEMORY_MIB=16384
MACHINE_DISK_GIB=80
MIN_HOST_FREE_GIB=12
HF_CACHE="${HF_HOME:-${HOME}/.cache/huggingface}"
OUTPUT_DIR="${REPO_ROOT}/build/glm-ocr-litert"
STOP_MACHINE=0
SKIP_SMOKE=0
TEXT_ONLY_LITERTLM=0

readonly MODEL_REPO="zai-org/GLM-OCR"
readonly MODEL_REVISION="ca5d8b3e287e52589e37c28385d9655ee4372f9d"
readonly MODEL_SNAPSHOT_RELATIVE="hub/models--zai-org--GLM-OCR/snapshots/${MODEL_REVISION}"
readonly CONTAINER_IMAGE="docker.io/library/python:3.11-slim@sha256:00af38ae2ed311628970782e8a2d7f014d8909dbc63cb97bc0a158187f4db045"
readonly CONTAINER_PLATFORM="linux/amd64"
readonly TORCH_VERSION="2.7.1"
readonly LITERT_TORCH_VERSION="0.9.1"

usage() {
  cat <<'EOF'
Usage: model-assets/huggingface/convert-glm-ocr-litert.sh [options]

Creates or reuses a rootless 16 GiB Podman machine, builds a pinned CPU-only
Linux converter environment, downloads the pinned GLM-OCR Hugging Face
snapshot when necessary, and exports the multimodal LiteRT components:

  glm_ocr_multimodal_decoder_combined_fp16.tflite
  glm_ocr_vision_68x46_fp32.tflite
  glm_ocr_litert_conversion_manifest.json

With --text-only-litertlm, it instead exports:

  glm_ocr_text_decoder_q8_ekv1536.litertlm
  glm_ocr_text_decoder_q8_ekv1536_manifest.json

Options:
  --machine NAME          Podman machine/connection name.
  --container NAME        Disposable converter container name.
  --hf-cache DIR          Host Hugging Face cache directory.
  --output-dir DIR        Output directory inside this repository.
  --memory-mib N          New-machine memory in MiB (default: 16384).
  --cpus N                New-machine CPU count (default: 6).
  --disk-gib N            New-machine disk size in GiB (default: 80).
  --min-free-gib N        Required host free space (default: 12).
  --text-only-litertlm    Export only the standard text decoder package.
  --skip-smoke            Skip the tiny conversion/runtime validation gate.
  --stop-machine          Stop the dedicated machine after success.
  -h, --help              Show this help.

The script replaces only the named disposable converter container. It never
removes or resizes another Podman machine. Stop other memory-heavy machines
before running this script.
EOF
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

log() {
  printf '\n[glm-ocr-litert] %s\n' "$*"
}

require_uint() {
  case "$2" in
    ''|*[!0-9]*) die "$1 must be a positive integer, got: $2" ;;
    0) die "$1 must be greater than zero" ;;
  esac
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --machine)
      [[ $# -ge 2 ]] || die "--machine requires a value"
      MACHINE_NAME="$2"
      shift 2
      ;;
    --container)
      [[ $# -ge 2 ]] || die "--container requires a value"
      CONTAINER_NAME="$2"
      shift 2
      ;;
    --hf-cache)
      [[ $# -ge 2 ]] || die "--hf-cache requires a value"
      HF_CACHE="$2"
      shift 2
      ;;
    --output-dir)
      [[ $# -ge 2 ]] || die "--output-dir requires a value"
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --memory-mib)
      [[ $# -ge 2 ]] || die "--memory-mib requires a value"
      MACHINE_MEMORY_MIB="$2"
      shift 2
      ;;
    --cpus)
      [[ $# -ge 2 ]] || die "--cpus requires a value"
      MACHINE_CPUS="$2"
      shift 2
      ;;
    --disk-gib)
      [[ $# -ge 2 ]] || die "--disk-gib requires a value"
      MACHINE_DISK_GIB="$2"
      shift 2
      ;;
    --min-free-gib)
      [[ $# -ge 2 ]] || die "--min-free-gib requires a value"
      MIN_HOST_FREE_GIB="$2"
      shift 2
      ;;
    --text-only-litertlm)
      TEXT_ONLY_LITERTLM=1
      shift
      ;;
    --skip-smoke)
      SKIP_SMOKE=1
      shift
      ;;
    --stop-machine)
      STOP_MACHINE=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown option: $1"
      ;;
  esac
done

require_uint "--memory-mib" "${MACHINE_MEMORY_MIB}"
require_uint "--cpus" "${MACHINE_CPUS}"
require_uint "--disk-gib" "${MACHINE_DISK_GIB}"
require_uint "--min-free-gib" "${MIN_HOST_FREE_GIB}"

command -v podman >/dev/null 2>&1 || die "podman is required"
command -v shasum >/dev/null 2>&1 || die "shasum is required"

mkdir -p "${HF_CACHE}" "${OUTPUT_DIR}"
HF_CACHE="$(cd "${HF_CACHE}" && pwd)"
OUTPUT_DIR="$(cd "${OUTPUT_DIR}" && pwd)"

case "${OUTPUT_DIR}/" in
  "${REPO_ROOT}/"*) ;;
  *) die "--output-dir must be inside ${REPO_ROOT}" ;;
esac

readonly CONTAINER_OUTPUT_DIR="/workspace${OUTPUT_DIR#${REPO_ROOT}}"
readonly CONTAINER_CHECKPOINT="/root/.cache/huggingface/${MODEL_SNAPSHOT_RELATIVE}"
readonly EXPORTER="/workspace/tools/glm_ocr_litert_lm/export_glm_ocr_multimodal_component.py"
readonly VISION_EXPORTER="/workspace/tools/glm_ocr_litert_lm/export_glm_ocr_vision_component.py"
readonly VALIDATOR="/workspace/tools/glm_ocr_litert_lm/validate_litert_multimodal_component.py"
readonly TEXT_EXPORTER="/workspace/tools/glm_ocr_litert_lm/export_glm_ocr_decoder.py"

readonly COMBINED_NAME="glm_ocr_multimodal_decoder_combined_fp16.tflite"
readonly VISION_NAME="glm_ocr_vision_68x46_fp32.tflite"
readonly SMOKE_NAME="glm_ocr_multimodal_smoke_fp16.tflite"
readonly MANIFEST_NAME="glm_ocr_litert_conversion_manifest.json"
readonly TEXT_NAME="glm_ocr_text_decoder_q8_ekv1536.litertlm"
readonly TEXT_MANIFEST_NAME="glm_ocr_text_decoder_q8_ekv1536_manifest.json"

readonly COMBINED_PATH="${OUTPUT_DIR}/${COMBINED_NAME}"
readonly VISION_PATH="${OUTPUT_DIR}/${VISION_NAME}"
readonly SMOKE_PATH="${OUTPUT_DIR}/${SMOKE_NAME}"
readonly COMBINED_PARTIAL="${COMBINED_PATH}.partial"
readonly VISION_PARTIAL="${VISION_PATH}.partial"
readonly SMOKE_PARTIAL="${SMOKE_PATH}.partial"
TEXT_STAGING_DIR=""

cleanup() {
  status=$?
  if [[ -n "${TEXT_STAGING_DIR}" && -d "${TEXT_STAGING_DIR}" ]]; then
    rm -rf "${TEXT_STAGING_DIR}"
  fi
  if [[ ${status} -ne 0 ]]; then
    rm -f "${COMBINED_PARTIAL}" "${VISION_PARTIAL}" "${SMOKE_PARTIAL}"
    printf '\n[glm-ocr-litert] failed; completed artifacts were preserved\n' >&2
  fi
}
trap cleanup EXIT

check_host_space() {
  available_kib="$(df -Pk "${OUTPUT_DIR}" | awk 'NR == 2 {print $4}')"
  required_kib=$((MIN_HOST_FREE_GIB * 1024 * 1024))
  if [[ -z "${available_kib}" ]] || [[ "${available_kib}" -lt "${required_kib}" ]]; then
    die "at least ${MIN_HOST_FREE_GIB} GiB free is required on the output filesystem"
  fi
}

machine_field() {
  podman machine inspect "${MACHINE_NAME}" --format "$1"
}

podman_in_machine() {
  podman --connection "${MACHINE_NAME}" "$@"
}

check_host_space

if ! podman machine inspect "${MACHINE_NAME}" >/dev/null 2>&1; then
  log "creating rootless Podman machine ${MACHINE_NAME}"
  podman machine init \
    --cpus "${MACHINE_CPUS}" \
    --memory "${MACHINE_MEMORY_MIB}" \
    --disk-size "${MACHINE_DISK_GIB}" \
    "${MACHINE_NAME}"
else
  actual_memory="$(machine_field '{{.Resources.Memory}}')"
  actual_cpus="$(machine_field '{{.Resources.CPUs}}')"
  actual_disk="$(machine_field '{{.Resources.DiskSize}}')"
  [[ "${actual_memory}" -ge "${MACHINE_MEMORY_MIB}" ]] ||
    die "${MACHINE_NAME} has ${actual_memory} MiB RAM; ${MACHINE_MEMORY_MIB} MiB is required"
  [[ "${actual_cpus}" -ge "${MACHINE_CPUS}" ]] ||
    die "${MACHINE_NAME} has ${actual_cpus} CPUs; ${MACHINE_CPUS} are required"
  [[ "${actual_disk}" -ge "${MACHINE_DISK_GIB}" ]] ||
    die "${MACHINE_NAME} has ${actual_disk} GiB disk; ${MACHINE_DISK_GIB} GiB is required"
fi

if [[ "$(machine_field '{{.State}}')" != "running" ]]; then
  log "starting Podman machine ${MACHINE_NAME}"
  podman machine start "${MACHINE_NAME}"
fi

connection_ready=0
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  if podman_in_machine info >/dev/null 2>&1; then
    connection_ready=1
    break
  fi
  sleep 2
done
[[ "${connection_ready}" -eq 1 ]] || die "Podman connection ${MACHINE_NAME} did not become ready"

if podman_in_machine container exists "${CONTAINER_NAME}"; then
  log "replacing disposable converter container ${CONTAINER_NAME}"
  podman_in_machine rm -f "${CONTAINER_NAME}"
  podman machine ssh "${MACHINE_NAME}" sudo fstrim -av >/dev/null 2>&1 || true
fi

log "creating CPU-only linux/amd64 converter container"
podman_in_machine run -d \
  --name "${CONTAINER_NAME}" \
  --platform "${CONTAINER_PLATFORM}" \
  --env HF_HUB_DISABLE_TELEMETRY=1 \
  --volume "${REPO_ROOT}:/workspace:Z" \
  --volume "${HF_CACHE}:/root/.cache/huggingface" \
  "${CONTAINER_IMAGE}" \
  sleep infinity >/dev/null

log "installing pinned CPU PyTorch ${TORCH_VERSION}"
podman_in_machine exec "${CONTAINER_NAME}" \
  pip install \
    --no-cache-dir \
    --no-deps \
    --index-url https://download.pytorch.org/whl/cpu \
    "torch==${TORCH_VERSION}"

log "installing the exact successful LiteRT-Torch environment"
podman_in_machine exec -i "${CONTAINER_NAME}" \
  pip install --no-cache-dir --no-deps -r /dev/stdin <<'REQUIREMENTS'
absl-py==2.5.0
ai-edge-litert==2.1.5
ai-edge-quantizer==0.7.0
annotated-doc==0.0.5
anyio==4.14.2
backports.strenum==1.2.8
certifi==2026.7.22
charset-normalizer==3.4.9
click==8.4.2
filelock==3.29.0
fire==0.7.1
flatbuffers==25.12.19
fsspec==2026.4.0
h11==0.16.0
hf-xet==1.5.2
httpcore==1.0.9
httpx==0.28.1
huggingface_hub==1.25.1
idna==3.18
immutabledict==4.2.1
jax==0.10.2
jaxlib==0.10.2
jaxtyping==0.3.11
Jinja2==3.1.6
kagglehub==1.0.2
kagglesdk==0.1.35
lark==1.3.1
litert-converter==0.2.0
litert-lm-builder==0.14.0
litert-torch==0.9.1
markdown-it-py==4.2.0
MarkupSafe==3.0.3
mdurl==0.1.2
ml_dtypes==0.5.4
mpmath==1.3.0
multipledispatch==1.0.0
networkx==3.6.1
numpy==2.4.6
opt_einsum==3.4.0
ordered-set==4.1.0
packaging==26.2
protobuf==7.35.1
Pygments==2.20.0
PyYAML==6.0.3
regex==2026.7.19
requests==2.34.2
rich==15.0.0
safetensors==0.8.0
scipy==1.17.1
sentencepiece==0.2.2
shellingham==1.5.4
sympy==1.14.0
tabulate==0.10.0
termcolor==3.3.0
tokenizers==0.22.2
tomli==2.4.1
torchao==0.17.0
tqdm==4.70.0
transformers==5.14.1
typer==0.27.0
typing_extensions==4.12.2
urllib3==2.7.0
wadler_lindig==0.1.7
xdsl==0.28.0
REQUIREMENTS

podman_in_machine exec "${CONTAINER_NAME}" pip check
if podman_in_machine exec "${CONTAINER_NAME}" \
  sh -c "pip freeze | grep -E '^(cuda-|nvidia-|triton==)'"; then
  die "CUDA packages entered the CPU-only converter environment"
fi
podman_in_machine exec "${CONTAINER_NAME}" python -c \
  'import torch; from litert_torch._convert import interface; print("torch", torch.__version__, "LiteRT imports OK")'

log "resolving pinned Hugging Face snapshot ${MODEL_REPO}@${MODEL_REVISION}"
podman_in_machine exec "${CONTAINER_NAME}" python -c \
  "from huggingface_hub import snapshot_download; print(snapshot_download('${MODEL_REPO}', revision='${MODEL_REVISION}', cache_dir='/root/.cache/huggingface/hub'))"
podman_in_machine exec "${CONTAINER_NAME}" sh -c \
  "test -f '${CONTAINER_CHECKPOINT}/model.safetensors' || test -f '${CONTAINER_CHECKPOINT}/model.safetensors.index.json'"

if [[ "${TEXT_ONLY_LITERTLM}" -eq 1 ]]; then
  log "checking LiteRT-Torch generative converter imports"
  podman_in_machine exec \
    -w /workspace/tools/glm_ocr_litert_lm \
    "${CONTAINER_NAME}" \
    python -c \
    'from export_glm_ocr_decoder import install_tensorflow_schema_compat; install_tensorflow_schema_compat(); from litert_torch.generative.utilities import converter; print("LiteRT-LM converter imports OK")'

  log "running GLM-OCR decoder structural gates"
  podman_in_machine exec -w /workspace "${CONTAINER_NAME}" \
    python tools/glm_ocr_litert_lm/test_glm_ocr_decoder.py

  TEXT_STAGING_DIR="$(mktemp -d "${OUTPUT_DIR}/.glm-ocr-text-litertlm.XXXXXX")"
  readonly CONTAINER_TEXT_STAGING_DIR="/workspace${TEXT_STAGING_DIR#${REPO_ROOT}}"
  text_oom_before="$(podman_in_machine exec "${CONTAINER_NAME}" \
    awk '$1 == "oom_kill" {print $2}' /sys/fs/cgroup/memory.events)"
  text_export_started_epoch="$(date '+%s')"

  log "exporting standard text-only LiteRT-LM decoder"
  podman_in_machine exec -w /workspace "${CONTAINER_NAME}" python "${TEXT_EXPORTER}" \
    --checkpoint "${CONTAINER_CHECKPOINT}" \
    --output-dir "${CONTAINER_TEXT_STAGING_DIR}" \
    --output-prefix "glm_ocr_text_decoder" \
    --format litertlm \
    --quantize dynamic_int8 \
    --cache-length 1536 \
    --prefill-length 128 \
    --prefill-length 512 \
    --prefill-length 798

  [[ -f "${TEXT_STAGING_DIR}/${TEXT_NAME}" ]] ||
    die "text-only exporter did not produce ${TEXT_NAME}"
  mv "${TEXT_STAGING_DIR}/${TEXT_NAME}" "${OUTPUT_DIR}/${TEXT_NAME}"
  text_export_elapsed_seconds=$(($(date '+%s') - text_export_started_epoch))
  text_oom_after="$(podman_in_machine exec "${CONTAINER_NAME}" \
    awk '$1 == "oom_kill" {print $2}' /sys/fs/cgroup/memory.events)"
  [[ "${text_oom_after}" -eq "${text_oom_before}" ]] ||
    die "the converter cgroup recorded an OOM kill"

  log "inspecting LiteRT-LM package sections"
  podman_in_machine exec "${CONTAINER_NAME}" litert-lm-peek \
    --litertlm_file "${CONTAINER_OUTPUT_DIR}/${TEXT_NAME}" \
    >"${OUTPUT_DIR}/glm_ocr_text_decoder_q8_ekv1536_peek.txt.partial"
  mv \
    "${OUTPUT_DIR}/glm_ocr_text_decoder_q8_ekv1536_peek.txt.partial" \
    "${OUTPUT_DIR}/glm_ocr_text_decoder_q8_ekv1536_peek.txt"

  text_bytes="$(wc -c <"${OUTPUT_DIR}/${TEXT_NAME}" | tr -d ' ')"
  text_sha="$(shasum -a 256 "${OUTPUT_DIR}/${TEXT_NAME}" | awk '{print $1}')"
  text_created_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  cat >"${OUTPUT_DIR}/${TEXT_MANIFEST_NAME}.partial" <<EOF
{
  "created_at": "${text_created_at}",
  "model": {
    "repo": "${MODEL_REPO}",
    "revision": "${MODEL_REVISION}"
  },
  "converter": {
    "container_platform": "${CONTAINER_PLATFORM}",
    "python_image": "${CONTAINER_IMAGE}",
    "torch": "${TORCH_VERSION}+cpu",
    "litert_torch": "${LITERT_TORCH_VERSION}",
    "ai_edge_litert": "2.1.5",
    "litert_lm_builder": "0.14.0",
    "quantization": "dynamic_int8"
  },
  "artifact": {
    "file": "${TEXT_NAME}",
    "bytes": ${text_bytes},
    "sha256": "${text_sha}",
    "model_type": "generic",
    "context_length": 1536,
    "prefill_lengths": [128, 512, 798],
    "decode_rope_delta": 0,
    "stop_token_ids": [59246, 59253],
    "tokenizer": "huggingface_json"
  },
  "conversion_telemetry": {
    "elapsed_seconds": ${text_export_elapsed_seconds},
    "oom_kill_before": ${text_oom_before},
    "oom_kill_after": ${text_oom_after}
  },
  "validation": {
    "pytorch_structural_tests": true,
    "litertlm_section_inspection": true,
    "device_runtime": false,
    "multimodal": false
  }
}
EOF
  mv \
    "${OUTPUT_DIR}/${TEXT_MANIFEST_NAME}.partial" \
    "${OUTPUT_DIR}/${TEXT_MANIFEST_NAME}"

  log "text-only LiteRT-LM conversion complete"
  printf 'model: %s/%s (%s bytes)\n' "${OUTPUT_DIR}" "${TEXT_NAME}" "${text_bytes}"
  printf '       sha256 %s\n' "${text_sha}"
  printf 'manifest: %s/%s\n' "${OUTPUT_DIR}" "${TEXT_MANIFEST_NAME}"

  if [[ "${STOP_MACHINE}" -eq 1 ]]; then
    log "stopping Podman machine ${MACHINE_NAME}"
    podman machine stop "${MACHINE_NAME}"
  fi
  exit 0
fi

if [[ "${SKIP_SMOKE}" -eq 0 ]]; then
  log "running tiny conversion and dynamic multimodal/RoPE runtime gate"
  rm -f "${SMOKE_PARTIAL}"
  podman_in_machine exec -w /workspace "${CONTAINER_NAME}" python "${EXPORTER}" \
    --smoke-test \
    --dtype float16 \
    --signature-mode both \
    --lightweight-conversion \
    --runtime-constant-folding off \
    --output "${CONTAINER_OUTPUT_DIR}/${SMOKE_NAME}.partial"
  mv "${SMOKE_PARTIAL}" "${SMOKE_PATH}"
  podman_in_machine exec -w /workspace "${CONTAINER_NAME}" python "${VALIDATOR}" \
    --model "${CONTAINER_OUTPUT_DIR}/${SMOKE_NAME}" \
    --sequence-length 6 \
    --image-token-count 4 \
    --cache-length 16 \
    --layers 2 \
    --kv-heads 2 \
    --head-dim 8 \
    --report "${CONTAINER_OUTPUT_DIR}/glm_ocr_multimodal_smoke_validation.json"
fi

check_host_space

export_started_epoch="$(date '+%s')"
oom_before="$(podman_in_machine exec "${CONTAINER_NAME}" \
  awk '$1 == "oom_kill" {print $2}' /sys/fs/cgroup/memory.events)"

log "exporting combined multimodal prefill and cached-decode model"
rm -f "${COMBINED_PARTIAL}"
podman_in_machine exec -w /workspace "${CONTAINER_NAME}" python "${EXPORTER}" \
  --checkpoint "${CONTAINER_CHECKPOINT}" \
  --dtype float16 \
  --signature-mode both \
  --lightweight-conversion \
  --runtime-constant-folding off \
  --sequence-length 798 \
  --image-token-start 5 \
  --image-token-count 782 \
  --cache-length 1536 \
  --output "${CONTAINER_OUTPUT_DIR}/${COMBINED_NAME}.partial"
mv "${COMBINED_PARTIAL}" "${COMBINED_PATH}"
export_elapsed_seconds=$(($(date '+%s') - export_started_epoch))

vision_export_started_epoch="$(date '+%s')"
log "exporting fixed 68x46-patch pixel vision encoder"
rm -f "${VISION_PARTIAL}"
podman_in_machine exec -w /workspace "${CONTAINER_NAME}" python "${VISION_EXPORTER}" \
  --checkpoint "${CONTAINER_CHECKPOINT}" \
  --grid-height 68 \
  --grid-width 46 \
  --attention-query-chunk-size 391 \
  --lightweight-conversion \
  --output "${CONTAINER_OUTPUT_DIR}/${VISION_NAME}.partial"
mv "${VISION_PARTIAL}" "${VISION_PATH}"
vision_export_elapsed_seconds=$(($(date '+%s') - vision_export_started_epoch))

oom_after="$(podman_in_machine exec "${CONTAINER_NAME}" \
  awk '$1 == "oom_kill" {print $2}' /sys/fs/cgroup/memory.events)"
cgroup_peak_bytes="$(podman_in_machine exec "${CONTAINER_NAME}" \
  cat /sys/fs/cgroup/memory.peak)"
[[ "${oom_after}" -eq "${oom_before}" ]] ||
  die "the converter cgroup recorded an OOM kill"

inspect_signature() {
  model_name="$1"
  report_name="$2"
  podman_in_machine exec -w /workspace "${CONTAINER_NAME}" python -c \
    'import json, sys; from ai_edge_litert.interpreter import Interpreter; model=Interpreter(model_path=sys.argv[1]); print(json.dumps(model.get_signature_list(), indent=2, sort_keys=True))' \
    "${CONTAINER_OUTPUT_DIR}/${model_name}" \
    >"${OUTPUT_DIR}/${report_name}.partial"
  mv "${OUTPUT_DIR}/${report_name}.partial" "${OUTPUT_DIR}/${report_name}"
}

log "inspecting full LiteRT signature contracts"
inspect_signature \
  "${COMBINED_NAME}" \
  "glm_ocr_multimodal_decoder_combined_signatures.json"
inspect_signature \
  "${VISION_NAME}" \
  "glm_ocr_vision_68x46_signatures.json"

log "running full cached-decode runtime gate"
podman_in_machine exec -w /workspace "${CONTAINER_NAME}" python "${VALIDATOR}" \
  --model "${CONTAINER_OUTPUT_DIR}/${COMBINED_NAME}" \
  --sequence-length 798 \
  --image-token-count 782 \
  --cache-length 1536 \
  --layers 16 \
  --kv-heads 8 \
  --head-dim 128 \
  --decode-only \
  --report \
    "${CONTAINER_OUTPUT_DIR}/glm_ocr_multimodal_decoder_combined_validation.json"

combined_bytes="$(wc -c <"${COMBINED_PATH}" | tr -d ' ')"
combined_sha="$(shasum -a 256 "${COMBINED_PATH}" | awk '{print $1}')"
vision_bytes="$(wc -c <"${VISION_PATH}" | tr -d ' ')"
vision_sha="$(shasum -a 256 "${VISION_PATH}" | awk '{print $1}')"
created_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

cat >"${OUTPUT_DIR}/${MANIFEST_NAME}.partial" <<EOF
{
  "created_at": "${created_at}",
  "model": {
    "repo": "${MODEL_REPO}",
    "revision": "${MODEL_REVISION}"
  },
  "converter": {
    "container_platform": "${CONTAINER_PLATFORM}",
    "python_image": "${CONTAINER_IMAGE}",
    "torch": "${TORCH_VERSION}+cpu",
    "litert_torch": "${LITERT_TORCH_VERSION}",
    "ai_edge_litert": "2.1.5",
    "weight_storage": "float16",
    "compute": "float32",
    "lightweight_conversion": true,
    "runtime_constant_folding": false
  },
  "shape_contract": {
    "sequence_length": 798,
    "image_token_start": 5,
    "image_token_count": 782,
    "cache_length": 1536,
    "layers": 16,
    "kv_heads": 8,
    "head_dim": 128
  },
  "artifacts": {
    "combined_decoder": {
      "file": "${COMBINED_NAME}",
      "bytes": ${combined_bytes},
      "sha256": "${combined_sha}",
      "signatures": [
        "prefill_798_vision_features",
        "decode_with_rope_delta"
      ]
    },
    "vision_encoder": {
      "file": "${VISION_NAME}",
      "bytes": ${vision_bytes},
      "sha256": "${vision_sha}",
      "signature_layout": {
        "patch": "patch_embed_68x46",
        "blocks": "vision_block_00_68x46 through vision_block_23_68x46",
        "head": "vision_head_68x46"
      },
      "input": [3128, 1176],
      "patch_embedding": [3128, 1024],
      "output": [782, 1536],
      "image_size": [952, 644],
      "grid_thw": [1, 68, 46],
      "attention_query_chunk_size": 391
    }
  },
  "conversion_telemetry": {
    "decoder_elapsed_seconds": ${export_elapsed_seconds},
    "vision_elapsed_seconds": ${vision_export_elapsed_seconds},
    "cgroup_peak_bytes": ${cgroup_peak_bytes},
    "oom_kill_before": ${oom_before},
    "oom_kill_after": ${oom_after}
  },
  "validation": {
    "smoke_runtime": $([[ "${SKIP_SMOKE}" -eq 0 ]] && printf 'true' || printf 'false'),
    "full_signature_inspection": true,
    "full_decode_runtime": true,
    "full_prefill_runtime": false
  }
}
EOF
mv "${OUTPUT_DIR}/${MANIFEST_NAME}.partial" "${OUTPUT_DIR}/${MANIFEST_NAME}"

log "conversion complete"
printf 'model: %s (%s bytes)\n' "${COMBINED_PATH}" "${combined_bytes}"
printf '       sha256 %s\n' "${combined_sha}"
printf 'vision: %s (%s bytes)\n' "${VISION_PATH}" "${vision_bytes}"
printf '        sha256 %s\n' "${vision_sha}"
printf 'manifest: %s/%s\n' "${OUTPUT_DIR}" "${MANIFEST_NAME}"

if [[ "${STOP_MACHINE}" -eq 1 ]]; then
  log "stopping Podman machine ${MACHINE_NAME}"
  podman machine stop "${MACHINE_NAME}"
fi
